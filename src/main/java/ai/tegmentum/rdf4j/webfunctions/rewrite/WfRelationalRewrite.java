package ai.tegmentum.rdf4j.webfunctions.rewrite;

import ai.tegmentum.rdf4j.webfunctions.rewrite.WfRelationalRegistry.Column;
import ai.tegmentum.rdf4j.webfunctions.rewrite.WfRelationalRegistry.RelationalDescriptor;
import ai.tegmentum.rdf4j.webfunctions.rewrite.WfRelationalRegistry.RelationalEntry;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.Dataset;
import org.eclipse.rdf4j.query.algebra.Join;
import org.eclipse.rdf4j.query.algebra.Service;
import org.eclipse.rdf4j.query.algebra.StatementPattern;
import org.eclipse.rdf4j.query.algebra.TupleExpr;
import org.eclipse.rdf4j.query.algebra.Var;
import org.eclipse.rdf4j.query.algebra.evaluation.QueryOptimizer;
import org.eclipse.rdf4j.query.algebra.helpers.AbstractQueryModelVisitor;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * URL-sugar rewrite: fold
 * {@code SERVICE <wf-relational:<name>>} clauses (produced upstream by
 * {@link WfFederationRewrite} for FederationSources of type
 * {@link FederationRegistry.SourceType#WF_RELATIONAL}) into the same
 * {@code SERVICE <wf:call>} envelope {@link ShapeRewrite} /
 * {@link WfFetchRewrite} already emit for direct-BGP shape hits &mdash;
 * with the descriptor's {@code sink_kind = "postgres"}, a Postgres sink
 * URL, and {@code include_graph = false} baked in at fold time.
 *
 * <p>Design memo:
 * {@code wf-conformance/docs/design/wf-relational.md} &sect;04. A
 * {@code wf-relational} source's schema DDL is the shape descriptor; the
 * federation pass emits {@code wf-relational:<name>}; this fold pass
 * turns that into a wf_fetch dispatch whose descriptor's
 * {@code sink_kind = "postgres"} steers the guest to Postgres-SQL.
 *
 * <h2>Position in the pipeline</h2>
 * Same slot as {@link WfFetchRewrite}: after {@link WfFederationRewrite}
 * (which emits the {@code wf-relational:} URLs this pass consumes) and
 * before {@link ShapeRewrite}. Empty registry or empty wf_fetch URL
 * &rarr; short-circuit; unknown name &rarr; leave the SERVICE alone.
 *
 * <p>Java sibling of {@code oxigraph-wf/src/wf_relational_rewrite.rs}.
 */
public final class WfRelationalRewrite implements QueryOptimizer {

    private static final ValueFactory VF = SimpleValueFactory.getInstance();
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    /** The URL scheme this pass recognises at the SERVICE position. */
    public static final String WF_RELATIONAL_SCHEME = "wf-relational:";

    private static final String WF_NS = "http://tegmentum.ai/ns/webfunction/";
    private static final String WF_CALL_IRI = WF_NS + "call";
    private static final String WF_WASM = WF_NS + "wasm";
    private static final String WF_ARG = WF_NS + "arg";
    private static final String RDF_TYPE =
            "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";

    private final FederationRegistry federationRegistry;
    private final WfRelationalRegistry relationalRegistry;
    private final String wfFetchUrl;
    private int rewrites;

    public WfRelationalRewrite(final FederationRegistry federationRegistry,
                               final WfRelationalRegistry relationalRegistry,
                               final String wfFetchUrl) {
        this.federationRegistry = federationRegistry;
        this.relationalRegistry = relationalRegistry;
        this.wfFetchUrl = wfFetchUrl;
    }

    public int rewriteCount() { return rewrites; }

    @Override
    public void optimize(final TupleExpr tupleExpr, final Dataset dataset, final BindingSet bindings) {
        rewrites = 0;
        if (relationalRegistry == null || relationalRegistry.isEmpty()) return;
        if (wfFetchUrl == null || wfFetchUrl.isEmpty()) return;
        tupleExpr.visit(new Walker());
    }

    /** Convenience for tests. */
    public int rewritePattern(final TupleExpr expr) {
        optimize(expr, null, null);
        return rewrites;
    }

    private final class Walker extends AbstractQueryModelVisitor<RuntimeException> {

        @Override
        public void meet(final Service service) {
            // Recurse first so nested SERVICEs get a chance to fold.
            super.meet(service);

            final Var ref = service.getServiceRef();
            if (ref == null || !ref.hasValue()) return;
            if (!(ref.getValue() instanceof IRI iri)) return;
            final String url = iri.stringValue();
            if (!url.startsWith(WF_RELATIONAL_SCHEME)) return;
            final String name = url.substring(WF_RELATIONAL_SCHEME.length());
            if (name.isEmpty()) return;

            // Defensive federation-registry check. Absent from the
            // registry is still allowed &mdash; users may reach this pass
            // via an explicit SERVICE clause.
            if (federationRegistry != null) {
                final FederationRegistry.FederationSource fs =
                        federationRegistry.byName(name);
                if (fs != null
                        && fs.sourceType() != FederationRegistry.SourceType.WF_RELATIONAL) {
                    return;
                }
            }
            final RelationalEntry entry = relationalRegistry.byName(name);
            if (entry == null) return;

            // Collect the SERVICE body's statement patterns. The body
            // must be a plain Join-tree of StatementPatterns; anything
            // else (Filter, nested Service, VALUES, ...) declines to
            // fold.
            final List<StatementPattern> sps = new ArrayList<>();
            if (!collectSps(service.getServiceExpr(), sps)) return;
            if (sps.isEmpty()) return;

            final Var subjectVar = sharedSubjectVar(sps);
            if (subjectVar == null) return;

            final Map<String, String> byPred = entry.descriptor().columnsByPredicate();
            final List<Map.Entry<String, Var>> columns = new ArrayList<>();
            for (StatementPattern sp : sps) {
                final Var pVar = sp.getPredicateVar();
                if (pVar == null || !pVar.hasValue()
                        || !(pVar.getValue() instanceof IRI predIri)) return;
                final String pStr = predIri.stringValue();
                if (RDF_TYPE.equals(pStr)) {
                    // rdf:type is structural — the anchor class is baked
                    // into the descriptor already. Skip.
                    continue;
                }
                final Var oVar = sp.getObjectVar();
                if (oVar == null || oVar.hasValue()) return;
                final String col = byPred.get(pStr);
                if (col == null) return;
                columns.add(Map.entry(col, oVar));
            }
            if (columns.isEmpty()) return;

            final TupleExpr replacement = buildService(subjectVar, entry, columns);
            service.replaceWith(replacement);
            rewrites++;
        }

        /**
         * Walk a Join-tree of StatementPatterns. Returns true only if
         * every leaf is a plain StatementPattern.
         */
        private boolean collectSps(final TupleExpr node, final List<StatementPattern> acc) {
            if (node instanceof Join j) {
                return collectSps(j.getLeftArg(), acc) && collectSps(j.getRightArg(), acc);
            }
            if (node instanceof StatementPattern sp) {
                acc.add(sp);
                return true;
            }
            return false;
        }
    }

    /** All patterns must share a single (variable) subject. */
    private static Var sharedSubjectVar(final List<StatementPattern> patterns) {
        Var chosen = null;
        for (StatementPattern sp : patterns) {
            final Var s = sp.getSubjectVar();
            if (s == null || s.hasValue()) return null;
            if (chosen == null) {
                chosen = s;
            } else if (!chosen.getName().equals(s.getName())) {
                return null;
            }
        }
        return chosen;
    }

    /**
     * Construct the {@code SERVICE <wf:call>} envelope with wf_fetch's
     * wasm URL, the descriptor literal, and one output triple per
     * requested column.
     */
    private TupleExpr buildService(final Var subjectVar,
                                   final RelationalEntry entry,
                                   final List<Map.Entry<String, Var>> mappedColumns) {
        final String cnode = "_wf_c_" + UUID.randomUUID().toString().replace("-", "");
        final String onode = "_wf_o_" + UUID.randomUUID().toString().replace("-", "");
        final Var cVar = anonVar(cnode);
        final Var oVar = anonVar(onode);

        final String descriptorJson = buildDescriptorJson(entry);

        final List<StatementPattern> body = new ArrayList<>();
        body.add(sp(cVar.clone(), constVar(VF.createIRI(WF_WASM)),
                constVar(VF.createIRI(wfFetchUrl))));
        body.add(sp(cVar.clone(), constVar(VF.createIRI(WF_ARG)),
                constVar(VF.createLiteral(descriptorJson))));

        body.add(sp(oVar.clone(),
                constVar(VF.createIRI(WF_NS + entry.descriptor().subjectColumn())),
                subjectVar.clone()));
        for (Map.Entry<String, Var> e : mappedColumns) {
            body.add(sp(oVar.clone(),
                    constVar(VF.createIRI(WF_NS + e.getKey())),
                    e.getValue().clone()));
        }

        final TupleExpr inner = joinAll(body);
        final Var serviceRef = Var.of("_wf_relational_svc",
                VF.createIRI(WF_CALL_IRI), true, true);
        return new Service(serviceRef, inner, "", new HashMap<>(), "", false);
    }

    /**
     * Assemble the wf_fetch descriptor JSON for a Postgres-backed shape.
     * Adds the three fields wf_fetch needs beyond the SQLite-descriptor
     * shape ({@code sink_kind}, {@code sink}, {@code include_graph}),
     * plus carries {@code schema_version} through so the guest can
     * honour the {@code ?_shape_version} provenance sidecar when the
     * caller asks for it (memo &sect;07).
     */
    static String buildDescriptorJson(final RelationalEntry entry) {
        final RelationalDescriptor d = entry.descriptor();
        // `sink` = "<endpoint>#<table>" mirrors the sqlite sink URL
        // format (`sqlite:///path/to.db#tablename`). The Postgres sink
        // implementation strips the fragment when connecting; the
        // fragment stays as human-readable metadata + a way for callers
        // to see which table the guest will query.
        final String sinkUrl = entry.endpoint() + "#" + d.table();

        final ObjectNode root = MAPPER.createObjectNode();
        root.put("name", entry.name());
        root.put("shape", entry.name());
        root.put("sink", sinkUrl);
        root.put("sink_kind", d.sinkKind());
        root.put("include_graph", false);
        root.put("table", d.table());
        root.put("subject_column", d.subjectColumn());

        final ObjectNode anchor = MAPPER.createObjectNode();
        if (d.anchor() != null && d.anchor().anchorClass() != null) {
            anchor.put("class", d.anchor().anchorClass());
        }
        root.set("anchor", anchor);

        final ArrayNode cols = MAPPER.createArrayNode();
        for (Column c : d.columns()) {
            final ObjectNode obj = MAPPER.createObjectNode();
            obj.put("name", c.name());
            obj.put("role", c.role());
            if (c.xsdType() != null) obj.put("type", c.xsdType());
            if (c.predicate() != null) obj.put("predicate", c.predicate());
            cols.add(obj);
        }
        root.set("columns", cols);
        root.put("emit_provenance", d.emitProvenance());
        if (d.iriTemplate() != null) root.put("iri_template", d.iriTemplate());
        if (d.schemaVersion() != null) root.put("schema_version", d.schemaVersion());
        return root.toString();
    }

    private static StatementPattern sp(final Var s, final Var p, final Var o) {
        return new StatementPattern(s, p, o);
    }

    private static Var anonVar(final String name) {
        return Var.of(name, null, true, false);
    }

    private static Var constVar(final Value v) {
        return Var.of("_const_" + Integer.toHexString(System.identityHashCode(v)) + "_"
                + v.stringValue().hashCode(), v, true, true);
    }

    private static TupleExpr joinAll(final List<StatementPattern> patterns) {
        if (patterns.isEmpty()) throw new IllegalArgumentException("empty BGP");
        TupleExpr acc = patterns.get(0);
        for (int i = 1; i < patterns.size(); i++) {
            acc = new Join(acc, patterns.get(i));
        }
        return acc;
    }
}

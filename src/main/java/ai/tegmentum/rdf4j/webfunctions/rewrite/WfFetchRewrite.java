package ai.tegmentum.rdf4j.webfunctions.rewrite;

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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * URL-sugar rewrite: fold
 * {@code SERVICE <wf-fetch:<name>>} clauses (produced upstream by
 * {@link WfFederationRewrite} for FederationSources of type
 * {@link FederationRegistry.SourceType#WF_FETCH}) into the same
 * {@code SERVICE <wf:call>} envelope {@link ShapeRewrite} already emits
 * for direct-BGP shape hits.
 *
 * <p>Design memo: {@code wf-conformance/docs/design/wf-federation.md}
 * &sect;06 (heterogeneous sources).
 *
 * <h3>Option A: shape-registry bridge, keyed by name</h3>
 * The name after {@code wf-fetch:} is looked up in both the
 * {@link FederationRegistry} (to confirm the source is
 * {@link FederationRegistry.SourceType#WF_FETCH}) and the
 * {@link ShapeRegistry} (to get the descriptor JSON, sink URL, column
 * list). Same-name bridging mirrors how {@link WfSearchRewrite} bridges
 * to {@link DocumentRegistry} today.
 *
 * <p>Java sibling of {@code oxigraph-wf/src/wf_fetch_rewrite.rs}.
 */
public final class WfFetchRewrite implements QueryOptimizer {

    private static final ValueFactory VF = SimpleValueFactory.getInstance();

    /** The URL scheme this pass recognises at the SERVICE position. */
    public static final String WF_FETCH_SCHEME = "wf-fetch:";

    private static final String WF_NS = "http://tegmentum.ai/ns/webfunction/";
    private static final String WF_CALL_IRI = WF_NS + "call";
    private static final String WF_WASM = WF_NS + "wasm";
    private static final String WF_ARG = WF_NS + "arg";
    private static final String RDF_TYPE =
            "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";

    private final FederationRegistry federationRegistry;
    private final ShapeRegistry shapeRegistry;
    private final String wfFetchUrl;
    private int rewrites;

    public WfFetchRewrite(final FederationRegistry federationRegistry,
                          final ShapeRegistry shapeRegistry,
                          final String wfFetchUrl) {
        this.federationRegistry = federationRegistry;
        this.shapeRegistry = shapeRegistry;
        this.wfFetchUrl = wfFetchUrl;
    }

    public int rewriteCount() { return rewrites; }

    @Override
    public void optimize(final TupleExpr tupleExpr, final Dataset dataset, final BindingSet bindings) {
        rewrites = 0;
        if (shapeRegistry == null || shapeRegistry.isEmpty()) return;
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
            if (!url.startsWith(WF_FETCH_SCHEME)) return;
            final String name = url.substring(WF_FETCH_SCHEME.length());
            if (name.isEmpty()) return;

            // Defensive federation-registry check. Absent from the registry
            // is still allowed &mdash; users may reach this pass via an
            // explicit SERVICE clause.
            if (federationRegistry != null) {
                final FederationRegistry.FederationSource fs =
                        federationRegistry.byName(name);
                if (fs != null
                        && fs.sourceType() != FederationRegistry.SourceType.WF_FETCH) {
                    return;
                }
            }
            final ShapeEntry shape = shapeRegistry.shapeByName(name);
            if (shape == null) return;

            // Collect the SERVICE body's statement patterns. The body must
            // be a plain Join-tree of StatementPatterns; anything else (a
            // Filter, another nested Service, VALUES, ...) declines to
            // fold.
            final List<StatementPattern> sps = new ArrayList<>();
            if (!collectSps(service.getServiceExpr(), sps)) return;
            if (sps.isEmpty()) return;

            final Var subjectVar = sharedSubjectVar(sps);
            if (subjectVar == null) return;

            final List<Map.Entry<String, Var>> columns = new ArrayList<>();
            for (StatementPattern sp : sps) {
                final Var pVar = sp.getPredicateVar();
                if (pVar == null || !pVar.hasValue()
                        || !(pVar.getValue() instanceof IRI predIri)) return;
                final String pStr = predIri.stringValue();
                if (RDF_TYPE.equals(pStr)) {
                    continue;
                }
                final Var oVar = sp.getObjectVar();
                if (oVar == null || oVar.hasValue()) return;
                final String col = shape.columnsByPredicate().get(pStr);
                if (col == null) return;
                columns.add(Map.entry(col, oVar));
            }
            if (columns.isEmpty()) return;

            final TupleExpr replacement = buildService(subjectVar, shape, columns);
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
     * Construct the same SERVICE-envelope Op that {@link ShapeRewrite}
     * emits for a direct-BGP shape hit.
     */
    private TupleExpr buildService(final Var subjectVar,
                                   final ShapeEntry shape,
                                   final List<Map.Entry<String, Var>> mappedColumns) {
        final String cnode = "_wf_c_" + UUID.randomUUID().toString().replace("-", "");
        final String onode = "_wf_o_" + UUID.randomUUID().toString().replace("-", "");
        final Var cVar = anonVar(cnode);
        final Var oVar = anonVar(onode);

        final List<StatementPattern> body = new ArrayList<>();
        body.add(sp(cVar.clone(), constVar(VF.createIRI(WF_WASM)),
                constVar(VF.createIRI(wfFetchUrl))));
        body.add(sp(cVar.clone(), constVar(VF.createIRI(WF_ARG)),
                constVar(VF.createLiteral(shape.descriptorJson()))));

        body.add(sp(oVar.clone(),
                constVar(VF.createIRI(WF_NS + shape.subjectColumnName())),
                subjectVar.clone()));
        for (Map.Entry<String, Var> e : mappedColumns) {
            body.add(sp(oVar.clone(),
                    constVar(VF.createIRI(WF_NS + e.getKey())),
                    e.getValue().clone()));
        }

        final TupleExpr inner = joinAll(body);
        final Var serviceRef = Var.of("_wf_fetch_svc",
                VF.createIRI(WF_CALL_IRI), true, true);
        return new Service(serviceRef, inner, "", new HashMap<>(), "", false);
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

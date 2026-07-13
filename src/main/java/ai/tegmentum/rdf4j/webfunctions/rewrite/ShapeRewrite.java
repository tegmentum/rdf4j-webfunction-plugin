package ai.tegmentum.rdf4j.webfunctions.rewrite;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.Dataset;
import org.eclipse.rdf4j.query.algebra.Join;
import org.eclipse.rdf4j.query.algebra.QueryModelNode;
import org.eclipse.rdf4j.query.algebra.Service;
import org.eclipse.rdf4j.query.algebra.StatementPattern;
import org.eclipse.rdf4j.query.algebra.TupleExpr;
import org.eclipse.rdf4j.query.algebra.Var;
import org.eclipse.rdf4j.query.algebra.evaluation.QueryOptimizer;
import org.eclipse.rdf4j.query.algebra.helpers.AbstractQueryModelVisitor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Planner-side BGP rewrite: route qualifying patterns to
 * {@code wf_fetch} via {@code SERVICE <wf:call>}.
 *
 * <p>When a BGP references only column predicates of a single registered
 * shape, all sharing one subject variable, rewrite the whole BGP to a
 * {@code SERVICE <wf:call>} block that invokes {@code wf_fetch.wasm}
 * against the shape's sink. The store never sees those triples.
 *
 * <p>Java port of {@code oxigraph-wf/src/shape_rewrite.rs}. In RDF4J
 * there is no explicit {@code Bgp} node &mdash; a BGP is a subtree of
 * {@link Join}s whose leaves are all {@link StatementPattern}s. We look
 * at each maximal join-of-SPs subtree with a shared subject variable
 * and rewrite it in place.
 */
public final class ShapeRewrite implements QueryOptimizer {

    private static final ValueFactory VF = SimpleValueFactory.getInstance();

    private static final String WF_NS       = "http://tegmentum.ai/ns/webfunction/";
    private static final String WF_CALL_IRI = WF_NS + "call";
    private static final String WF_WASM     = WF_NS + "wasm";
    private static final String WF_ARG      = WF_NS + "arg";
    private static final String RDF_TYPE    = RDF.TYPE.stringValue();

    private final ShapeRegistry registry;
    private final String wfFetchUrl;
    private int rewrites;

    public ShapeRewrite(final ShapeRegistry registry, final String wfFetchUrl) {
        this.registry = registry;
        this.wfFetchUrl = wfFetchUrl;
    }

    public int rewriteCount() { return rewrites; }

    @Override
    public void optimize(final TupleExpr tupleExpr, final Dataset dataset, final BindingSet bindings) {
        if (registry == null || registry.isEmpty() || wfFetchUrl == null || wfFetchUrl.isEmpty()) return;
        tupleExpr.visit(new Walker());
    }

    /** Convenience matching the Rust name. */
    public int rewritePattern(final TupleExpr expr) {
        optimize(expr, null, null);
        return rewrites;
    }

    private final class Walker extends AbstractQueryModelVisitor<RuntimeException> {

        @Override
        public void meet(final Join node) {
            // Only the topmost Join in a flat Join-tree drives the
            // rewrite &mdash; defer descendants so we flatten the whole
            // Join tree in one place.
            if (node.getParentNode() instanceof Join) {
                super.meet(node);
                return;
            }

            // Flatten the Join subtree into siblings. A Service node
            // (e.g. one emitted by a prior federation pass) is an opaque
            // sibling: we don't descend into it, but its presence must
            // not disqualify neighbouring shape-eligible SPs. RDF4J's
            // algebra has no explicit Bgp boundary, so this is the only
            // point where we can re-identify "these SPs form a BGP even
            // though a Service is joined next to them" (see wf-conformance
            // federation_wf_fetch.toml).
            final List<TupleExpr> parts = new ArrayList<>();
            flattenJoinTree(node, parts);

            final List<StatementPattern> sps = new ArrayList<>();
            final List<TupleExpr> opaques = new ArrayList<>();
            for (TupleExpr p : parts) {
                if (p instanceof StatementPattern sp) sps.add(sp);
                else opaques.add(p);
            }

            if (!sps.isEmpty()) {
                final TupleExpr rewritten = tryRewrite(sps);
                if (rewritten != null) {
                    // Rebuild as Join(opaques..., rewritten). Ordering
                    // matters: the opaques (typically a federation
                    // Service against a real SPARQL endpoint) go on the
                    // left so RDF4J evaluates them first &mdash;
                    // otherwise the wf:call output would be pushed as
                    // a VALUES block into a remote query that not
                    // every endpoint (and none of the mock endpoints
                    // used in wf-conformance federation_wf_fetch.toml)
                    // supports. Re-parent existing opaques directly
                    // rather than cloning: RDF4J's Service holds a
                    // raw serviceExpressionString + prefixDeclarations
                    // that must survive intact for
                    // SPARQLFederatedService to POST the correct body.
                    final List<TupleExpr> rebuiltParts = new ArrayList<>(opaques.size() + 1);
                    rebuiltParts.addAll(opaques);
                    rebuiltParts.add(rewritten);
                    node.replaceWith(joinAllExpr(rebuiltParts));
                    rewrites++;
                    // Give nested (non-Service) opaques a chance to
                    // trigger shape rewrites of their own.
                    for (TupleExpr op : opaques) {
                        if (!(op instanceof Service)) op.visit(this);
                    }
                    return;
                }
            }
            // No rewrite at this level. Walk into non-Service opaques
            // to let nested BGPs try. The SPs themselves have Join
            // parents and meet(StatementPattern) early-returns for
            // them, preserving the "all-or-nothing per BGP" semantics
            // (see skipsBgpWithForeignPredicate).
            for (TupleExpr op : opaques) {
                if (!(op instanceof Service)) op.visit(this);
            }
        }

        @Override
        public void meet(final Service s) {
            // Opaque boundary &mdash; the caller's chosen source owns
            // its body. Don't descend, don't rewrite anything inside.
        }

        @Override
        public void meet(final StatementPattern sp) {
            // Only rewrite a standalone SP when it's NOT part of a Join
            // subtree; parent-Join handling (above) covers that case.
            if (sp.getParentNode() instanceof Join) return;
            final TupleExpr rewritten = tryRewrite(Collections.singletonList(sp));
            if (rewritten != null) {
                sp.replaceWith(rewritten);
                rewrites++;
            }
        }

        @Override
        protected void meetNode(final QueryModelNode n) {
            n.visitChildren(this);
        }
    }

    /**
     * Flatten a Join subtree into its list of leaf {@link TupleExpr}s
     * &mdash; every non-Join descendant along the left/right spine. The
     * order mirrors an in-order walk of the Join tree.
     */
    private static void flattenJoinTree(final TupleExpr node, final List<TupleExpr> acc) {
        if (node instanceof Join j) {
            flattenJoinTree(j.getLeftArg(), acc);
            flattenJoinTree(j.getRightArg(), acc);
        } else {
            acc.add(node);
        }
    }

    /** Left-associative Join over an arbitrary list of {@link TupleExpr}. */
    private static TupleExpr joinAllExpr(final List<TupleExpr> parts) {
        if (parts.isEmpty()) throw new IllegalArgumentException("empty join");
        TupleExpr acc = parts.get(0);
        for (int i = 1; i < parts.size(); i++) {
            acc = new Join(acc, parts.get(i));
        }
        return acc;
    }

    /**
     * Inspect a collected BGP. Return the SERVICE replacement if all
     * patterns qualify for the same shape; {@code null} otherwise.
     */
    private TupleExpr tryRewrite(final List<StatementPattern> patterns) {
        if (patterns.isEmpty()) return null;
        final Var subjectVar = sharedSubjectVar(patterns);
        if (subjectVar == null) return null;

        String classIri = null;
        // (predicate IRI, object var) pairs for the column patterns.
        final List<Map.Entry<String, Var>> columns = new ArrayList<>();
        for (StatementPattern tp : patterns) {
            final Var pVar = tp.getPredicateVar();
            if (pVar == null || !pVar.hasValue() || !(pVar.getValue() instanceof IRI predIri)) return null;
            final String pStr = predIri.stringValue();
            if (RDF_TYPE.equals(pStr)) {
                final Var oVar = tp.getObjectVar();
                if (oVar == null || !oVar.hasValue() || !(oVar.getValue() instanceof IRI classI)) return null;
                classIri = classI.stringValue();
                continue;
            }
            final Var oVar = tp.getObjectVar();
            if (oVar == null || oVar.hasValue()) return null;
            columns.add(Map.entry(pStr, oVar));
        }
        if (columns.isEmpty()) return null;

        final ShapeEntry shape = resolveSharedShape(columns, classIri);
        if (shape == null) return null;

        // Map (predicate -> column name) using the shape.
        final List<Map.Entry<String, Var>> mapped = new ArrayList<>(columns.size());
        for (Map.Entry<String, Var> e : columns) {
            final String col = shape.columnsByPredicate().get(e.getKey());
            if (col == null) return null;
            mapped.add(Map.entry(col, e.getValue()));
        }

        return buildService(subjectVar, shape, mapped);
    }

    /** All patterns must share a single (variable) subject. */
    private static Var sharedSubjectVar(final List<StatementPattern> patterns) {
        Var chosen = null;
        for (StatementPattern tp : patterns) {
            final Var s = tp.getSubjectVar();
            if (s == null || s.hasValue()) return null;
            if (chosen == null) {
                chosen = s;
            } else if (!chosen.getName().equals(s.getName())) {
                return null;
            }
        }
        return chosen;
    }

    private ShapeEntry resolveSharedShape(final List<Map.Entry<String, Var>> columns,
                                          final String classIri) {
        ShapeEntry shape = null;
        for (Map.Entry<String, Var> e : columns) {
            final ShapeEntry candidate = registry.findByPredicate(e.getKey());
            if (candidate == null) return null;
            if (shape == null) {
                shape = candidate;
            } else if (!shape.name().equals(candidate.name())) {
                return null;
            }
        }
        if (shape == null) return null;
        if (classIri != null) {
            if (shape.anchorClass() == null || !shape.anchorClass().equals(classIri)) {
                return null;
            }
        }
        return shape;
    }

    /**
     * Build the {@code SERVICE <wf:call>} envelope. Shape (see
     * {@code shape_rewrite.rs::build_service}):
     * <pre>
     *   SERVICE &lt;wf:call&gt; {
     *     _:c wf:wasm &lt;wf_fetch_url&gt; ;
     *         wf:arg  "&lt;descriptor-json&gt;" .
     *     _:o wf:&lt;subject-col&gt; ?s ;
     *         wf:&lt;col1&gt;         ?var1 ;
     *         wf:&lt;col2&gt;         ?var2 .
     *   }
     * </pre>
     */
    private TupleExpr buildService(final Var subjectVar,
                                   final ShapeEntry shape,
                                   final List<Map.Entry<String, Var>> mappedColumns) {
        final String cnode = "_wf_c_" + UUID.randomUUID().toString().replace("-", "");
        final String onode = "_wf_o_" + UUID.randomUUID().toString().replace("-", "");
        final Var cVar = anonVar(cnode);
        final Var oVar = anonVar(onode);

        final List<StatementPattern> body = new ArrayList<>();
        // Config side. Each SP owns fresh Var instances &mdash; RDF4J's
        // AbstractQueryModelNode asserts each Var has at most one parent.
        body.add(sp(cVar.clone(), constVar(VF.createIRI(WF_WASM)),
                constVar(VF.createIRI(wfFetchUrl))));
        body.add(sp(cVar.clone(), constVar(VF.createIRI(WF_ARG)),
                constVar(VF.createLiteral(shape.descriptorJson()))));

        // Output side: subject_iri column + user columns.
        body.add(sp(oVar.clone(),
                constVar(VF.createIRI(WF_NS + shape.subjectColumnName())),
                subjectVar.clone()));
        for (Map.Entry<String, Var> e : mappedColumns) {
            body.add(sp(oVar.clone(),
                    constVar(VF.createIRI(WF_NS + e.getKey())),
                    e.getValue().clone()));
        }

        final TupleExpr inner = joinAll(body);
        final Var serviceRef = Var.of("_wf_svc", VF.createIRI(WF_CALL_IRI), true, true);
        // RDF4J Service signature: (serviceRef, expr, baseUri, prefixes, exprStr, silent).
        // The exprStr parameter cannot be null &mdash; Service#setExpressionString
        // downcases it during construction. A placeholder is fine; the executor
        // walks the algebra, not the source text.
        return new Service(serviceRef, inner, "", new HashMap<>(), "", false);
    }

    private static StatementPattern sp(final Var s, final Var p, final Var o) {
        return new StatementPattern(s, p, o);
    }

    private static Var anonVar(final String name) {
        return Var.of(name, null, true, false);
    }

    private static Var constVar(final Value v) {
        // Anonymous, constant Var carrying a value &mdash; the RDF4J parser
        // uses this shape for constant subjects/predicates/objects.
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

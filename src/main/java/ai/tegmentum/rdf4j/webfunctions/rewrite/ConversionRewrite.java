package ai.tegmentum.rdf4j.webfunctions.rewrite;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.Dataset;
import org.eclipse.rdf4j.query.algebra.Extension;
import org.eclipse.rdf4j.query.algebra.ExtensionElem;
import org.eclipse.rdf4j.query.algebra.QueryModelNode;
import org.eclipse.rdf4j.query.algebra.StatementPattern;
import org.eclipse.rdf4j.query.algebra.TupleExpr;
import org.eclipse.rdf4j.query.algebra.Union;
import org.eclipse.rdf4j.query.algebra.ValueConstant;
import org.eclipse.rdf4j.query.algebra.ValueExpr;
import org.eclipse.rdf4j.query.algebra.Var;
import org.eclipse.rdf4j.query.algebra.evaluation.QueryOptimizer;
import org.eclipse.rdf4j.query.algebra.helpers.AbstractQueryModelVisitor;

import java.util.ArrayList;
import java.util.List;

/**
 * Query-time rewrite that turns virtual {@code urn:wf:conversion:*} graph
 * patterns into computed triples.
 *
 * <p>Default-graph queries are untouched. Only patterns explicitly wrapped
 * in {@code GRAPH <urn:wf:conversion:X>} or {@code GRAPH ?g} trigger the
 * rewrite. See the reference file
 * ({@code oxigraph-wf/src/conversion_rewrite.rs}) for the two supported
 * cases.
 *
 * <p>Semantic gap note: RDF4J does not have an explicit {@code Graph}
 * pattern node &mdash; a {@code GRAPH { ... }} clause manifests as
 * {@link StatementPattern}s with a bound {@code contextVar}. We
 * therefore rewrite one {@link StatementPattern} at a time rather than
 * a whole BGP node. Each rewritten pattern turns into either an
 * {@link Extension} (the specific-graph case) or a {@link Union} of
 * per-rule extensions (the variable-graph case), matching the spargebra
 * shape.
 */
public final class ConversionRewrite implements QueryOptimizer {

    private static final ValueFactory VF = SimpleValueFactory.getInstance();
    private static final String CONVERSION_SCHEME = "urn:wf:conversion:";
    private static final String SUBST_VAR = "source";

    private final ConversionRegistry registry;
    private int rewrites;

    public ConversionRewrite(final ConversionRegistry registry) {
        this.registry = registry;
    }

    public int rewriteCount() { return rewrites; }

    @Override
    public void optimize(final TupleExpr tupleExpr, final Dataset dataset, final BindingSet bindings) {
        if (registry == null || registry.isEmpty()) return;
        tupleExpr.visit(new Walker());
    }

    private final class Walker extends AbstractQueryModelVisitor<RuntimeException> {

        @Override
        public void meet(final StatementPattern sp) {
            final Var ctx = sp.getContextVar();
            if (ctx == null) return; // default graph &mdash; pristine.

            // Predicate must be a concrete IRI, object must be a variable.
            final Var predVar = sp.getPredicateVar();
            if (predVar == null || !predVar.hasValue() || !(predVar.getValue() instanceof IRI predIri)) return;
            final Var objVar = sp.getObjectVar();
            if (objVar == null || objVar.hasValue()) return;

            final String pred = predIri.stringValue();

            if (ctx.hasValue()) {
                // Specific graph: `GRAPH <urn:wf:conversion:X> { ?s pred ?o }`
                if (!(ctx.getValue() instanceof IRI graphIri)) return;
                final String iri = graphIri.stringValue();
                if (!iri.startsWith(CONVERSION_SCHEME)) return;
                final ConversionRule rule = registry.ruleByGraph(iri);
                if (rule == null || !pred.equals(rule.targetPredicate())) return;

                final TupleExpr replacement = buildSpecificBranch(sp, rule, /*graphVar*/ null);
                sp.replaceWith(replacement);
                rewrites++;
                return;
            }

            // Variable graph: `GRAPH ?g { ?s pred ?o }`
            final String graphVarName = ctx.getName();
            final List<ConversionRule> rules = registry.rulesForTarget(pred);
            if (rules.isEmpty()) return;
            final List<TupleExpr> branches = new ArrayList<>(rules.size());
            for (ConversionRule rule : rules) {
                branches.add(buildSpecificBranch(sp, rule, graphVarName));
            }
            sp.replaceWith(unionOf(branches));
            rewrites++;
        }

        @Override
        protected void meetNode(final QueryModelNode n) {
            n.visitChildren(this);
        }
    }

    /**
     * Build one rewritten branch:
     * <pre>
     *   Extension(
     *     arg = StatementPattern(?s, sourcePred, ?_wfconv_o, ctx?),
     *     BIND(substituted-expression AS ?o)
     *     [, BIND(&lt;graphIri&gt; AS ?g)]
     *   )
     * </pre>
     */
    private TupleExpr buildSpecificBranch(final StatementPattern originalSp,
                                          final ConversionRule rule,
                                          final String graphVarName) {
        final Var subject = originalSp.getSubjectVar().clone();
        final Var objVar = originalSp.getObjectVar();
        final String srcName = "_wfconv_" + objVar.getName();
        final Var srcVar = Var.of(srcName, null, true, false);

        final Var sourcePredVar = Var.of(
                "_wfconv_pred_" + Integer.toHexString(rule.sourcePredicate().hashCode()),
                VF.createIRI(rule.sourcePredicate()),
                true, true);

        // Preserve the outer context var if the caller passed a variable
        // graph, so the branch still projects onto ?g via BIND(). We do
        // NOT propagate a concrete-graph context here &mdash; the whole
        // point is to synthesize the row rather than look it up.
        final StatementPattern source = new StatementPattern(subject, sourcePredVar, srcVar);

        final ValueExpr targetExpr = substituteSource(rule.parsedExpression(), srcName);
        final Extension ext = new Extension(source);
        ext.addElement(new ExtensionElem(targetExpr, objVar.getName()));

        if (graphVarName != null) {
            ext.addElement(new ExtensionElem(
                    new ValueConstant(VF.createIRI(rule.graphIri())),
                    graphVarName));
        }
        return ext;
    }

    /**
     * Deep-clone {@code e} and rename any reference to variable
     * {@code ?source} to {@code targetSourceName}. The parsed expression
     * lives in the registry and must not be mutated; we clone before
     * walking.
     */
    static ValueExpr substituteSource(final ValueExpr e, final String targetSourceName) {
        final ValueExpr copy = e.clone();
        copy.visit(new AbstractQueryModelVisitor<RuntimeException>() {
            @Override
            public void meet(final Var var) {
                if (!var.hasValue() && SUBST_VAR.equals(var.getName())) {
                    var.replaceWith(Var.of(targetSourceName, null, false, false));
                }
            }
            @Override
            protected void meetNode(final QueryModelNode n) {
                n.visitChildren(this);
            }
        });
        return copy;
    }

    static TupleExpr unionOf(final List<TupleExpr> branches) {
        if (branches.isEmpty()) throw new IllegalArgumentException("empty union");
        TupleExpr acc = branches.get(0);
        for (int i = 1; i < branches.size(); i++) {
            acc = new Union(acc, branches.get(i));
        }
        return acc;
    }
}

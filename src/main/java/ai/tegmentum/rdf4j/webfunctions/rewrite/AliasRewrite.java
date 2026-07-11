package ai.tegmentum.rdf4j.webfunctions.rewrite;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.Dataset;
import org.eclipse.rdf4j.query.algebra.QueryModelNode;
import org.eclipse.rdf4j.query.algebra.TupleExpr;
import org.eclipse.rdf4j.query.algebra.ValueConstant;
import org.eclipse.rdf4j.query.algebra.Var;
import org.eclipse.rdf4j.query.algebra.evaluation.QueryOptimizer;
import org.eclipse.rdf4j.query.algebra.helpers.AbstractQueryModelVisitor;

/**
 * Alias &rarr; canonical IRI rewrite pass.
 *
 * <p>Walks the parsed {@link TupleExpr}, substituting every concrete IRI
 * that appears in a {@link Var} value or a {@link ValueConstant} with
 * its canonical if the {@link AliasMap} has an entry. The (canonical
 * &rarr; original-alias) pairing is recorded into the supplied
 * {@link AliasRewriteState} so the output path can restore the caller's
 * IRI on returned solutions.
 *
 * <p>Java port of {@code oxigraph-wf/src/alias_rewrite.rs::rewrite_query}.
 *
 * <p>Semantic gap note: RDF4J models constant IRIs as {@link Var}s with
 * {@code hasValue()==true} and {@link ValueConstant}s inside expressions.
 * Both are covered here; property paths in RDF4J are similarly
 * expanded to {@code StatementPattern}s + Union/Join by the parser, so
 * the walker doesn't need a dedicated property-path visitor like the
 * spargebra source.
 */
public final class AliasRewrite implements QueryOptimizer {

    private static final ValueFactory VF = SimpleValueFactory.getInstance();

    private final AliasMap aliases;
    private final AliasRewriteState state;

    public AliasRewrite(final AliasMap aliases) {
        this(aliases, new AliasRewriteState());
    }

    public AliasRewrite(final AliasMap aliases, final AliasRewriteState state) {
        this.aliases = aliases;
        this.state = state;
    }

    /**
     * Rewrite state for the current query. Callers must hold onto this
     * so the result iteration can pipe rows through
     * {@link AliasRewriteState#rewriteBindingSet(BindingSet)}.
     */
    public AliasRewriteState state() {
        return state;
    }

    @Override
    public void optimize(final TupleExpr tupleExpr, final Dataset dataset, final BindingSet bindings) {
        if (aliases.isEmpty()) return;
        tupleExpr.visit(new Walker());
    }

    /** Convenience entry point matching the Rust name. */
    public AliasRewriteState rewriteQuery(final TupleExpr tupleExpr) {
        optimize(tupleExpr, null, null);
        return state;
    }

    private final class Walker extends AbstractQueryModelVisitor<RuntimeException> {

        @Override
        public void meet(final Var var) {
            if (var.hasValue()) {
                final Value v = var.getValue();
                if (v instanceof IRI iri) {
                    final String canonical = aliases.get(iri.stringValue());
                    if (canonical != null) {
                        state.record(canonical, iri.stringValue());
                        // Var#setValue is package-private in some RDF4J
                        // versions &mdash; safer to replace the whole node.
                        final Var replacement = Var.of(
                                var.getName(),
                                VF.createIRI(canonical),
                                var.isAnonymous(),
                                var.isConstant());
                        var.replaceWith(replacement);
                        return;
                    }
                }
            }
            super.meet(var);
        }

        @Override
        public void meet(final ValueConstant vc) {
            final Value v = vc.getValue();
            if (v instanceof IRI iri) {
                final String canonical = aliases.get(iri.stringValue());
                if (canonical != null) {
                    state.record(canonical, iri.stringValue());
                    vc.setValue(VF.createIRI(canonical));
                }
            }
        }

        @Override
        protected void meetNode(final QueryModelNode node) {
            node.visitChildren(this);
        }
    }
}

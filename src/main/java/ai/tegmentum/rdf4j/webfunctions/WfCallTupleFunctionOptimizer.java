package ai.tegmentum.rdf4j.webfunctions;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.Dataset;
import org.eclipse.rdf4j.query.algebra.QueryModelNode;
import org.eclipse.rdf4j.query.algebra.QueryRoot;
import org.eclipse.rdf4j.query.algebra.SingletonSet;
import org.eclipse.rdf4j.query.algebra.StatementPattern;
import org.eclipse.rdf4j.query.algebra.TupleExpr;
import org.eclipse.rdf4j.query.algebra.TupleFunctionCall;
import org.eclipse.rdf4j.query.algebra.ValueConstant;
import org.eclipse.rdf4j.query.algebra.ValueExpr;
import org.eclipse.rdf4j.query.algebra.Var;
import org.eclipse.rdf4j.query.algebra.evaluation.QueryOptimizer;
import org.eclipse.rdf4j.query.algebra.evaluation.function.TupleFunctionRegistry;
import org.eclipse.rdf4j.query.algebra.helpers.AbstractQueryModelVisitor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Recognises the SPIN-style "magic property" pattern
 * <pre>
 *   (a1 a2 …) &lt;fn&gt; (?o1 ?o2 …).
 * </pre>
 * in the parsed SPARQL algebra when {@code &lt;fn&gt;} is a URI registered in
 * {@link TupleFunctionRegistry}, and rewrites the whole subgraph into a single
 * {@link TupleFunctionCall}. Args come from the RDF collection at the subject
 * (or the subject itself if it isn't a collection), result vars come from the
 * RDF collection at the object (or the object variable itself). The
 * {@code rdf:first}/{@code rdf:rest} {@link StatementPattern}s that made up
 * the two lists are replaced with {@link SingletonSet}, the identity element
 * of join, so downstream evaluation reduces to the rewritten
 * {@code TupleFunctionCall}.
 *
 * <p>This closes the gap noted in {@link WfCallTupleFunction} — before this
 * optimizer, actual SPARQL-textual invocation required either a SPINX parser
 * (which RDF4J no longer ships) or programmatic algebra construction.
 */
public final class WfCallTupleFunctionOptimizer implements QueryOptimizer {

    @Override
    public void optimize(final TupleExpr tupleExpr, final Dataset dataset, final BindingSet bindings) {
        final SpIndex index = new SpIndex();
        tupleExpr.visit(index);

        // Snapshot the candidate central SPs before mutating; visitor-driven
        // mutation while iterating would ConcurrentModify.
        final List<StatementPattern> candidates = new ArrayList<>();
        final TupleFunctionRegistry registry = TupleFunctionRegistry.getInstance();
        for (StatementPattern sp : index.all) {
            final Var predVar = sp.getPredicateVar();
            if (predVar == null || !predVar.hasValue()) continue;
            final Value predVal = predVar.getValue();
            if (!(predVal instanceof IRI)) continue;
            if (registry.has(predVal.stringValue())) {
                candidates.add(sp);
            }
        }
        for (StatementPattern sp : candidates) {
            rewrite(sp, index);
        }
    }

    private void rewrite(final StatementPattern central, final SpIndex index) {
        final String uri = central.getPredicateVar().getValue().stringValue();
        final List<StatementPattern> consumed = new ArrayList<>();

        final List<ValueExpr> args = collectArgs(central.getSubjectVar(), index, consumed);
        final List<Var> resultVars = collectResultVars(central.getObjectVar(), index, consumed);

        final TupleFunctionCall call = new TupleFunctionCall();
        call.setURI(uri);
        for (ValueExpr a : args) call.addArg(a);
        for (Var v : resultVars) call.addResultVar(v);

        central.replaceWith(call);
        for (StatementPattern sp : consumed) {
            sp.replaceWith(new SingletonSet());
        }
    }

    /**
     * If {@code head} is bound to a constant OR is a non-collection variable
     * (no {@code rdf:first} edge in the index), treat it as a single arg.
     * Otherwise walk the {@code rdf:first}/{@code rdf:rest} chain.
     */
    private List<ValueExpr> collectArgs(final Var head,
                                        final SpIndex index,
                                        final List<StatementPattern> consumed) {
        final List<ValueExpr> args = new ArrayList<>();
        Var current = head;
        while (true) {
            final StatementPattern firstSp = index.firstBySubject(current.getName());
            final StatementPattern restSp = index.restBySubject(current.getName());
            if (firstSp == null || restSp == null) {
                // Not a list — treat head as a single arg.
                args.add(varToArg(head));
                return args;
            }
            args.add(varToArg(firstSp.getObjectVar()));
            consumed.add(firstSp);
            consumed.add(restSp);
            final Var next = restSp.getObjectVar();
            if (isRdfNil(next)) return args;
            // next is a child of restSp — safe to reference by name, but do
            // NOT hold the node reference across mutation of the tree.
            current = next;
        }
    }

    /**
     * Mirror of {@link #collectArgs} but for the object side. Result slots must
     * be {@link Var} because {@link TupleFunctionCall#addResultVar} demands it —
     * output binding is by name, so a literal on the object side would be
     * meaningless to the caller.
     */
    private List<Var> collectResultVars(final Var head,
                                        final SpIndex index,
                                        final List<StatementPattern> consumed) {
        final List<Var> vars = new ArrayList<>();
        Var current = head;
        while (true) {
            final StatementPattern firstSp = index.firstBySubject(current.getName());
            final StatementPattern restSp = index.restBySubject(current.getName());
            if (firstSp == null || restSp == null) {
                vars.add(head.clone());
                return vars;
            }
            vars.add(firstSp.getObjectVar().clone());
            consumed.add(firstSp);
            consumed.add(restSp);
            final Var next = restSp.getObjectVar();
            if (isRdfNil(next)) return vars;
            current = next;
        }
    }

    private static ValueExpr varToArg(final Var v) {
        return v.hasValue() ? new ValueConstant(v.getValue()) : v.clone();
    }

    private static boolean isRdfNil(final Var v) {
        return v != null && v.hasValue()
                && v.getValue() instanceof IRI iri
                && RDF.NIL.stringValue().equals(iri.stringValue());
    }

    /**
     * Second-pass index of all {@link StatementPattern}s in the tree, keyed by
     * {@code (subjectVarName, predicateIri)} so list-chain following is O(1).
     * We can't rely on parent-child traversal to reach list SPs since they may
     * be scattered across sibling {@link org.eclipse.rdf4j.query.algebra.Join}
     * subtrees.
     */
    private static final class SpIndex extends AbstractQueryModelVisitor<RuntimeException> {
        final List<StatementPattern> all = new ArrayList<>();
        final Map<String, StatementPattern> firstBySubject = new HashMap<>();
        final Map<String, StatementPattern> restBySubject = new HashMap<>();

        @Override
        public void meet(final QueryRoot node) {
            node.visitChildren(this);
        }

        @Override
        public void meet(final StatementPattern sp) {
            all.add(sp);
            final Var pred = sp.getPredicateVar();
            if (pred != null && pred.hasValue() && pred.getValue() instanceof IRI iri) {
                final String subjectName = sp.getSubjectVar().getName();
                if (RDF.FIRST.stringValue().equals(iri.stringValue())) {
                    firstBySubject.put(subjectName, sp);
                } else if (RDF.REST.stringValue().equals(iri.stringValue())) {
                    restBySubject.put(subjectName, sp);
                }
            }
        }

        StatementPattern firstBySubject(final String subjectName) {
            return firstBySubject.get(subjectName);
        }

        StatementPattern restBySubject(final String subjectName) {
            return restBySubject.get(subjectName);
        }

        @Override
        protected void meetNode(final QueryModelNode node) {
            node.visitChildren(this);
        }
    }
}

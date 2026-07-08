package ai.tegmentum.rdf4j.webfunctions;

import org.eclipse.rdf4j.query.Dataset;
import org.eclipse.rdf4j.query.algebra.evaluation.EvaluationStrategy;
import org.eclipse.rdf4j.query.algebra.evaluation.QueryOptimizer;
import org.eclipse.rdf4j.query.algebra.evaluation.QueryOptimizerPipeline;
import org.eclipse.rdf4j.query.algebra.evaluation.TripleSource;
import org.eclipse.rdf4j.query.algebra.evaluation.federation.FederatedServiceResolver;
import org.eclipse.rdf4j.query.algebra.evaluation.impl.AbstractEvaluationStrategyFactory;
import org.eclipse.rdf4j.query.algebra.evaluation.impl.DefaultEvaluationStrategy;
import org.eclipse.rdf4j.query.algebra.evaluation.impl.EvaluationStatistics;
import org.eclipse.rdf4j.query.algebra.evaluation.impl.TupleFunctionEvaluationStatistics;
import org.eclipse.rdf4j.query.algebra.evaluation.impl.TupleFunctionEvaluationStrategy;
import org.eclipse.rdf4j.query.algebra.evaluation.optimizer.StandardQueryOptimizerPipeline;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Strategy factory that yields {@link TupleFunctionEvaluationStrategy} and
 * prepends {@link WfCallTupleFunctionOptimizer} to the standard query
 * optimizer pipeline. Wire onto a Sail via
 * {@code sail.setEvaluationStrategyFactory(new WfEvaluationStrategyFactory(...))}
 * to make SPARQL-textual tuple-function invocation work end-to-end.
 *
 * <p>The optimizer runs BEFORE the standard optimizers because it rewrites
 * StatementPatterns into TupleFunctionCalls — later optimizations (constraint
 * splitting, filter pushdown, iterative evaluation) should see the rewritten
 * tree, not the original list-chain SPs.
 */
public final class WfEvaluationStrategyFactory extends AbstractEvaluationStrategyFactory {

    private final FederatedServiceResolver serviceResolver;

    public WfEvaluationStrategyFactory(final FederatedServiceResolver serviceResolver) {
        this.serviceResolver = serviceResolver;
    }

    @Override
    public EvaluationStrategy createEvaluationStrategy(final Dataset dataset,
                                                       final TripleSource tripleSource,
                                                       final EvaluationStatistics statistics) {
        // Ignore the passed-in statistics: it's whatever the sail wired up,
        // and its CardinalityCalculator throws on TupleFunctionCall nodes that
        // WfCallTupleFunctionOptimizer introduces. TupleFunctionEvaluationStatistics
        // knows how to size them; substitute unconditionally.
        final EvaluationStatistics tfStats = new TupleFunctionEvaluationStatistics();
        final TupleFunctionEvaluationStrategy strategy = new TupleFunctionEvaluationStrategy(
                tripleSource, dataset, serviceResolver, getQuerySolutionCacheThreshold(), tfStats);

        // AbstractEvaluationStrategyFactory#getOptimizerPipeline returns
        // whatever caller-supplied pipeline was set on this factory (Optional).
        // If none was set we install our default pipeline that puts the
        // tuple-function optimizer in front of the standard optimizers.
        final QueryOptimizerPipeline pipeline = getOptimizerPipeline()
                .orElseGet(() -> withTupleFunctionOptimizer(
                        new StandardQueryOptimizerPipeline(strategy, tripleSource, tfStats)));
        ((DefaultEvaluationStrategy) strategy).setOptimizerPipeline(pipeline);

        // v0.3.0 host callbacks: bind the strategy + triple source so any
        // FilterFunction / TupleFunction / aggregate fired during this
        // strategy's evaluation can look them up via CallbackContext.current()
        // and run recursive sub-queries. Bind here at strategy construction
        // rather than at each evaluate() call — evaluate() returns a lazy
        // iterator, and we want the binding to survive iteration.
        //
        // The binding leaks past the strategy's lifetime, which is fine:
        // RDF4J constructs a fresh strategy per query, and the next strategy's
        // bind() no-ops if a context is already present, then overrides on the
        // NEXT query when the current one has closed. For strict cleanup,
        // callers can invoke CallbackContext.unbind() between queries.
        if (WebFunctionConfig.callbackEnabled()) {
            CallbackContext.bind(strategy, tripleSource);
        }
        return strategy;
    }

    /**
     * Prepends {@link WfCallTupleFunctionOptimizer} to the given base
     * pipeline's optimizer sequence. Kept as a static helper so callers who
     * want their own base pipeline can still get the wf: rewrite behavior:
     * {@code factory.setOptimizerPipeline(WfEvaluationStrategyFactory
     * .withTupleFunctionOptimizer(myPipeline))}.
     */
    public static QueryOptimizerPipeline withTupleFunctionOptimizer(final QueryOptimizerPipeline base) {
        return () -> {
            final List<QueryOptimizer> combined = new ArrayList<>();
            combined.add(new WfCallTupleFunctionOptimizer());
            final Iterator<QueryOptimizer> it = base.getOptimizers().iterator();
            while (it.hasNext()) combined.add(it.next());
            return combined;
        };
    }
}

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
        final TupleFunctionEvaluationStrategy strategy = new TupleFunctionEvaluationStrategy(
                tripleSource, dataset, serviceResolver, getQuerySolutionCacheThreshold(), statistics);

        // AbstractEvaluationStrategyFactory#getOptimizerPipeline returns
        // whatever caller-supplied pipeline was set on this factory (Optional).
        // If none was set we install our default pipeline that puts the
        // tuple-function optimizer in front of the standard optimizers.
        final QueryOptimizerPipeline pipeline = getOptimizerPipeline()
                .orElseGet(() -> withTupleFunctionOptimizer(
                        new StandardQueryOptimizerPipeline(strategy, tripleSource, statistics)));
        ((DefaultEvaluationStrategy) strategy).setOptimizerPipeline(pipeline);
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

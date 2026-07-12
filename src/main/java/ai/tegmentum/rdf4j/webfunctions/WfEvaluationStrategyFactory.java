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
    private final org.eclipse.rdf4j.sail.Sail sail;
    private final ai.tegmentum.rdf4j.webfunctions.rewrite.RewritePipeline rewritePipeline;

    public WfEvaluationStrategyFactory(final FederatedServiceResolver serviceResolver) {
        this(serviceResolver, null, null);
    }

    /**
     * Overload for v0.3.1 {@code execute-update}: pass the {@link Sail} the
     * factory is attached to and callback contexts can open write
     * connections against it. When {@code sail} is null (the pre-existing
     * constructor), {@code execute-update} imports return an err with a
     * diagnostic.
     */
    public WfEvaluationStrategyFactory(final FederatedServiceResolver serviceResolver,
                                       final org.eclipse.rdf4j.sail.Sail sail) {
        this(serviceResolver, sail, null);
    }

    /**
     * Overload that also installs the four engine-parity rewrite passes
     * (partial &rarr; conversion &rarr; alias &rarr; shape) at
     * optimizer-pipeline setup time. The pipeline can be inspected
     * after evaluation begins via
     * {@link ai.tegmentum.rdf4j.webfunctions.rewrite.RewritePipeline#aliasState()}
     * so the output-path rewriter can restore caller-facing aliases.
     */
    public WfEvaluationStrategyFactory(final FederatedServiceResolver serviceResolver,
                                       final org.eclipse.rdf4j.sail.Sail sail,
                                       final ai.tegmentum.rdf4j.webfunctions.rewrite.RewritePipeline rewritePipeline) {
        this.serviceResolver = serviceResolver;
        this.sail = sail;
        this.rewritePipeline = rewritePipeline;
    }

    /** The engine-parity rewrite pipeline installed on this factory, or {@code null}. */
    public ai.tegmentum.rdf4j.webfunctions.rewrite.RewritePipeline rewritePipeline() {
        return rewritePipeline;
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
                .orElseGet(() -> {
                    final QueryOptimizerPipeline standard = new StandardQueryOptimizerPipeline(
                            strategy, tripleSource, tfStats);
                    // withWebfunctionRewrites already prepends
                    // WfCallTupleFunctionOptimizer; use it alone when a
                    // RewritePipeline is present so both live in one chain.
                    return rewritePipeline == null
                            ? withTupleFunctionOptimizer(standard)
                            : withWebfunctionRewrites(standard, rewritePipeline);
                });
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
            CallbackContext.bind(strategy, tripleSource, sail);
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

    /**
     * Prepend the four engine-parity rewrite passes (partial &rarr;
     * conversion &rarr; alias &rarr; shape) in front of the standard
     * pipeline, after the tuple-function optimizer. Order matches
     * {@code oxigraph-wf/src/main.rs} lines 630-665.
     *
     * <p>The returned pipeline wires the passes as
     * {@link QueryOptimizer}s so RDF4J invokes them at optimize time,
     * before any evaluation. For alias output rewrite, callers must
     * still wrap the result iterator via
     * {@link ai.tegmentum.rdf4j.webfunctions.rewrite.AliasRewriteState#wrap(org.eclipse.rdf4j.common.iteration.CloseableIteration)}.
     */
    public static QueryOptimizerPipeline withWebfunctionRewrites(
            final QueryOptimizerPipeline base,
            final ai.tegmentum.rdf4j.webfunctions.rewrite.InvokeRegistry invokeRegistry,
            final ai.tegmentum.rdf4j.webfunctions.rewrite.ConversionRegistry conversionRegistry,
            final ai.tegmentum.rdf4j.webfunctions.rewrite.AliasRewrite aliasRewrite,
            final ai.tegmentum.rdf4j.webfunctions.rewrite.ShapeRegistry shapeRegistry,
            final String wfFetchUrl) {
        return () -> {
            final List<QueryOptimizer> combined = new ArrayList<>();
            combined.add(new WfCallTupleFunctionOptimizer());
            if (invokeRegistry != null) {
                combined.add(new ai.tegmentum.rdf4j.webfunctions.rewrite.PartialRewrite(invokeRegistry));
            }
            if (conversionRegistry != null && !conversionRegistry.isEmpty()) {
                combined.add(new ai.tegmentum.rdf4j.webfunctions.rewrite.ConversionRewrite(conversionRegistry));
            }
            if (aliasRewrite != null) {
                combined.add(aliasRewrite);
            }
            if (shapeRegistry != null && !shapeRegistry.isEmpty() && wfFetchUrl != null && !wfFetchUrl.isEmpty()) {
                combined.add(new ai.tegmentum.rdf4j.webfunctions.rewrite.ShapeRewrite(shapeRegistry, wfFetchUrl));
            }
            final Iterator<QueryOptimizer> it = base.getOptimizers().iterator();
            while (it.hasNext()) combined.add(it.next());
            return combined;
        };
    }

    /**
     * Overload of {@link #withWebfunctionRewrites(QueryOptimizerPipeline,
     * ai.tegmentum.rdf4j.webfunctions.rewrite.InvokeRegistry,
     * ai.tegmentum.rdf4j.webfunctions.rewrite.ConversionRegistry,
     * ai.tegmentum.rdf4j.webfunctions.rewrite.AliasRewrite,
     * ai.tegmentum.rdf4j.webfunctions.rewrite.ShapeRegistry, String)}
     * that takes a preconfigured
     * {@link ai.tegmentum.rdf4j.webfunctions.rewrite.RewritePipeline}
     * so the four passes share their engine-parity ordering and
     * empty-registry short-circuiting via a single configuration point.
     *
     * <p>Callers hold onto the {@code pipeline} to reach the alias
     * state (see
     * {@link ai.tegmentum.rdf4j.webfunctions.rewrite.RewritePipeline#aliasState()})
     * after evaluation begins.
     */
    public static QueryOptimizerPipeline withWebfunctionRewrites(
            final QueryOptimizerPipeline base,
            final ai.tegmentum.rdf4j.webfunctions.rewrite.RewritePipeline pipeline) {
        return () -> {
            final List<QueryOptimizer> combined = new ArrayList<>();
            combined.add(new WfCallTupleFunctionOptimizer());
            if (pipeline != null) combined.addAll(pipeline.optimizers());
            final Iterator<QueryOptimizer> it = base.getOptimizers().iterator();
            while (it.hasNext()) combined.add(it.next());
            return combined;
        };
    }
}

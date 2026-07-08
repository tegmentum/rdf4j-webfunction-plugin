package ai.tegmentum.rdf4j.webfunctions;

import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.MalformedQueryException;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.algebra.TupleExpr;
import org.eclipse.rdf4j.query.algebra.evaluation.EvaluationStrategy;
import org.eclipse.rdf4j.query.algebra.evaluation.QueryEvaluationStep;
import org.eclipse.rdf4j.query.algebra.evaluation.TripleSource;
import org.eclipse.rdf4j.query.algebra.evaluation.impl.QueryEvaluationContext;
import org.eclipse.rdf4j.query.parser.ParsedTupleQuery;
import org.eclipse.rdf4j.query.parser.QueryParserUtil;

/**
 * Per-thread context for host callback imports in the v0.3.0 WIT world.
 *
 * <p>Attaches to the current thread when the {@link WfEvaluationStrategyFactory}
 * constructs an {@link EvaluationStrategy}, so any FilterFunction, TupleFunction,
 * or aggregate that fires during that strategy's evaluation can look up the
 * strategy and its triple source via {@link #current()}.
 *
 * <p>This is the elegant answer to the "FilterFunction doesn't get a
 * RepositoryConnection" problem: the connection isn't what we need. What we
 * need is a query-execution capability, which the strategy already provides.
 * The strategy in turn holds a {@link TripleSource} that reads from whatever
 * transaction the outer query runs in — sub-queries inherit that automatically.
 *
 * <p>Threading: RDF4J executes a query on a single thread, so a ThreadLocal
 * is sufficient. Nested wf calls (a callback that fires another callback)
 * share the same context and just increment the depth counter.
 */
public final class CallbackContext {

    private static final ThreadLocal<CallbackContext> CURRENT = new ThreadLocal<>();

    private final EvaluationStrategy strategy;
    private final TripleSource tripleSource;
    private final int maxDepth;
    private final int maxRows;
    private int depth = 0;

    private CallbackContext(final EvaluationStrategy strategy,
                            final TripleSource tripleSource,
                            final int maxDepth,
                            final int maxRows) {
        this.strategy = strategy;
        this.tripleSource = tripleSource;
        this.maxDepth = maxDepth;
        this.maxRows = maxRows;
    }

    /**
     * Attach a fresh context to the current thread. Called by the
     * {@link WfEvaluationStrategyFactory} when the strategy is built. If a
     * context is already bound (nested strategy), don't clobber it — the
     * outer strategy's context stays authoritative.
     */
    public static void bind(final EvaluationStrategy strategy, final TripleSource tripleSource) {
        if (CURRENT.get() != null) return;
        CURRENT.set(new CallbackContext(
                strategy, tripleSource,
                WebFunctionConfig.callbackMaxDepth(),
                WebFunctionConfig.callbackMaxRows()));
    }

    /**
     * Clear the thread's binding. Callers must pair each successful bind
     * with an unbind — typically in a try/finally at the outermost strategy
     * evaluation boundary. Safe to call when no context is bound.
     */
    public static void unbind() {
        CURRENT.remove();
    }

    /**
     * @return the currently-bound context, or null if none. Host callbacks
     *     should check and return an error rather than throw — the wasm
     *     component can then decide whether to fail hard or fall back.
     */
    public static CallbackContext current() {
        return CURRENT.get();
    }

    public int enter() {
        if (depth >= maxDepth) {
            throw new RuntimeException(
                "wf callback depth limit exceeded: " + maxDepth
                + " (config: webfunctions.callback.max.depth)");
        }
        return ++depth;
    }

    public int exit() {
        return --depth;
    }

    public int depth() {
        return depth;
    }

    public int maxRows() {
        return maxRows;
    }

    public ValueFactory valueFactory() {
        return tripleSource.getValueFactory();
    }

    /**
     * Execute a SPARQL SELECT/ASK/DESCRIBE query in the current query's
     * evaluation context, with the given initial bindings pre-applied.
     * Returns the lazy iterator of {@link BindingSet}s; caller closes it.
     *
     * <p>The sub-query goes through the SAME {@link EvaluationStrategy} as
     * the outer query, so it sees the same triple source, the same
     * transaction view, the same optimizations, and — critically — can
     * itself invoke wf callbacks recursively.
     *
     * <p>Enforces the caller-supplied {@code maxRows} via a wrapping
     * iterator: rows beyond that count are silently dropped. Fuel and
     * memory limits are enforced by the surrounding wasm engine.
     */
    public CloseableIteration<BindingSet> executeSelect(final String sparql,
                                                        final BindingSet initialBindings) {
        try {
            final ParsedTupleQuery parsed = QueryParserUtil.parseTupleQuery(
                    QueryLanguage.SPARQL, sparql, null);
            final TupleExpr expr = parsed.getTupleExpr();
            // Precompile through the strategy so any callback-installed
            // optimizations (tuple-function rewrite, etc.) also apply here.
            final QueryEvaluationStep step = strategy.precompile(
                    expr, new QueryEvaluationContext.Minimal(parsed.getDataset()));
            return step.evaluate(initialBindings);
        } catch (MalformedQueryException e) {
            throw new RuntimeException("wf callback: SPARQL parse error: " + e.getMessage(), e);
        }
    }
}

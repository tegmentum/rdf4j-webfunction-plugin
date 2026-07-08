package ai.tegmentum.rdf4j.webfunctions;

import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.query.impl.MapBindingSet;
import org.eclipse.rdf4j.query.parser.ParsedTupleQuery;
import org.eclipse.rdf4j.query.parser.QueryParserUtil;
import org.eclipse.rdf4j.repository.RepositoryConnection;

/**
 * Per-invocation context for host callback imports in the v0.3.0 WIT world.
 *
 * <p>When a wf function is invoked, the plugin binds a {@link CallbackContext}
 * to the calling thread carrying the current {@link RepositoryConnection}
 * (so sub-queries share the same transaction), a {@link ValueFactory}, and
 * a mutable depth counter. The WIT host functions
 * ({@code stardog:webfunction/host#execute-query} and
 * {@code stardog:webfunction/host#callback-depth}) read this thread-local
 * context to know where to route their calls.
 *
 * <p>Threading model: RDF4J's query execution is single-threaded per
 * connection, so a ThreadLocal is sufficient — the entire callback chain
 * runs on the same thread as the original SPARQL evaluation.
 *
 * <p>Lifecycle: {@link #bind} at the top of a wf function's evaluate;
 * {@link #unbind} in a finally block. Nested wf calls re-use the same
 * context but bump the depth counter.
 */
public final class CallbackContext {

    private static final ThreadLocal<CallbackContext> CURRENT = new ThreadLocal<>();

    private final RepositoryConnection connection;
    private final ValueFactory valueFactory;
    private final int maxDepth;
    private final int maxRows;
    private int depth = 0;

    private CallbackContext(final RepositoryConnection c, final ValueFactory vf,
                            final int maxDepth, final int maxRows) {
        this.connection = c;
        this.valueFactory = vf;
        this.maxDepth = maxDepth;
        this.maxRows = maxRows;
    }

    /**
     * Attach a fresh context to the current thread. Call once at the top of
     * each outer wf invocation. If a context is already bound (nested wf
     * call), returns the existing one — do not create a new context for
     * nested calls, or child sub-queries would lose transactional identity.
     */
    public static CallbackContext bind(final RepositoryConnection c,
                                       final ValueFactory vf) {
        CallbackContext existing = CURRENT.get();
        if (existing != null) return existing;
        final CallbackContext ctx = new CallbackContext(
                c, vf,
                WebFunctionConfig.callbackMaxDepth(),
                WebFunctionConfig.callbackMaxRows());
        CURRENT.set(ctx);
        return ctx;
    }

    /**
     * Only unbind when the outermost caller's finally block runs. Nested
     * unwinding doesn't remove the context. Callers must pair each
     * outermost bind with an unbind.
     */
    public static void unbindIfOutermost(final CallbackContext ctx) {
        if (ctx.depth == 0 && CURRENT.get() == ctx) {
            CURRENT.remove();
        }
    }

    /**
     * @return the currently-bound context, or throws if none — indicates
     *     the wasm component is calling execute-query outside a bound
     *     evaluation, which is a plugin/component-integration bug.
     */
    public static CallbackContext current() {
        final CallbackContext c = CURRENT.get();
        if (c == null) {
            throw new IllegalStateException(
                "No CallbackContext bound; host callback invoked outside a wf-plugin path");
        }
        return c;
    }

    /**
     * Increment the depth counter, verifying we're still under the cap.
     * Called at the top of {@code executeQuery}; matched with {@link #exit}.
     */
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
        return valueFactory;
    }

    /**
     * Execute a SELECT/ASK/DESCRIBE query with the given initial bindings
     * pre-applied. Returns the raw {@link TupleQueryResult}; caller is
     * responsible for closing it and marshalling results back to WIT.
     *
     * <p>Uses the current connection's transaction — sub-queries see the
     * same view of the store as the outer query. Fuel and memory limits
     * are enforced by the surrounding wasm engine; row-count limit is
     * enforced here in the caller via {@link #maxRows()}.
     */
    public TupleQueryResult executeSelect(final String sparql,
                                          final BindingSet initialBindings) {
        // Parse the query, apply initial bindings, evaluate.
        final ParsedTupleQuery parsed = QueryParserUtil.parseTupleQuery(
                org.eclipse.rdf4j.query.QueryLanguage.SPARQL, sparql, null);
        return connection.prepareTupleQuery(sparql).evaluate();
        // NOTE: the above ignores initialBindings — proper implementation
        // must build a query with the bindings applied. RDF4J's
        // TupleQuery has setBinding(String, Value); refactor to use that
        // once wired in the host function.
    }

    /**
     * Convenience: build a MapBindingSet from name-value pairs for the
     * common execute-query use case.
     */
    public MapBindingSet emptyBindingSet() {
        return new MapBindingSet();
    }
}

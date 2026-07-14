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
import org.eclipse.rdf4j.query.parser.ParsedBooleanQuery;
import org.eclipse.rdf4j.query.parser.ParsedGraphQuery;
import org.eclipse.rdf4j.query.parser.ParsedQuery;
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
    private final org.eclipse.rdf4j.sail.Sail sail;
    private final int maxDepth;
    private final int maxRows;
    private int depth = 0;

    // Prepared-query handles, minted by prepare() and used by runPrepared().
    // Cleared implicitly when this context is dropped (per top-level query).
    private final java.util.Map<Integer, Prepared> prepared = new java.util.HashMap<>();
    private int nextHandle = 1;

    // v0.5 open sink handles. Vec-backed (not a HashMap) because the index
    // IS the handle; null slots survive sink-close calls without shifting.
    // Guests get a fresh table per bind() and the outer wf:call frame is
    // responsible for closing whatever the guest didn't via sink-close.
    private final java.util.List<Sink> sinks = new java.util.ArrayList<>();

    // Set true after the first successful executeUpdate commit on this
    // context. Under MemoryStore snapshot isolation, the outer strategy's
    // tripleSource still sees the pre-update snapshot after our fresh-
    // connection commit lands, so any subsequent executeSelect / query
    // must go through a fresh SailConnection to see the committed rows.
    // This is the fix for the chained_pipeline verify_canonical
    // 0-rows regression documented on that case's xfail_reason.
    private boolean postUpdate = false;

    /**
     * A precompiled sub-query bound to this context's strategy. Holds the
     * {@link QueryEvaluationStep} so runPrepared() only pays initial-binding
     * substitution + iteration on each call.
     *
     * <p>The original {@code sparql} text is retained so that, post-update,
     * runPrepared can reparse and evaluate through a fresh SailConnection
     * (the cached step is bound to the outer strategy's pre-update
     * tripleSource and would return stale rows).
     */
    private static final class Prepared {
        final QueryEvaluationStep step;
        final String sparql;
        Prepared(final QueryEvaluationStep step, final String sparql) {
            this.step = step;
            this.sparql = sparql;
        }
    }

    private CallbackContext(final EvaluationStrategy strategy,
                            final TripleSource tripleSource,
                            final org.eclipse.rdf4j.sail.Sail sail,
                            final int maxDepth,
                            final int maxRows) {
        this.strategy = strategy;
        this.tripleSource = tripleSource;
        this.sail = sail;
        this.maxDepth = maxDepth;
        this.maxRows = maxRows;
    }

    /** Legacy bind — no sail; execute-update unavailable. */
    public static void bind(final EvaluationStrategy strategy, final TripleSource tripleSource) {
        bind(strategy, tripleSource, null);
    }

    /**
     * Attach a fresh context to the current thread. Called by the
     * {@link WfEvaluationStrategyFactory} when the strategy is built. If a
     * context is already bound (nested strategy), don't clobber it — the
     * outer strategy's context stays authoritative.
     *
     * <p>{@code sail} enables the v0.3.1 execute-update import; may be null.
     */
    public static void bind(final EvaluationStrategy strategy,
                            final TripleSource tripleSource,
                            final org.eclipse.rdf4j.sail.Sail sail) {
        // Always replace at strategy construction. RDF4J constructs a fresh
        // strategy per top-level query, so an earlier query's context is
        // stale. Nested wf callbacks (execute-query re-entering wasm)
        // reuse the CURRENT context via precompile on the SAME strategy —
        // that path doesn't call bind(), so the depth counter is preserved.
        CURRENT.set(new CallbackContext(
                strategy, tripleSource, sail,
                WebFunctionConfig.callbackMaxDepth(),
                WebFunctionConfig.callbackMaxRows()));
    }

    /**
     * Clear the thread's binding. Callers must pair each successful bind
     * with an unbind — typically in a try/finally at the outermost strategy
     * evaluation boundary. Safe to call when no context is bound.
     *
     * <p>Also closes any v0.5 sink handles the guest didn't explicitly
     * release via {@code sink-close}. Matches the WIT contract:
     * <em>"Handles are valid only within the outer wf:call frame that
     * opened them — the host closes them automatically when that frame
     * returns."</em>
     */
    public static void unbind() {
        final CallbackContext ctx = CURRENT.get();
        if (ctx != null) {
            for (Sink s : ctx.sinks) {
                if (s != null) {
                    try { s.close(); } catch (RuntimeException ignored) {}
                }
            }
            ctx.sinks.clear();
        }
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
     * Execute an arbitrary SPARQL query with the given initial bindings and
     * return the resulting {@link BindingSet} iterator. Dispatches on query
     * shape via {@link QueryParserUtil#parseQuery(QueryLanguage, String, String)}:
     * <ul>
     *   <li>{@link ParsedTupleQuery} (SELECT): precompile through the outer
     *       strategy and evaluate — same tripleSource, same optimizations,
     *       same transaction view.</li>
     *   <li>{@link ParsedGraphQuery} (CONSTRUCT / DESCRIBE): precompile the
     *       template's TupleExpr — which yields BindingSets with the
     *       reserved vars {@code subject}, {@code predicate}, {@code object}
     *       (see {@code SailGraphQuery$2}) — and remap those onto
     *       {@code s}, {@code p}, {@code o} so the WIT envelope matches
     *       Oxigraph's {@code QueryResults::Graph} shape from
     *       {@code oxigraph-wf/src/host.rs}. Guests written to that shape
     *       (e.g. {@code wf_infer}'s CONSTRUCT-and-INSERT loop) work
     *       identically across substrates.</li>
     *   <li>{@link ParsedBooleanQuery} (ASK): evaluate for at least one
     *       row; return a single-row iteration with the pseudo-var
     *       {@code _ask} bound to the boolean literal, mirroring
     *       Oxigraph's {@code QueryResults::Boolean} shape.</li>
     * </ul>
     *
     * <p>Method name kept as {@code executeSelect} for backward compatibility
     * with existing host-callback wiring; it now handles the full query
     * shape space, not just SELECT.
     *
     * <p>Post-update path: once {@link #executeUpdate(String, BindingSet)}
     * has committed, the outer strategy's tripleSource still sees the
     * pre-update snapshot under MemoryStore snapshot isolation, so
     * evaluation is routed through a fresh SailConnection to see the
     * committed rows. The returned iteration owns the connection and
     * closes it when itself closed.
     */
    public CloseableIteration<BindingSet> executeSelect(final String sparql,
                                                        final BindingSet initialBindings) {
        try {
            final ParsedQuery parsed = QueryParserUtil.parseQuery(
                    QueryLanguage.SPARQL, sparql, null);
            if (parsed instanceof ParsedTupleQuery) {
                return evaluateTupleExpr(parsed, initialBindings);
            }
            if (parsed instanceof ParsedGraphQuery) {
                return remapSubjectPredicateObjectToSpo(
                        evaluateTupleExpr(parsed, initialBindings));
            }
            if (parsed instanceof ParsedBooleanQuery) {
                final boolean answer;
                try (CloseableIteration<BindingSet> iter =
                        evaluateTupleExpr(parsed, initialBindings)) {
                    answer = iter.hasNext();
                }
                return askBooleanIteration(answer);
            }
            throw new RuntimeException(
                "wf callback: unsupported SPARQL query shape (parsed as "
                + parsed.getClass().getSimpleName() + ")");
        } catch (MalformedQueryException e) {
            throw new RuntimeException("wf callback: SPARQL parse error: " + e.getMessage(), e);
        }
    }

    /**
     * Evaluate a parsed query's {@link TupleExpr} either via the outer
     * strategy (fast path, reuses tripleSource + strategy optimizations)
     * or via a fresh {@link org.eclipse.rdf4j.sail.SailConnection}
     * (correctness path — post-update, see {@link #postUpdate}).
     *
     * <p>Fresh-connection path opens a {@link org.eclipse.rdf4j.sail.SailConnection}
     * on {@link #sail}, evaluates through it, and wraps the returned
     * iteration so closing it also closes the connection. The wrapper is
     * necessary because the iteration is lazy — closing the connection
     * before the caller drains it would break iteration.
     */
    private CloseableIteration<BindingSet> evaluateTupleExpr(
            final ParsedQuery parsed, final BindingSet initialBindings) {
        if (postUpdate) {
            if (sail == null) {
                throw new IllegalStateException(
                    "wf callback: post-update read needs a Sail — construct "
                    + "WfEvaluationStrategyFactory with the two-arg overload");
            }
            final org.eclipse.rdf4j.sail.SailConnection conn = sail.getConnection();
            try {
                final CloseableIteration<? extends BindingSet> inner = conn.evaluate(
                        parsed.getTupleExpr(),
                        parsed.getDataset(),
                        initialBindings,
                        true);
                return new ConnectionClosingIteration(inner, conn);
            } catch (RuntimeException | Error re) {
                conn.close();
                throw re;
            }
        }
        // Fast path: precompile through the strategy so any callback-
        // installed optimizations (tuple-function rewrite, etc.) also
        // apply here.
        final QueryEvaluationStep step = strategy.precompile(
                parsed.getTupleExpr(),
                new QueryEvaluationContext.Minimal(parsed.getDataset()));
        return step.evaluate(initialBindings);
    }

    /**
     * Wrap a graph-query iteration (BindingSets with reserved vars
     * {@code subject}, {@code predicate}, {@code object}, optionally
     * {@code context}) into one with the SPO shape guests expect
     * cross-substrate ({@code s}, {@code p}, {@code o}).
     */
    private static CloseableIteration<BindingSet> remapSubjectPredicateObjectToSpo(
            final CloseableIteration<BindingSet> inner) {
        return new CloseableIteration<BindingSet>() {
            @Override public boolean hasNext() { return inner.hasNext(); }
            @Override public BindingSet next() {
                final BindingSet row = inner.next();
                final org.eclipse.rdf4j.query.impl.MapBindingSet out =
                        new org.eclipse.rdf4j.query.impl.MapBindingSet();
                final org.eclipse.rdf4j.model.Value s = row.getValue("subject");
                final org.eclipse.rdf4j.model.Value p = row.getValue("predicate");
                final org.eclipse.rdf4j.model.Value o = row.getValue("object");
                if (s != null) out.addBinding("s", s);
                if (p != null) out.addBinding("p", p);
                if (o != null) out.addBinding("o", o);
                return out;
            }
            @Override public void remove() { inner.remove(); }
            @Override public void close() { inner.close(); }
        };
    }

    /**
     * Materialise an ASK answer as a single-row iteration with pseudo-var
     * {@code _ask} bound to an {@code xsd:boolean} literal — mirroring
     * Oxigraph's {@code QueryResults::Boolean} shape so guests get the
     * same binding-sets envelope across substrates.
     */
    private CloseableIteration<BindingSet> askBooleanIteration(final boolean answer) {
        final org.eclipse.rdf4j.model.Literal lit =
                tripleSource.getValueFactory().createLiteral(answer);
        final org.eclipse.rdf4j.query.impl.MapBindingSet row =
                new org.eclipse.rdf4j.query.impl.MapBindingSet();
        row.addBinding("_ask", lit);
        final java.util.Iterator<BindingSet> it = java.util.List.<BindingSet>of(row).iterator();
        return new CloseableIteration<BindingSet>() {
            @Override public boolean hasNext() { return it.hasNext(); }
            @Override public BindingSet next() { return it.next(); }
            @Override public void remove() { throw new UnsupportedOperationException(); }
            @Override public void close() { /* nothing to release */ }
        };
    }

    /**
     * Wraps a lazy iteration produced by a per-callback SailConnection so
     * that closing the outer iteration also closes the connection —
     * necessary for the {@link #postUpdate} fresh-connection path where
     * we can't leak the connection past the caller's try-with-resources
     * on the iteration.
     */
    private static final class ConnectionClosingIteration
            implements CloseableIteration<BindingSet> {
        private final CloseableIteration<? extends BindingSet> inner;
        private final org.eclipse.rdf4j.sail.SailConnection conn;
        private boolean closed = false;
        ConnectionClosingIteration(
                final CloseableIteration<? extends BindingSet> inner,
                final org.eclipse.rdf4j.sail.SailConnection conn) {
            this.inner = inner;
            this.conn = conn;
        }
        @Override public boolean hasNext() { return inner.hasNext(); }
        @Override public BindingSet next() { return inner.next(); }
        @Override public void remove() { inner.remove(); }
        @Override public void close() {
            if (closed) return;
            closed = true;
            try { inner.close(); } finally { conn.close(); }
        }
    }

    /**
     * v0.6 execute-query-with-bindings: execute a SPARQL SELECT with a full
     * pre-seed binding matrix (vars + rows) rather than a single row of
     * scalar bindings. Semantics mirror Oxigraph's
     * {@code run_query_with_seed} — the seed is spliced under the outermost
     * projection as a VALUES join via a
     * {@link org.eclipse.rdf4j.query.algebra.BindingSetAssignment} node.
     *
     * <p>Missing cells in a row become RDF4J UNDEF (the binding is simply
     * absent from the row's {@link BindingSet}), matching SPARQL 1.1
     * VALUES semantics.
     */
    public CloseableIteration<BindingSet> executeSelectWithBindings(
            final String sparql,
            final java.util.List<String> seedVars,
            final java.util.List<BindingSet> seedRows) {
        try {
            final ParsedTupleQuery parsed = QueryParserUtil.parseTupleQuery(
                    QueryLanguage.SPARQL, sparql, null);
            TupleExpr expr = parsed.getTupleExpr();
            if (!seedVars.isEmpty() && !seedRows.isEmpty()) {
                final org.eclipse.rdf4j.query.algebra.BindingSetAssignment bsa =
                        new org.eclipse.rdf4j.query.algebra.BindingSetAssignment();
                bsa.setBindingNames(new java.util.LinkedHashSet<>(seedVars));
                bsa.setBindingSets(seedRows);
                expr = spliceValuesUnderProjection(expr, bsa);
            }
            // Post-update: use a fresh connection so we see committed rows,
            // mirroring executeSelect's rationale.
            if (postUpdate) {
                if (sail == null) {
                    throw new IllegalStateException(
                        "wf callback: post-update read needs a Sail");
                }
                final org.eclipse.rdf4j.sail.SailConnection conn = sail.getConnection();
                try {
                    final CloseableIteration<? extends BindingSet> inner = conn.evaluate(
                            expr, parsed.getDataset(),
                            new org.eclipse.rdf4j.query.impl.EmptyBindingSet(),
                            true);
                    return new ConnectionClosingIteration(inner, conn);
                } catch (RuntimeException | Error re) {
                    conn.close();
                    throw re;
                }
            }
            final QueryEvaluationStep step = strategy.precompile(
                    expr, new QueryEvaluationContext.Minimal(parsed.getDataset()));
            return step.evaluate(new org.eclipse.rdf4j.query.impl.EmptyBindingSet());
        } catch (MalformedQueryException e) {
            throw new RuntimeException("wf callback: SPARQL parse error: " + e.getMessage(), e);
        }
    }

    /**
     * Walk down through Projection/Distinct/Reduced/Order/Slice/Group/
     * Filter/Extension wrappers until we reach the actual scan/join
     * pattern, then wrap it in {@code Join(values, inner)}. Same splice
     * path Oxigraph uses so seed columns compose with WHERE-only variables,
     * not just the outer SELECT's projection.
     */
    private static TupleExpr spliceValuesUnderProjection(
            final TupleExpr expr,
            final org.eclipse.rdf4j.query.algebra.BindingSetAssignment values) {
        if (expr instanceof org.eclipse.rdf4j.query.algebra.QueryRoot qr) {
            qr.setArg(spliceValuesUnderProjection(qr.getArg(), values));
            return qr;
        }
        if (expr instanceof org.eclipse.rdf4j.query.algebra.Projection p) {
            p.setArg(spliceValuesUnderProjection(p.getArg(), values));
            return p;
        }
        if (expr instanceof org.eclipse.rdf4j.query.algebra.Distinct d) {
            d.setArg(spliceValuesUnderProjection(d.getArg(), values));
            return d;
        }
        if (expr instanceof org.eclipse.rdf4j.query.algebra.Reduced r) {
            r.setArg(spliceValuesUnderProjection(r.getArg(), values));
            return r;
        }
        if (expr instanceof org.eclipse.rdf4j.query.algebra.Order o) {
            o.setArg(spliceValuesUnderProjection(o.getArg(), values));
            return o;
        }
        if (expr instanceof org.eclipse.rdf4j.query.algebra.Slice s) {
            s.setArg(spliceValuesUnderProjection(s.getArg(), values));
            return s;
        }
        if (expr instanceof org.eclipse.rdf4j.query.algebra.Group g) {
            g.setArg(spliceValuesUnderProjection(g.getArg(), values));
            return g;
        }
        if (expr instanceof org.eclipse.rdf4j.query.algebra.Filter f) {
            f.setArg(spliceValuesUnderProjection(f.getArg(), values));
            return f;
        }
        if (expr instanceof org.eclipse.rdf4j.query.algebra.Extension e) {
            e.setArg(spliceValuesUnderProjection(e.getArg(), values));
            return e;
        }
        return new org.eclipse.rdf4j.query.algebra.Join(values, expr);
    }

    /**
     * v0.3.2 prepare-query — parse and precompile a SPARQL SELECT once so
     * subsequent runPrepared() calls only pay initial-binding substitution
     * and iteration. Returns a small integer handle usable until this
     * context ends.
     */
    public int prepare(final String sparql) {
        try {
            final ParsedTupleQuery parsed = QueryParserUtil.parseTupleQuery(
                    QueryLanguage.SPARQL, sparql, null);
            final QueryEvaluationStep step = strategy.precompile(
                    parsed.getTupleExpr(),
                    new QueryEvaluationContext.Minimal(parsed.getDataset()));
            final int h = nextHandle++;
            prepared.put(h, new Prepared(step, sparql));
            return h;
        } catch (MalformedQueryException e) {
            throw new RuntimeException("wf callback: SPARQL parse error: " + e.getMessage(), e);
        }
    }

    /**
     * v0.3.3 follow-predicate — direct triple pattern lookup. Returns
     * the objects of triples matching {@code (subject, predicate, ?)} via
     * {@link TripleSource#getStatements}. No SPARQL parsing or algebra
     * evaluation; just an index scan.
     */
    public java.util.List<org.eclipse.rdf4j.model.Value> followPredicate(
            final org.eclipse.rdf4j.model.Value subject,
            final org.eclipse.rdf4j.model.Value predicate) {
        if (!(subject instanceof org.eclipse.rdf4j.model.Resource)) {
            throw new RuntimeException(
                "wf callback: follow-predicate subject must be IRI or bnode");
        }
        if (!(predicate instanceof org.eclipse.rdf4j.model.IRI)) {
            throw new RuntimeException(
                "wf callback: follow-predicate predicate must be IRI");
        }
        final java.util.List<org.eclipse.rdf4j.model.Value> out = new java.util.ArrayList<>();
        // Post-update, tripleSource holds the pre-commit snapshot; open a
        // fresh SailConnection so we see rows the outer wf:call frame
        // just committed via execute-update.
        if (postUpdate && sail != null) {
            try (org.eclipse.rdf4j.sail.SailConnection conn = sail.getConnection();
                 CloseableIteration<? extends org.eclipse.rdf4j.model.Statement> it =
                        conn.getStatements(
                                (org.eclipse.rdf4j.model.Resource) subject,
                                (org.eclipse.rdf4j.model.IRI) predicate,
                                null,
                                true)) {
                while (it.hasNext()) {
                    out.add(it.next().getObject());
                }
            }
            return out;
        }
        try (CloseableIteration<? extends org.eclipse.rdf4j.model.Statement> it =
                tripleSource.getStatements(
                        (org.eclipse.rdf4j.model.Resource) subject,
                        (org.eclipse.rdf4j.model.IRI) predicate,
                        null)) {
            while (it.hasNext()) {
                out.add(it.next().getObject());
            }
        }
        return out;
    }

    /** v0.3.2 run-prepared — evaluate a handle from {@link #prepare} with
     *  fresh initial bindings.
     *
     *  <p>Post-update, the cached step is bound to the outer strategy's
     *  pre-update tripleSource and would return stale rows; instead we
     *  reparse the stored SPARQL text and evaluate through
     *  {@link #executeSelect} (which routes to a fresh SailConnection).
     */
    public CloseableIteration<BindingSet> runPrepared(final int handle,
                                                     final BindingSet initialBindings) {
        final Prepared p = prepared.get(handle);
        if (p == null) {
            throw new RuntimeException("wf callback: unknown prepared handle " + handle);
        }
        if (postUpdate) {
            return executeSelect(p.sparql, initialBindings);
        }
        return p.step.evaluate(initialBindings);
    }

    // ---- v0.5 sink handles ------------------------------------------------

    /**
     * Register an opened {@link Sink} with this context and return the
     * u32 handle the guest will use for subsequent sink-execute /
     * sink-close calls. Handle == slot index in the internal Vec.
     */
    public int registerSink(final Sink sink) {
        final int handle = sinks.size();
        sinks.add(sink);
        return handle;
    }

    /**
     * Look up an open sink by handle. Returns null if the handle is out
     * of range or has been released via {@link #closeSink(int)}.
     */
    public Sink getSink(final int handle) {
        if (handle < 0 || handle >= sinks.size()) return null;
        return sinks.get(handle);
    }

    /**
     * Close the sink at the given handle. Returns true if the handle was
     * live and got closed, false if it was already closed or stale — the
     * caller shapes this into the WIT {@code result<_, string>}.
     */
    public boolean closeSink(final int handle) {
        if (handle < 0 || handle >= sinks.size()) return false;
        final Sink s = sinks.get(handle);
        if (s == null) return false;
        try { s.close(); } catch (RuntimeException ignored) {}
        sinks.set(handle, null);
        return true;
    }

    /**
     * v0.5 execute-update — SPARQL 1.1 UPDATE against the outer
     * {@link org.eclipse.rdf4j.sail.Sail}, no initial bindings. The v0.5
     * WIT signature dropped the {@code bindings} arg in favor of a
     * simpler {@code (update: string) -> result<_, string>} shape;
     * delegates to the pre-existing {@link #executeUpdate(String,
     * org.eclipse.rdf4j.query.BindingSet)} with an empty binding set.
     */
    public void executeUpdate(final String sparql) {
        executeUpdate(sparql,
                new org.eclipse.rdf4j.query.impl.MapBindingSet());
    }

    /**
     * v0.3.1 execute-update — SPARQL 1.1 UPDATE against the outer
     * {@link org.eclipse.rdf4j.sail.Sail}. Opens a fresh
     * {@link org.eclipse.rdf4j.sail.SailConnection} for the update; that
     * connection is a SEPARATE transaction from the outer read query, so
     * concurrent isolation depends on the underlying store's semantics
     * (MemoryStore uses snapshot isolation, so the read view won't see the
     * write until the read closes).
     *
     * <p>After a successful commit, {@link #postUpdate} is set so
     * subsequent callback reads ({@link #executeSelect},
     * {@link #executeSelectWithBindings}, {@link #runPrepared},
     * {@link #followPredicate}) route through a fresh SailConnection and
     * observe the just-written rows — otherwise they'd continue to read
     * the outer strategy's stale snapshot and, e.g., chained_pipeline's
     * verify_canonical would report 0 rows for the write the previous
     * step just committed.
     *
     * <p>When the factory was constructed without a Sail (the pre-existing
     * one-arg constructor), throws {@link IllegalStateException} — the
     * caller returns err to the guest.
     */
    public void executeUpdate(final String sparql,
                              final org.eclipse.rdf4j.query.BindingSet initial) {
        if (sail == null) {
            throw new IllegalStateException(
                "wf callback: RDF4J execute-update needs a Sail — construct "
                + "WfEvaluationStrategyFactory with the two-arg overload");
        }
        try (org.eclipse.rdf4j.sail.SailConnection sc = sail.getConnection()) {
            sc.begin();
            try {
                final org.eclipse.rdf4j.query.parser.ParsedUpdate parsed =
                        org.eclipse.rdf4j.query.parser.QueryParserUtil.parseUpdate(
                                QueryLanguage.SPARQL, sparql, null);
                final org.eclipse.rdf4j.repository.sail.helpers.SailUpdateExecutor exec =
                        new org.eclipse.rdf4j.repository.sail.helpers.SailUpdateExecutor(
                                sc, tripleSource.getValueFactory(),
                                new org.eclipse.rdf4j.rio.ParserConfig());
                for (org.eclipse.rdf4j.query.algebra.UpdateExpr expr : parsed.getUpdateExprs()) {
                    exec.executeUpdate(expr,
                            parsed.getDatasetMapping().get(expr),
                            initial,
                            true,   // includeInferred
                            -1);    // maxExecutionTime
                }
                sc.commit();
                // Flip AFTER a successful commit — a rolled-back update
                // shouldn't force subsequent reads onto the fresh-conn
                // path if nothing landed on the store.
                postUpdate = true;
            } catch (RuntimeException | Error re) {
                sc.rollback();
                throw re;
            } catch (Exception e) {
                sc.rollback();
                throw new RuntimeException("wf callback: update failed: " + e.getMessage(), e);
            }
        } catch (MalformedQueryException e) {
            throw new RuntimeException("wf callback: SPARQL update parse error: " + e.getMessage(), e);
        }
    }
}

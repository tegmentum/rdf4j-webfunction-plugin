package ai.tegmentum.rdf4j.webfunctions;

import ai.tegmentum.wasmtime4j.component.ComponentVal;
import ai.tegmentum.webassembly4j.api.WitHostFunction;

import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.model.BNode;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.impl.MapBindingSet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Host callbacks satisfying the v0.3.0 WIT world's {@code host} import interface:
 * {@code stardog:webfunction/host@0.3.0#execute-query} and
 * {@code stardog:webfunction/host@0.3.0#callback-depth}.
 *
 * <p>The linker-boundary currency is {@link ComponentVal}, not
 * {@code WitValue} — the wasmtime4j provider's
 * {@code WasmtimeComponentAdapter} unpacks each argument into a
 * {@link ComponentVal} and expects our returns to be {@link ComponentVal}
 * instances. Every helper below constructs values via
 * {@code ComponentVal.record(...)}, {@code ComponentVal.string(...)},
 * {@code ComponentVal.list(...)}, {@code ComponentVal.variant(...)},
 * {@code ComponentVal.ok/err(...)}, etc.
 *
 * <p>Threading: both callbacks read from {@link CallbackContext#current()},
 * which the {@link WfEvaluationStrategyFactory} binds at strategy
 * construction so this is available for any FilterFunction / TupleFunction
 * / aggregate fired during that strategy's evaluation.
 */
public final class HostCallbacks {

    private HostCallbacks() {}

    /**
     * v0.6 {@code execute-query-with-bindings: func(query: string,
     *  seed: binding-sets, max-rows: option<u32>) -> result<binding-sets, string>}.
     *
     * <p>Accepts a full {@code binding-sets} record (vars + rows) and
     * splices it under the query's outermost projection as a VALUES join
     * (a {@link org.eclipse.rdf4j.query.algebra.BindingSetAssignment} node).
     * Mirrors Oxigraph's {@code run_query_with_seed}: gives wf_pipeline v3's
     * typed binding-set propagation a substrate-native path that doesn't
     * route through VALUES-text interpolation.
     *
     * <p>Missing cells become RDF4J UNDEF (the binding simply doesn't
     * appear in the row's {@link BindingSet}).
     */
    public static WitHostFunction executeQueryWithBindings() {
        return args -> {
            if (!WebFunctionConfig.callbackEnabled()) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "wf callback disabled by webfunctions.callback.enabled=false")) };
            }
            final CallbackContext ctx = CallbackContext.current();
            if (ctx == null) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "wf callback: no strategy bound — install WfEvaluationStrategyFactory "
                    + "on the sail to enable callbacks")) };
            }
            try {
                final String sparql = ((ComponentVal) args[0]).asString();
                final ComponentVal seedVal = (ComponentVal) args[1];
                final Map<String, ComponentVal> seedFields = seedVal.asRecord();
                final ComponentVal varsField = seedFields.get("vars");
                final ComponentVal rowsField = seedFields.get("rows");
                if (varsField == null || rowsField == null) {
                    return new Object[] { ComponentVal.err(ComponentVal.string(
                        "wf execute-query-with-bindings: seed missing vars/rows field")) };
                }
                final List<String> seedVars = new ArrayList<>();
                for (ComponentVal v : varsField.asList()) {
                    seedVars.add(v.asString());
                }
                final ValueFactory vf = ctx.valueFactory();
                final List<BindingSet> seedRows = new ArrayList<>();
                for (ComponentVal rowVal : rowsField.asList()) {
                    final org.eclipse.rdf4j.query.impl.MapBindingSet mbs =
                            new org.eclipse.rdf4j.query.impl.MapBindingSet();
                    for (ComponentVal bindingVal : rowVal.asList()) {
                        final Map<String, ComponentVal> bf = bindingVal.asRecord();
                        final String name = bf.get("name").asString();
                        mbs.addBinding(name, decodeValue(bf.get("value"), vf));
                    }
                    seedRows.add(mbs);
                }
                final int rowCap = decodeOptionalU32((ComponentVal) args[2]).orElseGet(ctx::maxRows);

                ctx.enter();
                try (CloseableIteration<BindingSet> iter =
                        ctx.executeSelectWithBindings(sparql, seedVars, seedRows)) {
                    return new Object[] { ComponentVal.ok(encodeBindingSets(iter, rowCap)) };
                } finally {
                    ctx.exit();
                }
            } catch (RuntimeException e) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    e.getMessage() == null ? e.toString() : e.getMessage())) };
            }
        };
    }

    /** {@code execute-query: func(sparql: string, bindings: list<binding>,
     *  max-rows: option<u32>) -> result<binding-sets, string>}. */
    public static WitHostFunction executeQuery() {
        return args -> {
            if (!WebFunctionConfig.callbackEnabled()) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "wf callback disabled by webfunctions.callback.enabled=false")) };
            }
            final CallbackContext ctx = CallbackContext.current();
            if (ctx == null) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "wf callback: no strategy bound — install WfEvaluationStrategyFactory "
                    + "on the sail to enable callbacks")) };
            }
            try {
                final String sparql = ((ComponentVal) args[0]).asString();
                final BindingSet initial = decodeBindings((ComponentVal) args[1], ctx);
                final int rowCap = decodeOptionalU32((ComponentVal) args[2]).orElseGet(ctx::maxRows);

                ctx.enter();
                try (CloseableIteration<BindingSet> iter = ctx.executeSelect(sparql, initial)) {
                    return new Object[] { ComponentVal.ok(encodeBindingSets(iter, rowCap)) };
                } finally {
                    ctx.exit();
                }
            } catch (RuntimeException e) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    e.getMessage() == null ? e.toString() : e.getMessage())) };
            }
        };
    }

    /** {@code callback-depth: func() -> u32}. */
    public static WitHostFunction callbackDepth() {
        return args -> {
            final CallbackContext ctx = CallbackContext.current();
            return new Object[] { ComponentVal.u32(ctx == null ? 0L : (long) ctx.depth()) };
        };
    }

    /**
     * v0.4 {@code invoke-wasm: func(url: string, args: list<value>)
     * -> result<binding-sets, string>}.
     *
     * <p>Recursively invokes another wasm component identified by
     * {@code url}. The nested guest runs in a fresh
     * {@link Rdf4jWasmInstance}; component instantiation is cached per
     * URL by wasmtime4j, so back-to-back invocations reuse compiled
     * components.
     *
     * <p>Uses {@link CallbackContext#valueFactory()} to construct RDF4J
     * Values from the decoded WIT payload — the callback's outer
     * TripleSource is what makes the nested guest's return Values
     * shareable with the calling strategy.
     */
    public static WitHostFunction invokeWasm() {
        return args -> {
            if (!WebFunctionConfig.callbackEnabled()) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "wf callback disabled by webfunctions.callback.enabled=false")) };
            }
            final CallbackContext ctx = CallbackContext.current();
            if (ctx == null) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "wf callback: invoke-wasm has no context bound — nested guest "
                    + "was reached from a code path that didn't bind CallbackContext")) };
            }
            try {
                final String url = ((ComponentVal) args[0]).asString();
                final ComponentVal argsList = (ComponentVal) args[1];
                final List<ComponentVal> inner = argsList.asList();
                final ValueFactory vf = ctx.valueFactory();
                final Value[] callArgs = new Value[inner.size()];
                for (int i = 0; i < inner.size(); i++) {
                    callArgs[i] = decodeValue(inner.get(i), vf);
                }

                ctx.enter();
                try (Rdf4jWasmInstance instance =
                        new Rdf4jWasmInstance(new java.net.URL(url))) {
                    final List<WitValueMarshaller.Row> rows = instance.evaluate(vf, callArgs);
                    return new Object[] { ComponentVal.ok(encodeRows(rows, ctx.maxRows())) };
                } finally {
                    ctx.exit();
                }
            } catch (Exception e) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "invoke-wasm: " + (e.getMessage() == null ? e.toString() : e.getMessage()))) };
            }
        };
    }

    /**
     * Encode a {@link Rdf4jWasmInstance#evaluate} return (List<Row>)
     * back into the WIT {@code binding-sets} record. Companion to the
     * {@link CloseableIteration} overload; this one shapes the
     * invoke-wasm response.
     */
    private static ComponentVal encodeRows(final List<WitValueMarshaller.Row> rows,
                                           final int rowCap) {
        final List<String> vars = rows.isEmpty() ? List.of() : rows.get(0).vars;
        final List<ComponentVal> varsVals = new ArrayList<>();
        for (String v : vars) varsVals.add(ComponentVal.string(v));

        final List<ComponentVal> rowVals = new ArrayList<>();
        int emitted = 0;
        for (WitValueMarshaller.Row row : rows) {
            if (emitted >= rowCap) break;
            final List<ComponentVal> bindings = new ArrayList<>();
            for (int i = 0; i < row.vars.size(); i++) {
                final Value v = row.values.get(i);
                if (v == null) continue;
                final Map<String, ComponentVal> bindingFields = new LinkedHashMap<>();
                bindingFields.put("name", ComponentVal.string(row.vars.get(i)));
                bindingFields.put("value", encodeValue(v));
                bindings.add(ComponentVal.record(bindingFields));
            }
            rowVals.add(ComponentVal.list(bindings));
            emitted++;
        }
        final Map<String, ComponentVal> bs = new LinkedHashMap<>();
        bs.put("vars", ComponentVal.list(varsVals));
        bs.put("rows", ComponentVal.list(rowVals));
        return ComponentVal.record(bs);
    }

    /** {@code follow-predicate: func(subject: value, predicate: value)
     *  -> result<list<value>, string>}  (v0.3.3). */
    public static WitHostFunction followPredicate() {
        return args -> {
            if (!WebFunctionConfig.callbackEnabled()) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "wf callback disabled by webfunctions.callback.enabled=false")) };
            }
            final CallbackContext ctx = CallbackContext.current();
            if (ctx == null) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "wf callback: no strategy bound")) };
            }
            try {
                final org.eclipse.rdf4j.model.Value subj = decodeValue(
                    (ComponentVal) args[0], ctx.valueFactory());
                final org.eclipse.rdf4j.model.Value pred = decodeValue(
                    (ComponentVal) args[1], ctx.valueFactory());
                ctx.enter();
                try {
                    final java.util.List<org.eclipse.rdf4j.model.Value> objs =
                        ctx.followPredicate(subj, pred);
                    final java.util.List<ComponentVal> encoded =
                        new java.util.ArrayList<>(objs.size());
                    for (org.eclipse.rdf4j.model.Value v : objs) encoded.add(encodeValue(v));
                    return new Object[] { ComponentVal.ok(ComponentVal.list(encoded)) };
                } finally {
                    ctx.exit();
                }
            } catch (RuntimeException e) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    e.getMessage() == null ? e.toString() : e.getMessage())) };
            }
        };
    }

    /** {@code prepare-query: func(sparql: string) -> result<u32, string>}
     *  (v0.3.2). */
    public static WitHostFunction prepareQuery() {
        return args -> {
            if (!WebFunctionConfig.callbackEnabled()) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "wf callback disabled by webfunctions.callback.enabled=false")) };
            }
            final CallbackContext ctx = CallbackContext.current();
            if (ctx == null) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "wf callback: no strategy bound")) };
            }
            try {
                final String sparql = ((ComponentVal) args[0]).asString();
                return new Object[] { ComponentVal.ok(ComponentVal.u32((long) ctx.prepare(sparql))) };
            } catch (RuntimeException e) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    e.getMessage() == null ? e.toString() : e.getMessage())) };
            }
        };
    }

    /** {@code run-prepared: func(handle: u32, bindings: list<binding>,
     *  max-rows: option<u32>) -> result<binding-sets, string>}  (v0.3.2). */
    public static WitHostFunction runPrepared() {
        return args -> {
            if (!WebFunctionConfig.callbackEnabled()) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "wf callback disabled by webfunctions.callback.enabled=false")) };
            }
            final CallbackContext ctx = CallbackContext.current();
            if (ctx == null) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "wf callback: no strategy bound")) };
            }
            try {
                final int handle = (int) ((ComponentVal) args[0]).asU32();
                final BindingSet initial = decodeBindings((ComponentVal) args[1], ctx);
                final int rowCap = decodeOptionalU32((ComponentVal) args[2]).orElseGet(ctx::maxRows);

                ctx.enter();
                try (CloseableIteration<BindingSet> iter = ctx.runPrepared(handle, initial)) {
                    return new Object[] { ComponentVal.ok(encodeBindingSets(iter, rowCap)) };
                } finally {
                    ctx.exit();
                }
            } catch (RuntimeException e) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    e.getMessage() == null ? e.toString() : e.getMessage())) };
            }
        };
    }

    /**
     * v0.5 {@code execute-update: func(update: string) -> result<_, string>}.
     *
     * <p>Same semantics as the v0.3.1 two-arg {@link #executeUpdate()}
     * variant but with the initial-bindings arg removed — the v0.5 WIT
     * simplified the signature. Delegates to
     * {@link CallbackContext#executeUpdate(String)} against the outer sail.
     */
    public static WitHostFunction executeUpdateV05() {
        return args -> {
            if (!WebFunctionConfig.callbackEnabled()) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "wf callback disabled by webfunctions.callback.enabled=false")) };
            }
            final CallbackContext ctx = CallbackContext.current();
            if (ctx == null) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "wf callback: no context bound")) };
            }
            try {
                final String sparql = ((ComponentVal) args[0]).asString();
                ctx.enter();
                try {
                    ctx.executeUpdate(sparql);
                    return new Object[] { ComponentVal.ok() };
                } finally {
                    ctx.exit();
                }
            } catch (RuntimeException e) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    e.getMessage() == null ? e.toString() : e.getMessage())) };
            }
        };
    }

    /**
     * v0.5 {@code sink-open: func(url: string) -> result<u32, string>}.
     *
     * <p>Dispatches on the URL scheme. Currently ships {@code sqlite://};
     * other backends (duckdb, postgres, sirix) slot in behind the
     * {@link Sink} interface. Handles live in the current
     * {@link CallbackContext} — the outer {@code wf:call} frame closes
     * anything the guest didn't release explicitly.
     */
    public static WitHostFunction sinkOpen() {
        return args -> {
            if (!WebFunctionConfig.callbackEnabled()) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "wf callback disabled by webfunctions.callback.enabled=false")) };
            }
            final CallbackContext ctx = CallbackContext.current();
            if (ctx == null) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "wf callback: sink-open has no context bound")) };
            }
            try {
                final String url = ((ComponentVal) args[0]).asString();
                final java.net.URI parsed;
                try {
                    parsed = new java.net.URI(url);
                } catch (java.net.URISyntaxException use) {
                    return new Object[] { ComponentVal.err(ComponentVal.string(
                        "sink-open: URL did not parse: " + use.getMessage())) };
                }
                final String scheme = parsed.getScheme();
                if (scheme == null) {
                    return new Object[] { ComponentVal.err(ComponentVal.string(
                        "sink-open: URL is missing a scheme")) };
                }
                final Sink sink;
                switch (scheme.toLowerCase(java.util.Locale.ROOT)) {
                    case "sqlite":
                        sink = SqliteSink.open(url);
                        break;
                    case "postgres":
                    case "postgresql":
                        // wf_relational v0.1: Postgres via the standard
                        // libpq URL. postgresql:// accepted as an alias.
                        sink = PostgresSink.open(url);
                        break;
                    default:
                        return new Object[] { ComponentVal.err(ComponentVal.string(
                            "sink scheme `" + scheme + "` not supported (v0.5 ships sqlite, postgres)")) };
                }
                final int handle = ctx.registerSink(sink);
                return new Object[] { ComponentVal.ok(ComponentVal.u32((long) handle)) };
            } catch (Exception e) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "sink-open: " + (e.getMessage() == null ? e.toString() : e.getMessage()))) };
            }
        };
    }

    /**
     * v0.5 {@code sink-execute: func(handle: u32, query: string,
     *  params: list<value>) -> result<binding-sets, string>}.
     *
     * <p>Looks up the sink by handle, dispatches into
     * {@link Sink#execute(String, List)} with the raw
     * {@link ComponentVal} params — each backend's {@code Sink} impl owns
     * the WIT ↔ backend-native type marshalling. Returns empty
     * {@code binding-sets} for DDL/DML, populated for SELECT.
     */
    public static WitHostFunction sinkExecute() {
        return args -> {
            if (!WebFunctionConfig.callbackEnabled()) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "wf callback disabled by webfunctions.callback.enabled=false")) };
            }
            final CallbackContext ctx = CallbackContext.current();
            if (ctx == null) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "wf callback: sink-execute has no context bound")) };
            }
            try {
                final int handle = (int) ((ComponentVal) args[0]).asU32();
                final String query = ((ComponentVal) args[1]).asString();
                final List<ComponentVal> params = ((ComponentVal) args[2]).asList();
                final Sink sink = ctx.getSink(handle);
                if (sink == null) {
                    return new Object[] { ComponentVal.err(ComponentVal.string(
                        "sink-execute: stale or closed handle " + handle)) };
                }
                final ComponentVal bs = sink.execute(query, params);
                return new Object[] { ComponentVal.ok(bs) };
            } catch (Exception e) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "sink-execute: " + (e.getMessage() == null ? e.toString() : e.getMessage()))) };
            }
        };
    }

    /**
     * v0.5 {@code sink-close: func(handle: u32) -> result<_, string>}.
     *
     * <p>Explicit release; optional per the WIT contract (the outer
     * {@code wf:call} frame closes anything left open).
     */
    public static WitHostFunction sinkClose() {
        return args -> {
            if (!WebFunctionConfig.callbackEnabled()) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "wf callback disabled by webfunctions.callback.enabled=false")) };
            }
            final CallbackContext ctx = CallbackContext.current();
            if (ctx == null) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "wf callback: sink-close has no context bound")) };
            }
            try {
                final int handle = (int) ((ComponentVal) args[0]).asU32();
                if (!ctx.closeSink(handle)) {
                    return new Object[] { ComponentVal.err(ComponentVal.string(
                        "sink-close: stale or already-closed handle " + handle)) };
                }
                return new Object[] { ComponentVal.ok() };
            } catch (RuntimeException e) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "sink-close: " + (e.getMessage() == null ? e.toString() : e.getMessage()))) };
            }
        };
    }

    /**
     * {@code wf:sagegraph/host@0.2.0#execute-query:
     *  func(sparql: string) -> result<string, string>}.
     *
     * <p>The wf_sagegraph guest imports this to issue k-hop neighborhood
     * SPARQL round-trips back into the engine hosting it. Unlike the
     * {@code stardog:webfunction/host} family's {@link #executeQuery} — which
     * hands back a WIT-encoded {@code binding-sets} record — this one returns
     * raw SPARQL 1.1 Results JSON as a string and lets the guest parse it.
     * Same shape wf_document / wf_fulltext expose via {@code http-post-json};
     * sagegraph just reaches the local engine instead of an external service.
     *
     * <p>Reuses {@link CallbackContext#executeSelect} — the same executor
     * behind the stardog:webfunction/host callbacks — then serializes with
     * RDF4J's {@link org.eclipse.rdf4j.query.resultio.sparqljson.SPARQLResultsJSONWriter}
     * for the SELECT / CONSTRUCT / DESCRIBE / ASK shapes that
     * {@code executeSelect} already unifies onto tuple-shape iterators.
     */
    public static WitHostFunction sagegraphExecuteQuery() {
        return args -> {
            if (!WebFunctionConfig.callbackEnabled()) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "wf callback disabled by webfunctions.callback.enabled=false")) };
            }
            final CallbackContext ctx = CallbackContext.current();
            if (ctx == null) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "wf callback: no strategy bound — install WfEvaluationStrategyFactory "
                    + "on the sail to enable callbacks")) };
            }
            try {
                final String sparql = ((ComponentVal) args[0]).asString();
                ctx.enter();
                try (CloseableIteration<BindingSet> iter =
                        ctx.executeSelect(sparql, new org.eclipse.rdf4j.query.impl.EmptyBindingSet())) {
                    final java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                    final org.eclipse.rdf4j.query.resultio.sparqljson.SPARQLResultsJSONWriter writer =
                            new org.eclipse.rdf4j.query.resultio.sparqljson.SPARQLResultsJSONWriter(buf);
                    // Peek the first row (if any) to collect the projection
                    // names — CallbackContext.executeSelect unifies SELECT /
                    // CONSTRUCT / DESCRIBE / ASK onto BindingSet iterators
                    // that don't carry a separate var list, so derive it from
                    // the first row's binding names (fixed s/p/o for graph
                    // shapes, _ask for boolean).
                    final java.util.List<String> vars = new java.util.ArrayList<>();
                    BindingSet peeked = null;
                    if (iter.hasNext()) {
                        peeked = iter.next();
                        vars.addAll(peeked.getBindingNames());
                    }
                    writer.startQueryResult(vars);
                    if (peeked != null) {
                        writer.handleSolution(peeked);
                    }
                    while (iter.hasNext()) {
                        writer.handleSolution(iter.next());
                    }
                    writer.endQueryResult();
                    return new Object[] { ComponentVal.ok(ComponentVal.string(
                        buf.toString(java.nio.charset.StandardCharsets.UTF_8))) };
                } finally {
                    ctx.exit();
                }
            } catch (RuntimeException e) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    e.getMessage() == null ? e.toString() : e.getMessage())) };
            }
        };
    }

    /**
     * {@code wf:fulltext/host@0.1.0#http-post-json:
     *  func(url: string, body: string) -> result<string, string>}.
     *
     * <p>The wf_fulltext guest imports this to POST a JSON search request
     * to Manticore (OpenSearch as a follow-up per the wf-fulltext design
     * memo). Stateless — does not touch {@link CallbackContext}, does not
     * respect {@code webfunctions.callback.enabled} (that flag is about
     * re-entering the graph; this import reaches an external service).
     *
     * <p>Error contract, mirrored across every substrate engine:
     * <ul>
     *   <li>2xx: {@code Ok(response_body_verbatim)}</li>
     *   <li>non-2xx: {@code Err("HTTP <code>: <body>")}</li>
     *   <li>transport / URL / IO / interrupt: {@code Err("http transport: <details>")}</li>
     * </ul>
     * Timeout: 30 seconds — matches the oxigraph-wf substrate and the
     * Jena binding so a wf:call frame observes one honest deadline
     * regardless of destination.
     */
    public static WitHostFunction httpPostJson() {
        return args -> {
            try {
                final String url = ((ComponentVal) args[0]).asString();
                final String body = ((ComponentVal) args[1]).asString();
                return new Object[] { httpPostJsonImpl(url, body) };
            } catch (RuntimeException e) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "http transport: " + (e.getMessage() == null ? e.toString() : e.getMessage()))) };
            }
        };
    }

    private static ComponentVal httpPostJsonImpl(final String url, final String body) {
        final java.net.URI uri;
        try {
            uri = java.net.URI.create(url);
        } catch (IllegalArgumentException iae) {
            return ComponentVal.err(ComponentVal.string(
                "http transport: url did not parse: " + iae.getMessage()));
        }
        final java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(30))
                .build();
        final java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(uri)
                .timeout(java.time.Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                    body, java.nio.charset.StandardCharsets.UTF_8))
                .build();
        try {
            final java.net.http.HttpResponse<String> response = client.send(
                request, java.net.http.HttpResponse.BodyHandlers.ofString(
                    java.nio.charset.StandardCharsets.UTF_8));
            final int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return ComponentVal.ok(ComponentVal.string(response.body()));
            }
            return ComponentVal.err(ComponentVal.string(
                "HTTP " + status + ": " + response.body()));
        } catch (java.io.IOException ioe) {
            return ComponentVal.err(ComponentVal.string(
                "http transport: " + (ioe.getMessage() == null ? ioe.toString() : ioe.getMessage())));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return ComponentVal.err(ComponentVal.string("http transport: interrupted"));
        }
    }

    /**
     * {@code wf:embed/host@0.1.0#embed-text:
     *  func(text: string, model: string) -> result<list<f32>, string>}.
     *
     * <p>Substrate-side text-embedding host callback. Dispatches through
     * one of two paths, chosen at each call by the {@code WF_EMBED_SIDECAR_URL}
     * environment variable:
     *
     * <ul>
     *   <li><b>Env set → sidecar dispatch (Option B).</b> POST
     *       {@code {"text": ..., "model": ...}} to the configured URL,
     *       parse {@code {"embedding": [f32, ...]}} from the response, and
     *       return those bytes verbatim. When the sidecar is
     *       {@code fastembed-rs}-backed with the same on-disk model as the
     *       Rust engines' embedded {@code fastembed::TextEmbedding}, this
     *       gives cross-engine byte parity — the pinned
     *       {@code sagegraph_text_features} expected_bindings are
     *       fastembed-rs {@code bge-small-en} output; a fastembed-rs
     *       sidecar reproduces those bytes.</li>
     *   <li><b>Env unset → deterministic SHA-256-derived stub fallback.</b>
     *       Bit-for-bit identical output to the earlier Rust-engine stub
     *       landing (oxigraph-wf {@code d07c2d6}, qlever-wf-runtime
     *       {@code register_wf_embed_host_import} stub). Emits a one-shot
     *       warning on stderr so operators know the plugin fell back.
     *       Kept so JVM engines still resolve the wf:embed import even
     *       without a sidecar deployed; cross-engine byte parity does NOT
     *       hold against Rust engines' real fastembed output in this
     *       mode.</li>
     * </ul>
     *
     * <p>Stub algorithm — matches {@code embed_text_stub} in
     * {@code oxigraph-wf/src/host.rs}:
     * <ol>
     *   <li>Dispatch model → dim via {@link #embedModelCatalog()}
     *       (default 384 for unknown).</li>
     *   <li>Hash {@code model || 0x00 || text || 0x00 || counter_le}
     *       under SHA-256, incrementing {@code counter} until we have
     *       {@code dim * 4} bytes.</li>
     *   <li>Read each 4-byte window as a little-endian signed i32,
     *       map to {@code [-1, 1]} via {@code raw / (float) Integer.MAX_VALUE}.</li>
     *   <li>L2-normalise (f32 sum + f32 sqrt to preserve byte parity).</li>
     * </ol>
     *
     * <p>Empty text → {@code Err("embed-text: text is empty")}. Sidecar
     * transport / parse errors → {@code Err("embed-text: <details>")}; the
     * fallback stub is only reached when the env var is unset (a
     * deliberately-configured sidecar returning HTTP 500 surfaces the 500
     * rather than being silently masked by stub bytes).
     */
    public static WitHostFunction embedText() {
        return args -> {
            try {
                final String text = ((ComponentVal) args[0]).asString();
                final String model = ((ComponentVal) args[1]).asString();
                if (text.isEmpty()) {
                    return new Object[] { ComponentVal.err(ComponentVal.string(
                        "embed-text: text is empty")) };
                }
                final String sidecarUrl = System.getenv(EMBED_SIDECAR_URL_ENV);
                final float[] vec;
                if (sidecarUrl != null && !sidecarUrl.isEmpty()) {
                    vec = embedTextViaSidecar(text, model, sidecarUrl);
                } else {
                    warnStubFallbackOnce();
                    vec = embedTextStub(text, model);
                }
                final List<ComponentVal> vals = new ArrayList<>(vec.length);
                for (float v : vec) {
                    vals.add(ComponentVal.f32(v));
                }
                return new Object[] { ComponentVal.ok(ComponentVal.list(vals)) };
            } catch (RuntimeException e) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "embed-text: " + (e.getMessage() == null ? e.toString() : e.getMessage()))) };
            }
        };
    }

    /** Env var name for the {@link #embedText} sidecar dispatch URL. */
    static final String EMBED_SIDECAR_URL_ENV = "WF_EMBED_SIDECAR_URL";

    /**
     * One-shot gate for the "fell back to SHA-256 stub" warning. Wrapped
     * in an {@link java.util.concurrent.atomic.AtomicBoolean} so the log
     * line fires once per JVM regardless of how many wf:call frames hit
     * the fallback path.
     */
    private static final java.util.concurrent.atomic.AtomicBoolean
        STUB_FALLBACK_WARNED = new java.util.concurrent.atomic.AtomicBoolean(false);

    private static void warnStubFallbackOnce() {
        if (STUB_FALLBACK_WARNED.compareAndSet(false, true)) {
            System.err.println(
                "wf:embed: " + EMBED_SIDECAR_URL_ENV + " unset — falling back to "
                + "deterministic SHA-256 stub. Cross-engine byte parity vs Rust "
                + "engines' real fastembed output does NOT hold in this mode. "
                + "Point " + EMBED_SIDECAR_URL_ENV + " at a fastembed-rs HTTP "
                + "sidecar to restore parity.");
        }
    }

    /**
     * POST {@code {"text": ..., "model": ...}} to the sidecar and parse
     * {@code {"embedding": [f32, ...]}} out of the response body. Any
     * transport / parse failure surfaces as a
     * {@link RuntimeException} so the outer {@link #embedText} envelope
     * turns it into a WIT {@code Err} rather than falling back to the
     * stub (see {@link #embedText} javadoc for the rationale).
     */
    static float[] embedTextViaSidecar(final String text, final String model,
                                       final String sidecarUrl) {
        final java.net.URI uri;
        try {
            // wf_embed_sidecar registers POST /embed; strip any trailing slashes
            // from the configured base URL before appending the endpoint path.
            uri = java.net.URI.create(sidecarUrl.replaceAll("/+$", "") + "/embed");
        } catch (IllegalArgumentException iae) {
            throw new IllegalStateException(
                "sidecar url did not parse: " + iae.getMessage(), iae);
        }
        final String body =
            "{\"text\":" + jsonEscape(text)
            + ",\"model\":" + jsonEscape(model) + "}";
        // wf_embed_sidecar is backed by tiny_http, which speaks HTTP/1.1
        // only. Java's HttpClient defaults to HTTP/2 (with h2c upgrade on
        // plain HTTP), and the client hangs waiting for an upgrade
        // acknowledgement the sidecar never sends — surfaces as `request
        // timed out` at the configured deadline. Pin the client to HTTP/1.1
        // so the request round-trips against the sidecar's actual protocol.
        final java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .version(java.net.http.HttpClient.Version.HTTP_1_1)
                .connectTimeout(java.time.Duration.ofSeconds(30))
                .build();
        final java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(uri)
                .timeout(java.time.Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                    body, java.nio.charset.StandardCharsets.UTF_8))
                .build();
        final java.net.http.HttpResponse<String> response;
        try {
            response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString(
                java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException ioe) {
            throw new IllegalStateException(
                "sidecar transport: " + (ioe.getMessage() == null ? ioe.toString() : ioe.getMessage()), ioe);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("sidecar transport: interrupted", ie);
        }
        final int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new IllegalStateException(
                "sidecar HTTP " + status + ": " + response.body());
        }
        return parseEmbeddingArray(response.body());
    }

    /**
     * Extract the {@code "embedding"} array of floats from the sidecar's
     * JSON response. Deliberately minimal — we don't want to pull a JSON
     * library into this class just for one two-field payload. Handles the
     * common shapes: {@code {"embedding": [1.0, 2.0, ...]}}, tolerant of
     * whitespace and scientific notation. Anything else raises so the
     * caller can surface a WIT {@code Err} with the raw body prefix.
     */
    static float[] parseEmbeddingArray(final String responseBody) {
        final int keyIdx = responseBody.indexOf("\"embedding\"");
        if (keyIdx < 0) {
            throw new IllegalStateException(
                "sidecar response missing 'embedding' key: "
                + truncate(responseBody, 200));
        }
        final int openBracket = responseBody.indexOf('[', keyIdx);
        if (openBracket < 0) {
            throw new IllegalStateException(
                "sidecar response 'embedding' is not an array: "
                + truncate(responseBody, 200));
        }
        final int closeBracket = responseBody.indexOf(']', openBracket);
        if (closeBracket < 0) {
            throw new IllegalStateException(
                "sidecar response 'embedding' array is unterminated: "
                + truncate(responseBody, 200));
        }
        final String inner = responseBody.substring(openBracket + 1, closeBracket).trim();
        if (inner.isEmpty()) {
            return new float[0];
        }
        final String[] parts = inner.split(",");
        final float[] out = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Float.parseFloat(parts[i].trim());
            } catch (NumberFormatException nfe) {
                throw new IllegalStateException(
                    "sidecar 'embedding'[" + i + "] not a f32: " + parts[i].trim(), nfe);
            }
        }
        return out;
    }

    private static String jsonEscape(final String s) {
        final StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b");  break;
                case '\f': sb.append("\\f");  break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static String truncate(final String s, final int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /**
     * {@code wf:embed/host@0.1.0#list-models: func() -> list<string>}.
     *
     * <p>Enumerates the model names the substrate registration recognises.
     * Byte-identical to the Rust-side catalog so cross-engine calls to
     * this import see the same ordered list.
     */
    public static WitHostFunction embedListModels() {
        return args -> {
            final String[][] catalog = embedModelCatalog();
            final List<ComponentVal> vals = new ArrayList<>(catalog.length);
            for (String[] pair : catalog) {
                vals.add(ComponentVal.string(pair[0]));
            }
            return new Object[] { ComponentVal.list(vals) };
        };
    }

    /**
     * Model → embedding-dimension catalog for the wf:embed v0.1 stub.
     * Names + dims mirror {@code fastembed-rs} so a future swap to real
     * inference is a signature-compatible substitution. Held in lockstep
     * with {@code oxigraph-wf::host::embed_model_catalog}.
     */
    private static String[][] embedModelCatalog() {
        return new String[][] {
            {"bge-small-en",     "384"},
            {"all-MiniLM-L6-v2", "384"},
            {"bge-base-en",      "768"},
            {"nomic-embed-text", "768"},
            {"bge-large-en",     "1024"},
        };
    }

    /**
     * Deterministic SHA-256-derived embedding matching the Rust
     * {@code embed_text_stub} in {@code oxigraph-wf/src/host.rs} (commit
     * {@code d07c2d6}) so cross-engine byte parity holds.
     */
    static float[] embedTextStub(final String text, final String model) {
        int dim = 384;
        for (String[] pair : embedModelCatalog()) {
            if (pair[0].equalsIgnoreCase(model)) {
                dim = Integer.parseInt(pair[1]);
                break;
            }
        }
        final int bytesNeeded = Math.multiplyExact(dim, 4);
        final byte[] bytes = new byte[((bytesNeeded + 31) / 32) * 32];
        final byte[] modelBytes = model.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        final byte[] textBytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int counter = 0;
        int offset = 0;
        final java.security.MessageDigest md;
        try {
            md = java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable in this JVM", e);
        }
        while (offset < bytesNeeded) {
            md.reset();
            md.update(modelBytes);
            md.update((byte) 0);
            md.update(textBytes);
            md.update((byte) 0);
            md.update((byte) (counter & 0xff));
            md.update((byte) ((counter >>> 8) & 0xff));
            md.update((byte) ((counter >>> 16) & 0xff));
            md.update((byte) ((counter >>> 24) & 0xff));
            final byte[] digest = md.digest();
            System.arraycopy(digest, 0, bytes, offset, digest.length);
            offset += digest.length;
            counter++;
        }
        final float[] out = new float[dim];
        for (int i = 0; i < dim; i++) {
            final int b0 = bytes[i * 4]     & 0xff;
            final int b1 = bytes[i * 4 + 1] & 0xff;
            final int b2 = bytes[i * 4 + 2] & 0xff;
            final int b3 = bytes[i * 4 + 3] & 0xff;
            final int raw = b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
            out[i] = (float) raw / (float) Integer.MAX_VALUE;
        }
        // Rust reference: `let norm: f32 = out.iter().map(|x| x*x).sum::<f32>().sqrt();`
        // Mirror the f32 accumulation + f32 sqrt exactly — a
        // double-accumulated variant drifts a few ULP by dim 384 and
        // breaks the cross-engine byte-parity contract.
        float norm = 0.0f;
        for (float f : out) norm += f * f;
        norm = (float) Math.sqrt((double) norm);
        if (norm > 0.0f) {
            for (int i = 0; i < dim; i++) {
                out[i] = out[i] / norm;
            }
        }
        return out;
    }

    /**
     * New-shape {@code tegmentum:webfunction/graph-callbacks@0.1.0#execute-query:
     *  func(sparql: string) -> result<query-result, graph-call-error>}.
     *
     * <p>Bridges the base {@code tegmentum:webfunction} substrate WIT onto
     * the existing {@link CallbackContext#executeSelect} executor. Distinct
     * from {@link #executeQuery} — which speaks the legacy Stardog
     * {@code stardog:webfunction/host} shape (three args, {@code binding-sets}
     * record return) — this one accepts a single query string and returns
     * the base {@code query-result} variant (bindings / quads / boolean arms).
     *
     * <p>MVP: unifies onto the {@code bindings} arm. RDF4J's
     * {@code executeSelect} returns a {@link CloseableIteration}
     * &lt;BindingSet&gt;; every solution flattens into the flat
     * {@code list<binding>} shape memo §4 specifies for the bindings arm.
     * The base WIT's separate {@code quads} and {@code boolean} arms are a
     * follow-up shape refinement — guests reading the {@code bindings} arm
     * see the same data through a different envelope.
     *
     * <p>Error discrimination:
     * <ul>
     *   <li>Callback disabled → {@code not-permitted}.</li>
     *   <li>SPARQL parse failure ({@link org.eclipse.rdf4j.query.MalformedQueryException},
     *       wrapped by {@link CallbackContext} in a RuntimeException) →
     *       {@code syntax-error}.</li>
     *   <li>{@link SecurityException} → {@code not-permitted}.</li>
     *   <li>Everything else — including
     *       {@link org.eclipse.rdf4j.query.QueryEvaluationException} —
     *       → {@code backend-error}.</li>
     * </ul>
     */
    public static WitHostFunction graphExecuteQuery() {
        return args -> {
            if (!WebFunctionConfig.callbackEnabled()) {
                return new Object[] { ComponentVal.err(
                    graphCallError("not-permitted",
                        "wf callback disabled by webfunctions.callback.enabled=false")) };
            }
            final CallbackContext ctx = CallbackContext.current();
            if (ctx == null) {
                return new Object[] { ComponentVal.err(
                    graphCallError("backend-error",
                        "wf callback: no context bound")) };
            }
            try {
                final String sparql = ((ComponentVal) args[0]).asString();
                ctx.enter();
                try (CloseableIteration<BindingSet> iter = ctx.executeSelect(
                        sparql, new org.eclipse.rdf4j.query.impl.EmptyBindingSet())) {
                    final List<ComponentVal> bindings = new ArrayList<>();
                    while (iter.hasNext()) {
                        final BindingSet row = iter.next();
                        for (String var : row.getBindingNames()) {
                            final Value v = row.getValue(var);
                            if (v == null) continue;
                            final Map<String, ComponentVal> bindingFields = new LinkedHashMap<>();
                            bindingFields.put("variable", ComponentVal.string(var));
                            bindingFields.put("value", encodeTermV1(v));
                            bindings.add(ComponentVal.record(bindingFields));
                        }
                    }
                    final ComponentVal queryResult = ComponentVal.variant(
                        "bindings", ComponentVal.list(bindings));
                    return new Object[] { ComponentVal.ok(queryResult) };
                } finally {
                    ctx.exit();
                }
            } catch (RuntimeException e) {
                return new Object[] { ComponentVal.err(discriminateGraphError(e)) };
            }
        };
    }

    /**
     * New-shape {@code tegmentum:webfunction/graph-callbacks@0.1.0#execute-update:
     *  func(sparql: string) -> result<_, graph-call-error>}.
     *
     * <p>Bridges to {@link CallbackContext#executeUpdate(String)} — the same
     * executor that backs the legacy v0.5 {@link #executeUpdateV05}. Same
     * error discrimination as {@link #graphExecuteQuery}: SPARQL parse
     * failures (org.eclipse.rdf4j.query.MalformedQueryException) map to
     * {@code syntax-error}, {@link SecurityException} to
     * {@code not-permitted}, everything else (including
     * QueryEvaluationException) to {@code backend-error}.
     */
    public static WitHostFunction graphExecuteUpdate() {
        return args -> {
            if (!WebFunctionConfig.callbackEnabled()) {
                return new Object[] { ComponentVal.err(
                    graphCallError("not-permitted",
                        "wf callback disabled by webfunctions.callback.enabled=false")) };
            }
            final CallbackContext ctx = CallbackContext.current();
            if (ctx == null) {
                return new Object[] { ComponentVal.err(
                    graphCallError("backend-error",
                        "wf callback: no context bound")) };
            }
            try {
                final String sparql = ((ComponentVal) args[0]).asString();
                ctx.enter();
                try {
                    ctx.executeUpdate(sparql);
                    return new Object[] { ComponentVal.ok() };
                } finally {
                    ctx.exit();
                }
            } catch (RuntimeException e) {
                return new Object[] { ComponentVal.err(discriminateGraphError(e)) };
            }
        };
    }

    /**
     * Map a caught {@link RuntimeException} to the appropriate
     * {@code graph-call-error} variant. Parse-time exceptions
     * ({@link org.eclipse.rdf4j.query.MalformedQueryException}, wrapped in
     * a RuntimeException by {@link CallbackContext}) map to
     * {@code syntax-error}; security failures map to {@code not-permitted};
     * every other runtime failure — including
     * {@link org.eclipse.rdf4j.query.QueryEvaluationException} — lands on
     * {@code backend-error} (the preserved MVP default). Walks up to eight
     * hops of the cause chain so a MalformedQueryException wrapped in
     * multiple layers still surfaces as syntax-error.
     */
    private static ComponentVal discriminateGraphError(final RuntimeException e) {
        final String msg = e.getMessage() == null ? e.toString() : e.getMessage();
        Throwable cur = e;
        int hops = 0;
        while (cur != null && hops < 8) {
            if (cur instanceof org.eclipse.rdf4j.query.MalformedQueryException) {
                final String parseMsg = cur.getMessage() == null ? cur.toString() : cur.getMessage();
                return graphCallError("syntax-error", parseMsg);
            }
            if (cur instanceof SecurityException) {
                final String secMsg = cur.getMessage() == null ? cur.toString() : cur.getMessage();
                return graphCallError("not-permitted", secMsg);
            }
            cur = cur.getCause();
            hops++;
        }
        return graphCallError("backend-error", msg);
    }

    /** Build a {@code graph-call-error} variant value with the given arm and message. */
    private static ComponentVal graphCallError(final String armName, final String message) {
        return ComponentVal.variant(armName, ComponentVal.string(message));
    }

    // ---- tegmentum:webfunction/http-callbacks@0.1.0 -------------------------

    /**
     * Base-substrate {@code tegmentum:webfunction/http-callbacks@0.1.0#http-get:
     *  func(url: string, headers: list<http-header>) -> result<http-response, http-error>}.
     *
     * <p>Distinct from the legacy {@link #httpPostJson} (wf:fulltext /
     * wf:document / wf:sagegraph shape which returns {@code result<string,
     * string>}). Uses JDK-native {@link java.net.http.HttpClient}. Response
     * headers come back in the casing HttpClient returns; RFC 7230 §3.2
     * canonicalises Http/1.1 headers to lowercase.
     *
     * <p>Error surface:
     * <ul>
     *   <li>Malformed URL / invalid header shape → {@code invalid-request}.</li>
     *   <li>Non-2xx response → {@code status(u16)} (naked status code).</li>
     *   <li>IOException / transport failure / interrupt → {@code network}.</li>
     * </ul>
     * A 2xx response returns {@code Ok(http-response)}.
     */
    public static WitHostFunction httpGetV1() {
        return args -> {
            try {
                final String url = ((ComponentVal) args[0]).asString();
                final List<ComponentVal> headers = ((ComponentVal) args[1]).asList();
                return new Object[] { httpSendV1("GET", url, headers, null) };
            } catch (RuntimeException e) {
                return new Object[] { ComponentVal.err(httpErrorV1("invalid-request",
                    e.getMessage() == null ? e.toString() : e.getMessage())) };
            }
        };
    }

    /**
     * Base-substrate {@code tegmentum:webfunction/http-callbacks@0.1.0#http-post-json:
     *  func(url: string, body: string, headers: list<http-header>)
     *   -> result<http-response, http-error>}.
     *
     * <p>Adds a default {@code Content-Type: application/json} header when
     * the caller does not supply one. Same error surface as
     * {@link #httpGetV1}. Distinct method name from the legacy
     * {@link #httpPostJson} which returns {@code result<string, string>}.
     */
    public static WitHostFunction httpPostJsonV1() {
        return args -> {
            try {
                final String url = ((ComponentVal) args[0]).asString();
                final String body = ((ComponentVal) args[1]).asString();
                final List<ComponentVal> headers = ((ComponentVal) args[2]).asList();
                return new Object[] { httpSendV1("POST", url, headers, body) };
            } catch (RuntimeException e) {
                return new Object[] { ComponentVal.err(httpErrorV1("invalid-request",
                    e.getMessage() == null ? e.toString() : e.getMessage())) };
            }
        };
    }

    private static ComponentVal httpSendV1(
            final String method,
            final String url,
            final List<ComponentVal> headerRecords,
            final String bodyOrNull) {
        final java.net.URI uri;
        try {
            uri = java.net.URI.create(url);
        } catch (IllegalArgumentException iae) {
            return ComponentVal.err(httpErrorV1("invalid-request",
                "url did not parse: " + iae.getMessage()));
        }
        final java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(30))
                .build();
        final java.net.http.HttpRequest.Builder builder = java.net.http.HttpRequest.newBuilder(uri)
                .timeout(java.time.Duration.ofSeconds(30));

        boolean sawContentType = false;
        for (ComponentVal header : headerRecords) {
            final Map<String, ComponentVal> fields = header.asRecord();
            final String name = fields.get("name").asString();
            final String value = fields.get("value").asString();
            try {
                builder.header(name, value);
            } catch (IllegalArgumentException iae) {
                return ComponentVal.err(httpErrorV1("invalid-request",
                    "header rejected: " + iae.getMessage()));
            }
            if ("content-type".equalsIgnoreCase(name)) sawContentType = true;
        }
        if ("POST".equals(method)) {
            if (!sawContentType) {
                builder.header("Content-Type", "application/json");
            }
            builder.POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                bodyOrNull == null ? "" : bodyOrNull,
                java.nio.charset.StandardCharsets.UTF_8));
        } else {
            builder.GET();
        }
        try {
            final java.net.http.HttpResponse<String> response = client.send(
                builder.build(),
                java.net.http.HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
            final int status = response.statusCode();
            if (status < 200 || status >= 300) {
                return ComponentVal.err(ComponentVal.variant("status", ComponentVal.u16(status)));
            }
            final List<ComponentVal> respHeaders = new ArrayList<>();
            response.headers().map().forEach((k, vs) -> {
                for (String v : vs) {
                    final Map<String, ComponentVal> hf = new LinkedHashMap<>();
                    hf.put("name", ComponentVal.string(k));
                    hf.put("value", ComponentVal.string(v));
                    respHeaders.add(ComponentVal.record(hf));
                }
            });
            final Map<String, ComponentVal> respFields = new LinkedHashMap<>();
            respFields.put("status", ComponentVal.u16(status));
            respFields.put("headers", ComponentVal.list(respHeaders));
            respFields.put("body", ComponentVal.string(response.body()));
            return ComponentVal.ok(ComponentVal.record(respFields));
        } catch (java.io.IOException ioe) {
            return ComponentVal.err(httpErrorV1("network",
                ioe.getMessage() == null ? ioe.toString() : ioe.getMessage()));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return ComponentVal.err(httpErrorV1("network", "interrupted"));
        }
    }

    private static ComponentVal httpErrorV1(final String armName, final String message) {
        return ComponentVal.variant(armName, ComponentVal.string(message));
    }

    // ---- tegmentum:webfunction/wasm-callbacks@0.1.0 -------------------------

    /**
     * Base-substrate {@code tegmentum:webfunction/wasm-callbacks@0.1.0#invoke-wasm:
     *  func(component-uri: string, function-name: string, args: list<term>)
     *   -> result<term, wasm-call-error>}.
     *
     * <p>MVP: returns {@code wasm-call-error::not-permitted} with a descriptive
     * message. Full sub-component composition on the JVM host is separate
     * future work; the WIT surface is wired so guests importing this
     * interface can link. Distinct from the legacy {@link #invokeWasm}
     * which speaks the {@code stardog:webfunction/host} shape.
     */
    public static WitHostFunction invokeWasmV1() {
        return args -> new Object[] { ComponentVal.err(wasmCallErrorV1("not-permitted",
            "invoke-wasm: not implemented on JVM host (MVP stub — full sub-component "
            + "dispatch is future work)")) };
    }

    /**
     * Base-substrate {@code tegmentum:webfunction/wasm-callbacks@0.1.0#invoke-wasm-service:
     *  func(url: string, args: list<term>) -> result<list<binding>, wasm-call-error>}.
     *
     * <p>Property-function-shape counterpart to {@link #invokeWasmV1}. MVP is
     * a {@code not-permitted} stub for the same reason.
     */
    public static WitHostFunction invokeWasmService() {
        return args -> new Object[] { ComponentVal.err(wasmCallErrorV1("not-permitted",
            "invoke-wasm-service: not implemented on JVM host (MVP stub — full "
            + "sub-component dispatch is future work)")) };
    }

    private static ComponentVal wasmCallErrorV1(final String armName, final String message) {
        return ComponentVal.variant(armName, ComponentVal.string(message));
    }

    /**
     * Encode an RDF4J {@link Value} as the base {@code tegmentum:webfunction/types.term}
     * variant (4 arms: named-node / blank-node / literal / triple). Distinct
     * from the legacy {@link #encodeValue} — which produces the 3-arm
     * {@code stardog:webfunction/host} {@code value} variant with the legacy
     * literal record shape ({@code label} / {@code datatype: string} /
     * {@code lang}).
     *
     * <p>Base literal record uses {@code value: string}, {@code datatype:
     * option<iri>} (absent means xsd:string), and {@code language:
     * option<string>}. RDF-star quoted triples raise — the executor path
     * does not surface them today.
     */
    private static ComponentVal encodeTermV1(final Value v) {
        if (v instanceof IRI iri) {
            return ComponentVal.variant("named-node", ComponentVal.string(iri.stringValue()));
        }
        if (v instanceof BNode bnode) {
            return ComponentVal.variant("blank-node", ComponentVal.string(bnode.getID()));
        }
        if (v instanceof Literal lit) {
            final Map<String, ComponentVal> fields = new LinkedHashMap<>();
            fields.put("value", ComponentVal.string(lit.getLabel()));
            final String dt = lit.getDatatype() != null
                    ? lit.getDatatype().stringValue()
                    : XSD.STRING.stringValue();
            // datatype: option<iri>. absent = xsd:string per RDF 1.1 default.
            if (XSD.STRING.stringValue().equals(dt)) {
                fields.put("datatype", ComponentVal.none());
            } else {
                fields.put("datatype", ComponentVal.some(ComponentVal.string(dt)));
            }
            final Optional<String> lang = lit.getLanguage();
            fields.put("language", lang.isPresent()
                    ? ComponentVal.some(ComponentVal.string(lang.get()))
                    : ComponentVal.none());
            return ComponentVal.variant("literal", ComponentVal.record(fields));
        }
        throw new IllegalArgumentException(
            "wf graph-callbacks: unsupported Value kind for base-WIT term: " + v.getClass().getName());
    }

    /** {@code execute-update: func(sparql: string, bindings: list<binding>)
     *  -> result<_, string>}  (v0.3.1). */
    public static WitHostFunction executeUpdate() {
        return args -> {
            if (!WebFunctionConfig.callbackEnabled()) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "wf callback disabled by webfunctions.callback.enabled=false")) };
            }
            final CallbackContext ctx = CallbackContext.current();
            if (ctx == null) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "wf callback: no context bound")) };
            }
            try {
                final String sparql = ((ComponentVal) args[0]).asString();
                final BindingSet initial = decodeBindings((ComponentVal) args[1], ctx);
                ctx.enter();
                try {
                    ctx.executeUpdate(sparql, initial);
                    return new Object[] { ComponentVal.ok(null) };
                } finally {
                    ctx.exit();
                }
            } catch (RuntimeException e) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    e.getMessage() == null ? e.toString() : e.getMessage())) };
            }
        };
    }

    // ---- marshalling -------------------------------------------------------

    /**
     * {@code list<binding>} — a {@link ComponentVal} of kind list whose
     * elements are {@code record binding { name, value }}.
     */
    private static BindingSet decodeBindings(final ComponentVal list, final CallbackContext ctx) {
        final MapBindingSet bs = new MapBindingSet();
        for (ComponentVal elem : list.asList()) {
            final Map<String, ComponentVal> fields = elem.asRecord();
            final String name = fields.get("name").asString();
            final Value rdfValue = decodeValue(fields.get("value"), ctx.valueFactory());
            bs.addBinding(name, rdfValue);
        }
        return bs;
    }

    /**
     * {@code variant value { iri(string), literal(literal), bnode(string) }} —
     * decode into an RDF4J Value.
     */
    private static Value decodeValue(final ComponentVal variant, final ValueFactory vf) {
        // Variants come across as ComponentVariant with (caseName, payload).
        final ai.tegmentum.wasmtime4j.component.ComponentVariant cv = variant.asVariant();
        final String caseName = cv.getCaseName();
        final ComponentVal payload = cv.getPayload().orElse(null);

        switch (caseName) {
            case "iri": {
                final String uri = payload == null ? "" : payload.asString();
                return vf.createIRI(uri);
            }
            case "bnode": {
                final String id = payload == null ? "" : payload.asString();
                return vf.createBNode(id);
            }
            case "literal": {
                if (payload == null) {
                    throw new IllegalStateException("wf: literal variant has no payload");
                }
                final Map<String, ComponentVal> fields = payload.asRecord();
                final String label = fields.get("label").asString();
                final String datatype = fields.get("datatype").asString();
                final Optional<ComponentVal> lang = fields.get("lang").asSome();
                if (lang.isPresent()) {
                    return vf.createLiteral(label, lang.get().asString());
                }
                return vf.createLiteral(label, vf.createIRI(datatype));
            }
            default:
                throw new IllegalStateException("wf: unknown value variant case: " + caseName);
        }
    }

    /** {@code option<u32>} — Optional<Integer>. */
    private static Optional<Integer> decodeOptionalU32(final ComponentVal option) {
        return option.asSome().map(v -> (int) v.asU32());
    }

    /**
     * Encode a lazy {@link CloseableIteration} of {@link BindingSet}s as
     * {@code record binding-sets { vars: list<string>, rows: list<list<binding>> }}.
     * Materialises up to {@code rowCap} rows, then drops the rest.
     */
    private static ComponentVal encodeBindingSets(final CloseableIteration<BindingSet> iter,
                                                  final int rowCap) {
        final LinkedHashSet<String> varsSeen = new LinkedHashSet<>();
        final List<ComponentVal> rows = new ArrayList<>();
        int rowsSeen = 0;
        while (iter.hasNext() && rowsSeen < rowCap) {
            final BindingSet row = iter.next();
            varsSeen.addAll(row.getBindingNames());
            final List<ComponentVal> bindings = new ArrayList<>();
            for (String var : row.getBindingNames()) {
                final Value rdfValue = row.getValue(var);
                if (rdfValue == null) continue;
                final Map<String, ComponentVal> bindingFields = new LinkedHashMap<>();
                bindingFields.put("name", ComponentVal.string(var));
                bindingFields.put("value", encodeValue(rdfValue));
                bindings.add(ComponentVal.record(bindingFields));
            }
            rows.add(ComponentVal.list(bindings));
            rowsSeen++;
        }
        final List<ComponentVal> vars = new ArrayList<>(varsSeen.size());
        for (String v : varsSeen) vars.add(ComponentVal.string(v));

        final Map<String, ComponentVal> bindingSets = new LinkedHashMap<>();
        bindingSets.put("vars", ComponentVal.list(vars));
        bindingSets.put("rows", ComponentVal.list(rows));
        return ComponentVal.record(bindingSets);
    }

    /** RDF4J Value → {@code variant value { iri | literal | bnode }}. */
    private static ComponentVal encodeValue(final Value v) {
        if (v instanceof IRI iri) {
            return ComponentVal.variant("iri", ComponentVal.string(iri.stringValue()));
        }
        if (v instanceof BNode bnode) {
            return ComponentVal.variant("bnode", ComponentVal.string(bnode.getID()));
        }
        if (v instanceof Literal lit) {
            final Map<String, ComponentVal> fields = new LinkedHashMap<>();
            fields.put("label", ComponentVal.string(lit.getLabel()));
            final String dt = lit.getDatatype() != null
                    ? lit.getDatatype().stringValue()
                    : XSD.STRING.stringValue();
            fields.put("datatype", ComponentVal.string(dt));
            final Optional<String> lang = lit.getLanguage();
            fields.put("lang", lang.isPresent()
                    ? ComponentVal.some(ComponentVal.string(lang.get()))
                    : ComponentVal.none());
            return ComponentVal.variant("literal", ComponentVal.record(fields));
        }
        throw new IllegalArgumentException("wf: unsupported Value: " + v.getClass().getName());
    }
}

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
     * <p>Substrate-side text-embedding host callback. Deterministic
     * SHA-256-derived stub — bit-for-bit identical output to the earlier
     * Rust-engine stub landing (oxigraph-wf {@code d07c2d6},
     * qlever-wf-runtime {@code register_wf_embed_host_import} stub) so
     * cross-engine parity holds while the substrate does not yet have a
     * JVM-native fastembed backend. A real ONNX-Runtime-Java path can slot
     * in behind the same signature later; the Rust side already ships
     * real fastembed-rs at {@code b9194c5} for the eventual byte-parity
     * pin.
     *
     * <p>Algorithm — matches {@code embed_text_stub} in
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
     * <p>Empty text → {@code Err("embed-text: text is empty")}.
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
                final float[] vec = embedTextStub(text, model);
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

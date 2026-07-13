package ai.tegmentum.rdf4j.webfunctions;

import ai.tegmentum.webassembly4j.api.Component;
import ai.tegmentum.webassembly4j.api.ComponentInstance;
import ai.tegmentum.webassembly4j.api.DefaultLinkingContext;
import ai.tegmentum.webassembly4j.api.Engine;
import ai.tegmentum.wasmtime4j.wit.WitResult;
import ai.tegmentum.wasmtime4j.wit.WitString;
import ai.tegmentum.wasmtime4j.wit.WitU64;
import ai.tegmentum.wasmtime4j.wit.WitValue;

import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLConnection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Component-model WASM instance for the RDF4J binding. Uses a process-wide
 * shared {@link Engine} + per-URL cached {@link Component}; each instance
 * owns a fresh {@link ComponentInstance}. See the Jena binding's analog for
 * the config-freeze caveat.
 */
public final class Rdf4jWasmInstance implements Closeable {

    private static volatile Engine SHARED_ENGINE;
    private static final Object ENGINE_LOCK = new Object();
    private static final ConcurrentHashMap<URL, Component> COMPONENT_CACHE =
            new ConcurrentHashMap<>();
    /**
     * Per-URL cache of the resolved entry-point name once we've seen a
     * given component. Mirrors the component cache, but populated
     * lazily on first successful instantiation instead of at load time
     * (webassembly4j's public {@code Component} API exposes exported
     * <em>interfaces</em>, not exported function names — the function
     * list only surfaces on the {@code ComponentInstance}).
     *
     * <p>Keyed by URL to survive the fact that each invocation builds a
     * fresh {@code ComponentInstance}: once the resolver has picked
     * {@code evaluate} (or {@code search}, or a caller override) for a
     * given URL, subsequent calls short-circuit the enumeration.
     */
    private static final ConcurrentHashMap<URL, String> AUTO_ENTRY_CACHE =
            new ConcurrentHashMap<>();

    private ComponentInstance instance;
    private final URL wasmUrl;
    private boolean closed;

    public Rdf4jWasmInstance(final URL wasmUrl) throws IOException {
        this.wasmUrl = wasmUrl;
        final Component component = componentFor(wasmUrl);
        final DefaultLinkingContext.Builder linker = DefaultLinkingContext.builder();
        // v0.3.0 host callbacks: only bound if enabled by config. Older
        // components (v0.2.0 WIT world) declare no such imports and see the
        // linker's set of registered wit-host-functions ignored — the wasm
        // engine only pulls what the component's imports section names.
        if (WebFunctionConfig.callbackEnabled()) {
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.3.0#execute-query",
                HostCallbacks.executeQuery());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.3.0#callback-depth",
                HostCallbacks.callbackDepth());
            // v0.3.1 additive imports.
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.3.1#execute-query",
                HostCallbacks.executeQuery());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.3.1#callback-depth",
                HostCallbacks.callbackDepth());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.3.1#execute-update",
                HostCallbacks.executeUpdate());
            // v0.3.2 additive imports — prepared queries.
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.3.2#execute-query",
                HostCallbacks.executeQuery());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.3.2#callback-depth",
                HostCallbacks.callbackDepth());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.3.2#execute-update",
                HostCallbacks.executeUpdate());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.3.2#prepare-query",
                HostCallbacks.prepareQuery());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.3.2#run-prepared",
                HostCallbacks.runPrepared());
            // v0.3.3 additive imports — direct triple-pattern lookup.
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.3.3#execute-query",
                HostCallbacks.executeQuery());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.3.3#callback-depth",
                HostCallbacks.callbackDepth());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.3.3#execute-update",
                HostCallbacks.executeUpdate());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.3.3#prepare-query",
                HostCallbacks.prepareQuery());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.3.3#run-prepared",
                HostCallbacks.runPrepared());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.3.3#follow-predicate",
                HostCallbacks.followPredicate());
            // v0.4.0 additive imports — recursive wasm invocation.
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.4.0#execute-query",
                HostCallbacks.executeQuery());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.4.0#callback-depth",
                HostCallbacks.callbackDepth());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.4.0#execute-update",
                HostCallbacks.executeUpdate());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.4.0#prepare-query",
                HostCallbacks.prepareQuery());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.4.0#run-prepared",
                HostCallbacks.runPrepared());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.4.0#follow-predicate",
                HostCallbacks.followPredicate());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.4.0#invoke-wasm",
                HostCallbacks.invokeWasm());
            // v0.5.0 additive imports — sink handles (sqlite/duckdb/…) plus
            // execute-update's simplified one-arg signature. Additive: v0.4
            // guests keep linking against their own registrations above.
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.5.0#execute-query",
                HostCallbacks.executeQuery());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.5.0#callback-depth",
                HostCallbacks.callbackDepth());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.5.0#prepare-query",
                HostCallbacks.prepareQuery());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.5.0#run-prepared",
                HostCallbacks.runPrepared());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.5.0#invoke-wasm",
                HostCallbacks.invokeWasm());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.5.0#execute-update",
                HostCallbacks.executeUpdateV05());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.5.0#sink-open",
                HostCallbacks.sinkOpen());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.5.0#sink-execute",
                HostCallbacks.sinkExecute());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.5.0#sink-close",
                HostCallbacks.sinkClose());
            // v0.6.0 additive imports — execute-query-with-bindings unlocks
            // wf_pipeline v3's typed binding-set propagation: a step's row
            // grid handed to the next SPARQL step as a substrate-native
            // VALUES splice, not stringified into VALUES text. Additive
            // registration: guests targeting v0.3.x .. v0.5.x continue to
            // link against their own interface instance above.
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.6.0#execute-query",
                HostCallbacks.executeQuery());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.6.0#execute-query-with-bindings",
                HostCallbacks.executeQueryWithBindings());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.6.0#callback-depth",
                HostCallbacks.callbackDepth());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.6.0#execute-update",
                HostCallbacks.executeUpdateV05());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.6.0#prepare-query",
                HostCallbacks.prepareQuery());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.6.0#run-prepared",
                HostCallbacks.runPrepared());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.6.0#follow-predicate",
                HostCallbacks.followPredicate());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.6.0#invoke-wasm",
                HostCallbacks.invokeWasm());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.6.0#sink-open",
                HostCallbacks.sinkOpen());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.6.0#sink-execute",
                HostCallbacks.sinkExecute());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.6.0#sink-close",
                HostCallbacks.sinkClose());
        }
        // wf:fulltext/host@0.1.0 — one import, `http-post-json`. The
        // wf_fulltext guest declares its own WIT world (versioned under
        // wf:fulltext, not stardog:webfunction), so this binds independently
        // of the callback-enabled flag: the flag gates re-entry into the
        // outer graph, this import reaches an external fulltext backend.
        // Guests that never import wf:fulltext see no change in behaviour —
        // the wasm engine only pulls what a component's imports section
        // names.
        linker.addWitHostFunction(
            "wf:fulltext/host@0.1.0#http-post-json",
            HostCallbacks.httpPostJson());
        this.instance = component.instantiate(linker.build());
    }

    private static Engine sharedEngine() {
        Engine e = SHARED_ENGINE;
        if (e != null) return e;
        synchronized (ENGINE_LOCK) {
            if (SHARED_ENGINE == null) {
                final Engine built = WebFunctionConfig.buildEngine();
                if (!built.capabilities().supportsComponents()) {
                    built.close();
                    throw new IllegalStateException("engine '"
                            + built.info().engineId() + "' does not support components");
                }
                SHARED_ENGINE = built;
            }
            return SHARED_ENGINE;
        }
    }

    private static Component componentFor(final URL wasmUrl) throws IOException {
        Component cached = COMPONENT_CACHE.get(wasmUrl);
        if (cached != null) return cached;
        final Engine engine = sharedEngine();
        try {
            return COMPONENT_CACHE.computeIfAbsent(wasmUrl, u -> {
                try {
                    return engine.loadComponent(readAll(u));
                } catch (IOException ioe) {
                    throw new UncheckedIOException(ioe);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    public List<WitValueMarshaller.Row> evaluate(final ValueFactory vf, final Value... args) throws IOException {
        return invokeEntry(null, vf, args);
    }

    /**
     * Invoke a specific export by name, or auto-detect one via
     * {@link EntryPointResolver} when {@code entryPointOverride} is
     * {@code null}. See the class Javadoc on {@link EntryPointResolver}
     * for the resolution order.
     *
     * <p>Same shape as {@link #evaluate(ValueFactory, Value...)} — the
     * caller path used by {@link WfInvokeService} when an
     * {@code InvokeSpec.entryPoint} override was supplied at
     * {@code wf:partial} rewrite time.
     */
    public List<WitValueMarshaller.Row> invokeEntry(final String entryPointOverride,
                                                    final ValueFactory vf,
                                                    final Value... args) throws IOException {
        final String entry = resolveEntryPoint(entryPointOverride);
        final WitValue result = (WitValue) instance.invokeWit(
                entry, WitValueMarshaller.toWitArgs(args));
        return WitValueMarshaller.bindingSetsFromWit(unwrapOk(result), vf);
    }

    /**
     * Enumerate the underlying component's exported top-level function
     * names. Handy for callers that want to inspect a guest's contract
     * before invoking (e.g. the substrate resolver, tests).
     */
    public List<String> exportedFunctions() {
        return instance.exportedFunctions();
    }

    private String resolveEntryPoint(final String override) {
        if (override != null && !override.isEmpty()) {
            return override;
        }
        // Cache the auto-detected name per URL; the enumeration is cheap
        // but there's no reason to repeat it every wf:partial dispatch.
        final String cached = AUTO_ENTRY_CACHE.get(wasmUrl);
        if (cached != null) return cached;
        final String resolved = EntryPointResolver.resolve(instance.exportedFunctions(), null);
        AUTO_ENTRY_CACHE.putIfAbsent(wasmUrl, resolved);
        return resolved;
    }

    public void aggregateStep(final Value[] args, final long multiplicity) throws IOException {
        final WitValue result = (WitValue) instance.invokeWit(
                "aggregate-step",
                WitValueMarshaller.toWitArgs(args),
                WitU64.of(multiplicity));
        unwrapOk(result);
    }

    public List<WitValueMarshaller.Row> aggregateFinish(final ValueFactory vf) throws IOException {
        final WitValue result = (WitValue) instance.invokeWit("aggregate-finish");
        return WitValueMarshaller.bindingSetsFromWit(unwrapOk(result), vf);
    }

    public List<WitValueMarshaller.Row> doc(final ValueFactory vf) {
        final WitValue result = (WitValue) instance.invokeWit("doc");
        return WitValueMarshaller.bindingSetsFromWit(result, vf);
    }

    private static WitValue unwrapOk(final WitValue result) throws IOException {
        if (!(result instanceof WitResult wr)) {
            throw new IOException("Unexpected component return type: "
                    + (result == null ? "null" : result.getClass().getName()));
        }
        if (wr.isErr()) {
            throw new IOException(wr.getErr()
                    .map(v -> ((WitString) v).getValue())
                    .orElse("component returned err with no payload"));
        }
        return wr.getOk().orElse(null);
    }

    private static byte[] readAll(final URL url) throws IOException {
        final URLConnection conn = url.openConnection();
        conn.setConnectTimeout(240000);
        conn.setReadTimeout(240000);
        conn.connect();
        try (java.io.InputStream in = conn.getInputStream();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            in.transferTo(out);
            return out.toByteArray();
        }
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        instance = null;
        closed = true;
    }

    /** Purge cached components + close shared engine. Test-only. */
    static void resetCache() {
        COMPONENT_CACHE.forEach((k, c) -> c.close());
        COMPONENT_CACHE.clear();
        AUTO_ENTRY_CACHE.clear();
        synchronized (ENGINE_LOCK) {
            if (SHARED_ENGINE != null) {
                SHARED_ENGINE.close();
                SHARED_ENGINE = null;
            }
        }
    }
}

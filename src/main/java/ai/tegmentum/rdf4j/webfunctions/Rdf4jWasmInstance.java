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
        // wf:document/host@1.3.0 — same shape (`http-post-json(url, body)
        // -> result<string, string>`) as wf:fulltext's, but under a
        // separately-versioned interface for the wf_document guest. The
        // guest uses it both for Manticore search calls and for Sirix
        // storage-backend fetches, so a document-mode SERVICE dispatch
        // needs this linker binding to instantiate at all. Additive —
        // guests that never import wf:document see no change in behaviour.
        linker.addWitHostFunction(
            "wf:document/host@1.3.0#http-post-json",
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
        // Marshal callsite args to whatever the export actually wants.
        // Legacy `evaluate(list<value>)` guests still take a single packed
        // WitList; multi-param guests like wf_fulltext's
        // `search(backend-url: string, index: string, query: string,
        // opts: query-opts)` need N typed positional values coerced from
        // the callsite Value[] per the introspected WIT signature. See
        // {@code marshalTypedArgs} for the coercion policy.
        final Object[] callArgs = marshalTypedArgs(entry, args);
        final WitValue result = (WitValue) instance.invokeWit(entry, callArgs);
        return WitValueMarshaller.bindingSetsFromWit(unwrapOk(result), vf);
    }

    /**
     * Look up the resolved entry-point's WIT signature via the wasmtime4j
     * component-type introspection API and marshal the callsite Values to
     * whatever positional shape the guest actually declared.
     *
     * <p>Two shapes are recognised:
     *
     * <ol>
     *   <li>The historical {@code evaluate(list<value>)} — one parameter
     *       whose type is a list. The Values are packed into a single
     *       {@link ai.tegmentum.wasmtime4j.wit.WitList} and returned as
     *       a length-1 {@code Object[]}, matching the current call
     *       convention.</li>
     *   <li>N typed positional parameters (wf_fulltext's {@code search}
     *       is the motivating case). Each Value's lexical form is
     *       extracted and coerced to the target WIT primitive;
     *       record / option / list targets parse the lexical form as
     *       JSON.</li>
     * </ol>
     *
     * <p>Best-effort: when the provider doesn't expose typed export
     * items, we fall through to the packed shape. Guests that need
     * multi-arg dispatch use the wasmtime4j provider today, which does
     * expose typed items via {@code JniComponentImpl.componentType}.
     * Mirrors {@code JenaWasmInstance.marshalTypedArgs} in the sibling
     * plugin.
     */
    private Object[] marshalTypedArgs(final String entry, final Value[] args) throws IOException {
        final java.util.Optional<ai.tegmentum.wasmtime4j.component.ComponentItemInfo.ComponentFuncInfo> info =
                lookupFuncInfo(entry);
        if (!info.isPresent()) {
            return new Object[] { WitValueMarshaller.toWitArgs(args) };
        }
        final List<ai.tegmentum.wasmtime4j.component.ComponentItemInfo.NamedType> params =
                info.get().params();
        // Historical `list<value>` shape: one param, list-typed.
        if (params.size() == 1
                && params.get(0).type().getType()
                        == ai.tegmentum.wasmtime4j.component.ComponentType.LIST) {
            return new Object[] { WitValueMarshaller.toWitArgs(args) };
        }
        // Positional-limit fold: mirrors oxigraph-wf's
        // `maybe_fold_positional_limit` and Jena's counterpart. An
        // explicit `wf:partial(<WASM>, ..., <limit>, <opts_json>)`
        // callsite ships one more positional arg than a guest whose
        // trailing param is an opts record where `limit` lives inside
        // that record (wf_document `search-opts`). Merge the positional
        // into the trailing JSON before validating arg counts so both
        // callsite shapes reach the guest with the same 5-arg vector.
        final Value[] foldedArgs = maybeFoldPositionalLimit(params, args);
        if (params.size() != foldedArgs.length) {
            throw new IOException("arg count mismatch for `" + entry + "`: guest expects "
                    + params.size() + " positional params, callsite supplied " + foldedArgs.length);
        }
        final Object[] out = new Object[params.size()];
        for (int i = 0; i < params.size(); i++) {
            try {
                out[i] = coerceValueToWit(foldedArgs[i], params.get(i).type());
            } catch (RuntimeException e) {
                throw new IOException("arg " + i + " of `" + entry + "`: " + e.getMessage(), e);
            }
        }
        return out;
    }

    /**
     * Detect the `wf:partial(..., <limit>, <opts_json>)` callsite that
     * supplies one more positional arg than the guest's typed signature
     * declares, and fold the extra scalar into the trailing opts record
     * so the marshaller can proceed with the guest's canonical shape.
     * Mirrors {@code oxigraph-wf/src/wf_call.rs::maybe_fold_positional_limit}
     * and Jena's {@code JenaWasmInstance.maybeFoldPositionalLimit}.
     */
    static Value[] maybeFoldPositionalLimit(
            final List<ai.tegmentum.wasmtime4j.component.ComponentItemInfo.NamedType> params,
            final Value[] args) {
        if (args.length != params.size() + 1) {
            return args;
        }
        if (params.isEmpty()) {
            return args;
        }
        final ai.tegmentum.wasmtime4j.component.ComponentTypeDescriptor lastParam =
                params.get(params.size() - 1).type();
        if (lastParam.getType() != ai.tegmentum.wasmtime4j.component.ComponentType.RECORD) {
            return args;
        }
        final java.util.Map<String, ai.tegmentum.wasmtime4j.component.ComponentTypeDescriptor> fields =
                lastParam.getRecordFields();
        final ai.tegmentum.wasmtime4j.component.ComponentTypeDescriptor limitField = fields.get("limit");
        if (limitField == null || !isNumericOrOptionNumeric(limitField)) {
            return args;
        }
        final int limitIdx = args.length - 2;
        final int optsIdx = args.length - 1;
        final long limitVal;
        try {
            limitVal = Long.parseLong(lexicalOf(args[limitIdx]));
        } catch (IllegalArgumentException e) {
            // Covers both NumberFormatException (parse failure) and
            // IllegalArgumentException from lexicalOf for unsupported
            // Value kinds — decline the fold, let the honest arg-count
            // error surface downstream.
            return args;
        }
        final String optsLex;
        try {
            optsLex = lexicalOf(args[optsIdx]);
        } catch (IllegalArgumentException e) {
            return args;
        }
        final String merged = mergePositionalLimitIntoOptsJson(optsLex, limitVal);
        if (merged == null) {
            return args;
        }
        final org.eclipse.rdf4j.model.impl.SimpleValueFactory vf =
                org.eclipse.rdf4j.model.impl.SimpleValueFactory.getInstance();
        final Value[] out = new Value[params.size()];
        for (int i = 0, w = 0; i < args.length; i++) {
            if (i == limitIdx) {
                continue;
            }
            if (i == optsIdx) {
                out[w++] = vf.createLiteral(merged);
            } else {
                out[w++] = args[i];
            }
        }
        return out;
    }

    private static boolean isNumericOrOptionNumeric(
            final ai.tegmentum.wasmtime4j.component.ComponentTypeDescriptor d) {
        final ai.tegmentum.wasmtime4j.component.ComponentType kind = d.getType();
        if (kind == ai.tegmentum.wasmtime4j.component.ComponentType.OPTION) {
            return isNumericOrOptionNumeric(d.getOptionType());
        }
        switch (kind) {
            case S8: case U8: case S16: case U16:
            case S32: case U32: case S64: case U64:
                return true;
            default:
                return false;
        }
    }

    /**
     * Pure JSON merge: parse {@code optsLex} as an object, insert
     * {@code "limit": <value>} if not already present, re-serialize.
     * Returns {@code null} when the input isn't a JSON object or the
     * re-serialize fails. An explicit `limit` key in the opts blob
     * wins over the positional (matches the URL-sugar path's
     * `or_insert_with` semantics). Split out for unit-testability.
     * Uses Jackson (a transitive dep of RDF4J via jsonld-java) so we
     * don't take a new dependency for a two-line parse.
     */
    static String mergePositionalLimitIntoOptsJson(final String optsLex, final long limit) {
        try {
            final tools.jackson.databind.json.JsonMapper mapper =
                    tools.jackson.databind.json.JsonMapper.builder().build();
            final tools.jackson.databind.JsonNode node = mapper.readTree(optsLex);
            if (node == null || !node.isObject()) {
                return null;
            }
            final tools.jackson.databind.node.ObjectNode obj =
                    (tools.jackson.databind.node.ObjectNode) node;
            if (!obj.has("limit")) {
                obj.put("limit", limit);
            }
            return mapper.writeValueAsString(obj);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private java.util.Optional<ai.tegmentum.wasmtime4j.component.ComponentItemInfo.ComponentFuncInfo>
            lookupFuncInfo(final String exportName) {
        final java.util.Optional<ai.tegmentum.wasmtime4j.component.ComponentInstance> nativeInstance =
                instance.unwrap(ai.tegmentum.wasmtime4j.component.ComponentInstance.class);
        if (!nativeInstance.isPresent()) return java.util.Optional.empty();
        try {
            final ai.tegmentum.wasmtime4j.component.ComponentTypeInfo typeInfo =
                    nativeInstance.get().getComponent().componentType();
            final java.util.Optional<ai.tegmentum.wasmtime4j.component.ComponentItemInfo> item =
                    typeInfo.getExportItem(exportName);
            if (!item.isPresent()) return java.util.Optional.empty();
            if (item.get() instanceof ai.tegmentum.wasmtime4j.component.ComponentItemInfo.ComponentFuncInfo funcInfo) {
                return java.util.Optional.of(funcInfo);
            }
            return java.util.Optional.empty();
        } catch (ai.tegmentum.wasmtime4j.exception.WasmException e) {
            return java.util.Optional.empty();
        }
    }

    private static Object coerceValueToWit(
            final Value value,
            final ai.tegmentum.wasmtime4j.component.ComponentTypeDescriptor target) {
        final String lex = lexicalOf(value);
        return coerceLexicalToWit(lex, target);
    }

    private static String lexicalOf(final Value value) {
        if (value.isIRI()) return value.stringValue();
        if (value.isBNode()) return value.stringValue();
        if (value.isLiteral()) return ((org.eclipse.rdf4j.model.Literal) value).getLabel();
        throw new IllegalArgumentException("unsupported Value kind for typed-arg marshalling: " + value);
    }

    private static Object coerceLexicalToWit(
            final String lex,
            final ai.tegmentum.wasmtime4j.component.ComponentTypeDescriptor target) {
        final ai.tegmentum.wasmtime4j.component.ComponentType kind = target.getType();
        switch (kind) {
            case STRING:
                return witStringUnchecked(lex);
            case BOOL:
                return ai.tegmentum.wasmtime4j.wit.WitBool.of(Boolean.parseBoolean(lex));
            case S8:  return ai.tegmentum.wasmtime4j.wit.WitS8.of(Byte.parseByte(lex));
            case U8:  return ai.tegmentum.wasmtime4j.wit.WitU8.of(Byte.parseByte(lex));
            case S16: return ai.tegmentum.wasmtime4j.wit.WitS16.of(Short.parseShort(lex));
            case U16: return ai.tegmentum.wasmtime4j.wit.WitU16.of(Short.parseShort(lex));
            case S32: return ai.tegmentum.wasmtime4j.wit.WitS32.of(Integer.parseInt(lex));
            case U32: return ai.tegmentum.wasmtime4j.wit.WitU32.of(Integer.parseInt(lex));
            case S64: return ai.tegmentum.wasmtime4j.wit.WitS64.of(Long.parseLong(lex));
            case U64: return ai.tegmentum.wasmtime4j.wit.WitU64.of(Long.parseLong(lex));
            case F32: return ai.tegmentum.wasmtime4j.wit.WitFloat32.of(Float.parseFloat(lex));
            case F64: return ai.tegmentum.wasmtime4j.wit.WitFloat64.of(Double.parseDouble(lex));
            case CHAR: {
                if (lex.length() != 1) {
                    throw new IllegalArgumentException("char: expected exactly one code unit, got `" + lex + "`");
                }
                try {
                    return ai.tegmentum.wasmtime4j.wit.WitChar.of(lex.charAt(0));
                } catch (ai.tegmentum.wasmtime4j.exception.ValidationException e) {
                    throw new IllegalArgumentException("char: invalid code point in `" + lex + "`", e);
                }
            }
            default: {
                // Non-primitive: parse lexical form as JSON and rebuild.
                final com.google.gson.JsonElement json = parseJson(lex);
                return jsonToWit(json, target);
            }
        }
    }

    private static ai.tegmentum.wasmtime4j.wit.WitString witStringUnchecked(final String s) {
        try {
            return ai.tegmentum.wasmtime4j.wit.WitString.of(s);
        } catch (ai.tegmentum.wasmtime4j.exception.ValidationException e) {
            throw new IllegalArgumentException("invalid UTF-8 for WIT string: " + s, e);
        }
    }

    /**
     * Parse the lexical form as JSON via Gson — transitively available
     * from rdf4j-jsonld's dependency graph, no extra codec added.
     */
    private static com.google.gson.JsonElement parseJson(final String lex) {
        try {
            return com.google.gson.JsonParser.parseString(lex);
        } catch (com.google.gson.JsonSyntaxException e) {
            throw new IllegalArgumentException("expected JSON payload, got `" + lex + "`: " + e.getMessage(), e);
        }
    }

    private static ai.tegmentum.wasmtime4j.wit.WitValue jsonToWit(
            final com.google.gson.JsonElement json,
            final ai.tegmentum.wasmtime4j.component.ComponentTypeDescriptor target) {
        final ai.tegmentum.wasmtime4j.component.ComponentType kind = target.getType();
        switch (kind) {
            case BOOL:
                return ai.tegmentum.wasmtime4j.wit.WitBool.of(json.getAsBoolean());
            case S8:  return ai.tegmentum.wasmtime4j.wit.WitS8.of((byte) json.getAsInt());
            case U8:  return ai.tegmentum.wasmtime4j.wit.WitU8.of((byte) (json.getAsInt() & 0xff));
            case S16: return ai.tegmentum.wasmtime4j.wit.WitS16.of((short) json.getAsInt());
            case U16: return ai.tegmentum.wasmtime4j.wit.WitU16.of((short) (json.getAsInt() & 0xffff));
            case S32: return ai.tegmentum.wasmtime4j.wit.WitS32.of(json.getAsInt());
            case U32: return ai.tegmentum.wasmtime4j.wit.WitU32.of(json.getAsInt());
            case S64: return ai.tegmentum.wasmtime4j.wit.WitS64.of(json.getAsLong());
            case U64: return ai.tegmentum.wasmtime4j.wit.WitU64.of(json.getAsLong());
            case F32: return ai.tegmentum.wasmtime4j.wit.WitFloat32.of(json.getAsFloat());
            case F64: return ai.tegmentum.wasmtime4j.wit.WitFloat64.of(json.getAsDouble());
            case STRING: return witStringUnchecked(json.getAsString());
            case OPTION: {
                final ai.tegmentum.wasmtime4j.component.ComponentTypeDescriptor inner =
                        target.getOptionType();
                // WitOption.some/none demand the OPTION-wrapped WitType, not the
                // inner type — passing the inner triggers extractInnerType's
                // "Type must be an option type" guard. `witTypeOf(target)` wraps
                // `option<inner>` correctly (see the OPTION case below).
                final ai.tegmentum.wasmtime4j.wit.WitType optionTy = witTypeOf(target);
                if (json.isJsonNull()) {
                    return ai.tegmentum.wasmtime4j.wit.WitOption.none(optionTy);
                }
                return ai.tegmentum.wasmtime4j.wit.WitOption.some(
                        optionTy,
                        jsonToWit(json, inner));
            }
            case LIST: {
                final ai.tegmentum.wasmtime4j.component.ComponentTypeDescriptor elem =
                        target.getElementType();
                final com.google.gson.JsonArray arr = json.getAsJsonArray();
                if (arr.isEmpty()) {
                    return ai.tegmentum.wasmtime4j.wit.WitList.empty(witTypeOf(elem));
                }
                final java.util.List<ai.tegmentum.wasmtime4j.wit.WitValue> out =
                        new java.util.ArrayList<>(arr.size());
                for (com.google.gson.JsonElement item : arr) out.add(jsonToWit(item, elem));
                return ai.tegmentum.wasmtime4j.wit.WitList.of(out);
            }
            case RECORD: {
                // Accommodate a bare-scalar lexical form (e.g.
                // `wf:partial(..., "waterproof", 10)` where the guest's
                // 4th param is a query-opts record with `limit: int`,
                // `fields: list<string>`, and `highlight: bool`).
                // Policy mirrors the Oxigraph dispatcher
                // (oxigraph-wf/src/wf_call.rs::json_to_val) and the
                // Jena runner (JenaWasmInstance#jsonToWit):
                //
                //   * If exactly one non-optional field's type accepts
                //     the bare scalar's shape (int → int, bool → bool,
                //     string → string, array → list<_>) we slot it there
                //     and default-synthesize the other non-optional
                //     fields (empty list, false, "", 0/0.0). Option
                //     fields default to None as before.
                //   * If more than one field matches, we throw with
                //     both candidates named.
                //   * A missing non-optional field on an explicit
                //     JSON-object call path still throws — synthesis
                //     only fires on the bare-arg wrap path.
                final java.util.Map<String, ai.tegmentum.wasmtime4j.component.ComponentTypeDescriptor> fields =
                        target.getRecordFields();
                final com.google.gson.JsonObject obj;
                final boolean synthMissing;
                if (json.isJsonObject()) {
                    obj = json.getAsJsonObject();
                    synthMissing = false;
                } else if (json.isJsonNull()) {
                    throw new IllegalArgumentException("expected JSON object, got null");
                } else {
                    final String placed = placeBareArgIntoRecord(json, fields);
                    obj = new com.google.gson.JsonObject();
                    obj.add(placed, json);
                    synthMissing = true;
                }
                final ai.tegmentum.wasmtime4j.wit.WitRecord.Builder b =
                        ai.tegmentum.wasmtime4j.wit.WitRecord.builder();
                for (java.util.Map.Entry<String, ai.tegmentum.wasmtime4j.component.ComponentTypeDescriptor> e : fields.entrySet()) {
                    final String name = e.getKey();
                    final ai.tegmentum.wasmtime4j.component.ComponentTypeDescriptor fieldTy = e.getValue();
                    final com.google.gson.JsonElement fieldJson = obj.get(name);
                    if ((fieldJson == null || fieldJson.isJsonNull())
                            && fieldTy.getType() == ai.tegmentum.wasmtime4j.component.ComponentType.OPTION) {
                        // Pass the OPTION-wrapped type, not the inner type — same
                        // reason as the OPTION branch above.
                        b.field(name, ai.tegmentum.wasmtime4j.wit.WitOption.none(
                                witTypeOf(fieldTy)));
                    } else if (fieldJson == null && synthMissing) {
                        b.field(name, defaultValFor(fieldTy));
                    } else if (fieldJson == null) {
                        throw new IllegalArgumentException(
                                "record missing required field `" + name + "`");
                    } else {
                        b.field(name, jsonToWit(fieldJson, fieldTy));
                    }
                }
                return b.build();
            }
            default:
                throw new IllegalArgumentException(
                        "no JSON→WIT coercion implemented for target kind " + kind);
        }
    }

    /**
     * Bare-arg placement into a record — pick the sole non-optional
     * field whose type accepts the bare JSON scalar (by shape: int →
     * int/float, bool → bool, string → string, array → list). Zero or
     * multiple matches throw with a specific message so operators see
     * which candidates were considered. Kept as a static helper so
     * {@link RecordCoercionPolicy} tests exercise it without needing a
     * live component instance.
     */
    static String placeBareArgIntoRecord(
            final com.google.gson.JsonElement bare,
            final java.util.Map<String, ai.tegmentum.wasmtime4j.component.ComponentTypeDescriptor> fields) {
        final RecordCoercionPolicy.JsonShape scalar = RecordCoercionPolicy.jsonShapeOf(bare);
        final java.util.List<java.util.Map.Entry<String, ai.tegmentum.wasmtime4j.component.ComponentTypeDescriptor>> nonOptional =
                new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, ai.tegmentum.wasmtime4j.component.ComponentTypeDescriptor> e : fields.entrySet()) {
            if (e.getValue().getType() != ai.tegmentum.wasmtime4j.component.ComponentType.OPTION) {
                nonOptional.add(e);
            }
        }
        final java.util.List<String> candidates = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, ai.tegmentum.wasmtime4j.component.ComponentTypeDescriptor> e : nonOptional) {
            if (RecordCoercionPolicy.shapeAccepts(
                    RecordCoercionPolicy.fieldShapeOf(e.getValue().getType()), scalar)) {
                candidates.add(e.getKey());
            }
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        if (candidates.isEmpty()) {
            final java.util.List<String> nonOptNames = new java.util.ArrayList<>();
            for (java.util.Map.Entry<String, ai.tegmentum.wasmtime4j.component.ComponentTypeDescriptor> e : nonOptional) {
                nonOptNames.add(e.getKey() + ": " + e.getValue().getType().name().toLowerCase(java.util.Locale.ROOT));
            }
            throw new IllegalArgumentException(
                    "bare " + scalar + " does not match any non-optional field of record "
                    + "(non-optional fields: [" + String.join(", ", nonOptNames) + "])");
        }
        throw new IllegalArgumentException(
                "bare arg is ambiguous - matches multiple non-optional fields ("
                + String.join(", ", candidates) + "); pass an explicit JSON object to disambiguate");
    }

    /**
     * Default-synth a WitValue for a target descriptor. Empty list,
     * false, "", 0/0.0. Records / tuples / variants are not
     * synthesized — those need a callsite-provided value.
     */
    static ai.tegmentum.wasmtime4j.wit.WitValue defaultValFor(
            final ai.tegmentum.wasmtime4j.component.ComponentTypeDescriptor ty) {
        final ai.tegmentum.wasmtime4j.component.ComponentType kind = ty.getType();
        switch (kind) {
            case BOOL: return ai.tegmentum.wasmtime4j.wit.WitBool.of(false);
            case S8:  return ai.tegmentum.wasmtime4j.wit.WitS8.of((byte) 0);
            case U8:  return ai.tegmentum.wasmtime4j.wit.WitU8.of((byte) 0);
            case S16: return ai.tegmentum.wasmtime4j.wit.WitS16.of((short) 0);
            case U16: return ai.tegmentum.wasmtime4j.wit.WitU16.of((short) 0);
            case S32: return ai.tegmentum.wasmtime4j.wit.WitS32.of(0);
            case U32: return ai.tegmentum.wasmtime4j.wit.WitU32.of(0);
            case S64: return ai.tegmentum.wasmtime4j.wit.WitS64.of(0L);
            case U64: return ai.tegmentum.wasmtime4j.wit.WitU64.of(0L);
            case F32: return ai.tegmentum.wasmtime4j.wit.WitFloat32.of(0.0f);
            case F64: return ai.tegmentum.wasmtime4j.wit.WitFloat64.of(0.0);
            case STRING: return witStringUnchecked("");
            case LIST:
                return ai.tegmentum.wasmtime4j.wit.WitList.empty(witTypeOf(ty.getElementType()));
            case OPTION:
                // Pass the OPTION-wrapped type, not the inner type — see
                // jsonToWit's OPTION branch for the same reasoning.
                return ai.tegmentum.wasmtime4j.wit.WitOption.none(witTypeOf(ty));
            default:
                throw new IllegalArgumentException(
                        "no default-synth value for target kind " + kind
                        + " - bare-arg record coercion cannot fill this field automatically");
        }
    }

    private static ai.tegmentum.wasmtime4j.wit.WitType witTypeOf(
            final ai.tegmentum.wasmtime4j.component.ComponentTypeDescriptor d) {
        final ai.tegmentum.wasmtime4j.component.ComponentType kind = d.getType();
        switch (kind) {
            case BOOL: return ai.tegmentum.wasmtime4j.wit.WitType.createBool();
            case S8: return ai.tegmentum.wasmtime4j.wit.WitType.createS8();
            case U8: return ai.tegmentum.wasmtime4j.wit.WitType.createU8();
            case S16: return ai.tegmentum.wasmtime4j.wit.WitType.createS16();
            case U16: return ai.tegmentum.wasmtime4j.wit.WitType.createU16();
            case S32: return ai.tegmentum.wasmtime4j.wit.WitType.createS32();
            case U32: return ai.tegmentum.wasmtime4j.wit.WitType.createU32();
            case S64: return ai.tegmentum.wasmtime4j.wit.WitType.createS64();
            case U64: return ai.tegmentum.wasmtime4j.wit.WitType.createU64();
            case F32: return ai.tegmentum.wasmtime4j.wit.WitType.createFloat32();
            case F64: return ai.tegmentum.wasmtime4j.wit.WitType.createFloat64();
            case CHAR: return ai.tegmentum.wasmtime4j.wit.WitType.createChar();
            case STRING: return ai.tegmentum.wasmtime4j.wit.WitType.createString();
            case OPTION:
                return ai.tegmentum.wasmtime4j.wit.WitType.option(witTypeOf(d.getOptionType()));
            case LIST:
                return ai.tegmentum.wasmtime4j.wit.WitType.list(witTypeOf(d.getElementType()));
            case RECORD: {
                final java.util.Map<String, ai.tegmentum.wasmtime4j.component.ComponentTypeDescriptor> fields =
                        d.getRecordFields();
                final java.util.LinkedHashMap<String, ai.tegmentum.wasmtime4j.wit.WitType> out =
                        new java.util.LinkedHashMap<>();
                for (java.util.Map.Entry<String, ai.tegmentum.wasmtime4j.component.ComponentTypeDescriptor> e : fields.entrySet()) {
                    out.put(e.getKey(), witTypeOf(e.getValue()));
                }
                final String name = d.getName().orElse("record");
                return ai.tegmentum.wasmtime4j.wit.WitType.record(name, out);
            }
            default:
                throw new IllegalArgumentException(
                        "no WitType equivalent implemented for component-type kind " + kind);
        }
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

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

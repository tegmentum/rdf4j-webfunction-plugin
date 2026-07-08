package ai.tegmentum.rdf4j.webfunctions;

import ai.tegmentum.webassembly4j.api.WitHostFunction;

import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.query.impl.MapBindingSet;

import java.util.ArrayList;
import java.util.List;

/**
 * Host callbacks satisfying the v0.3.0 WIT world's {@code host} import interface:
 * {@code stardog:webfunction/host@0.3.0#execute-query} and
 * {@code #callback-depth}. Each is packaged as a {@link WitHostFunction}
 * ({@code Object[] execute(Object[])}) so it can be registered on
 * {@code DefaultLinkingContext.builder().addWitHostFunction(name, fn)}.
 *
 * <p>Input/output marshalling in this class works against boxed Java types
 * that mirror the WIT ADTs:
 *   <ul>
 *     <li>{@code string} → {@link String}
 *     <li>{@code u32} → {@link Integer}, {@code u64} → {@link Long}
 *     <li>{@code option<T>} → nullable {@code T}
 *     <li>{@code list<T>} → {@link List}{@code <T>}
 *     <li>{@code record}/{@code variant} → nested {@link Object}{@code []}
 *     <li>{@code result<T, E>} → {@code Object[]} with a boolean discriminant
 *   </ul>
 *
 * <p>The specific Object shapes wasmtime4j hands to and expects from
 * WitHostFunction implementations are still being verified end-to-end — the
 * boilerplate here is a scaffold; running components against it and adjusting
 * marshalling is the next concrete integration step.
 *
 * <p>Threading: both callbacks read from {@link CallbackContext#current()},
 * which the {@link Rdf4jWasmInstance} evaluator binds before instantiating
 * the component and unbinds in a finally block.
 */
public final class HostCallbacks {

    private HostCallbacks() {}

    /** {@code execute-query: func(sparql: string, bindings: list<binding>,
     *  max-rows: option<u32>) -> result<binding-sets, string>}. */
    public static WitHostFunction executeQuery() {
        return args -> {
            if (!WebFunctionConfig.callbackEnabled()) {
                return errorResult("wf callback disabled by webfunctions.callback.enabled=false");
            }
            try {
                final String sparql = (String) args[0];
                final BindingSet initial = decodeBindings(args[1]);
                final int rowCap = decodeOptionalU32(args[2])
                        .orElseGet(CallbackContext.current()::maxRows);

                final CallbackContext ctx = CallbackContext.current();
                ctx.enter();
                try (TupleQueryResult result = ctx.executeSelect(sparql, initial)) {
                    return successResult(encodeResult(result, rowCap));
                } finally {
                    ctx.exit();
                }
            } catch (RuntimeException e) {
                return errorResult(e.getMessage() == null ? e.toString() : e.getMessage());
            }
        };
    }

    /** {@code callback-depth: func() -> u32}. */
    public static WitHostFunction callbackDepth() {
        return args -> new Object[] { CallbackContext.current().depth() };
    }

    // ---- marshalling helpers -------------------------------------------------
    //
    // The exact runtime shapes of Object arrays wasmtime4j hands us for lists
    // of records and option/result ADTs are still being sanity-checked end-to-
    // end. The methods below encode our best current understanding; they will
    // very likely need small adjustments once a real component-invocation
    // round trip is exercised.

    /**
     * {@code list<binding>} is a Java {@link List} of Object[] rows; each row
     * has slot 0 = name (string), slot 1 = value (variant of iri/literal/bnode
     * per {@code WitValueMarshaller.VALUE_TYPE}).
     */
    @SuppressWarnings("unchecked")
    private static BindingSet decodeBindings(final Object raw) {
        final MapBindingSet bs = new MapBindingSet();
        if (raw == null) return bs;
        final List<Object[]> entries = (List<Object[]>) raw;
        for (Object[] entry : entries) {
            final String name = (String) entry[0];
            final Value rdfValue = WitValueMarshaller.valueFromWit(
                    (ai.tegmentum.wasmtime4j.wit.WitValue) entry[1],
                    CallbackContext.current().valueFactory());
            bs.addBinding(name, rdfValue);
        }
        return bs;
    }

    private static java.util.OptionalInt decodeOptionalU32(final Object raw) {
        if (raw == null) return java.util.OptionalInt.empty();
        if (raw instanceof Integer i) return java.util.OptionalInt.of(i);
        if (raw instanceof Long l) return java.util.OptionalInt.of(l.intValue());
        return java.util.OptionalInt.empty();
    }

    /**
     * Encode a {@link TupleQueryResult} as a WIT {@code binding-sets} record
     * (Object[]): slot 0 = vars (list of strings), slot 1 = rows
     * (list-of-list-of-Object[] bindings).
     */
    private static Object[] encodeResult(final TupleQueryResult result, final int rowCap) {
        final List<String> vars = result.getBindingNames();
        final List<Object> rows = new ArrayList<>();
        int rowsSeen = 0;
        while (result.hasNext() && rowsSeen < rowCap) {
            final BindingSet row = result.next();
            final List<Object[]> encoded = new ArrayList<>();
            for (String var : vars) {
                if (!row.hasBinding(var)) continue;
                final Value rdfValue = row.getValue(var);
                final Object witValue = WitValueMarshaller.toWit(rdfValue);
                encoded.add(new Object[] { var, witValue });
            }
            rows.add(encoded);
            rowsSeen++;
        }
        return new Object[] { vars, rows };
    }

    /** Encode {@code result::ok(T)} — WIT discriminant + payload. */
    private static Object[] successResult(final Object payload) {
        return new Object[] { Boolean.TRUE, payload };
    }

    /** Encode {@code result::err(string)}. */
    private static Object[] errorResult(final String message) {
        return new Object[] { Boolean.FALSE, message };
    }
}

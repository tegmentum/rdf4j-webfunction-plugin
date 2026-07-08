package ai.tegmentum.rdf4j.webfunctions;

import ai.tegmentum.wasmtime4j.wit.WitList;
import ai.tegmentum.wasmtime4j.wit.WitOption;
import ai.tegmentum.wasmtime4j.wit.WitRecord;
import ai.tegmentum.wasmtime4j.wit.WitResult;
import ai.tegmentum.wasmtime4j.wit.WitString;
import ai.tegmentum.wasmtime4j.wit.WitType;
import ai.tegmentum.wasmtime4j.wit.WitU32;
import ai.tegmentum.wasmtime4j.wit.WitValue;
import ai.tegmentum.webassembly4j.api.WitHostFunction;

import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.impl.MapBindingSet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Host callbacks satisfying the v0.3.0 WIT world's {@code host} import
 * interface:
 * {@code stardog:webfunction/host@0.3.0#execute-query} and
 * {@code stardog:webfunction/host@0.3.0#callback-depth}.
 *
 * <p>Every {@code Object[]} that flows across the wasmtime4j {@code
 * WitHostFunction} boundary must be a properly-typed {@link WitValue}
 * instance — the same discipline {@link WitValueMarshaller} applies to
 * VALUE_TYPE, extended here for {@code result<T, E>}, {@code option<u32>},
 * and the {@code binding-sets} record.
 *
 * <p>Threading: both callbacks read from {@link CallbackContext#current()},
 * which the {@link WfEvaluationStrategyFactory} binds at strategy
 * construction so this is available for any FilterFunction / TupleFunction
 * / aggregate fired during that strategy's evaluation.
 */
public final class HostCallbacks {

    // Composite WIT types the callbacks use.
    static final WitType RESULT_TYPE;

    static {
        RESULT_TYPE = WitType.result(
                Optional.of(WitValueMarshaller.BINDING_SETS_TYPE),
                Optional.of(WitType.createString()));
    }

    private HostCallbacks() {}

    /** {@code execute-query: func(sparql: string, bindings: list<binding>,
     *  max-rows: option<u32>) -> result<binding-sets, string>}. */
    public static WitHostFunction executeQuery() {
        return args -> {
            if (!WebFunctionConfig.callbackEnabled()) {
                return new Object[] { err(
                    "wf callback disabled by webfunctions.callback.enabled=false") };
            }
            final CallbackContext ctx = CallbackContext.current();
            if (ctx == null) {
                return new Object[] { err(
                    "wf callback: no strategy bound — install WfEvaluationStrategyFactory "
                    + "on the sail to enable callbacks") };
            }
            try {
                final String sparql = ((WitString) args[0]).getValue();
                final BindingSet initial = decodeBindings((WitList) args[1], ctx);
                final int rowCap = decodeOptionalU32((WitOption) args[2]).orElseGet(ctx::maxRows);

                ctx.enter();
                try (CloseableIteration<BindingSet> iter = ctx.executeSelect(sparql, initial)) {
                    return new Object[] { ok(encodeBindingSets(iter, rowCap)) };
                } finally {
                    ctx.exit();
                }
            } catch (RuntimeException e) {
                return new Object[] { err(e.getMessage() == null ? e.toString() : e.getMessage()) };
            }
        };
    }

    /** {@code callback-depth: func() -> u32}. */
    public static WitHostFunction callbackDepth() {
        return args -> {
            final CallbackContext ctx = CallbackContext.current();
            return new Object[] { WitU32.of(ctx == null ? 0 : ctx.depth()) };
        };
    }

    // ---- marshalling helpers -------------------------------------------------

    private static WitResult ok(final WitRecord bindingSets) {
        return WitResult.ok(RESULT_TYPE, bindingSets);
    }

    private static WitResult err(final String message) {
        return WitResult.err(RESULT_TYPE, witString(message));
    }

    private static WitString witString(final String s) {
        try {
            return WitString.of(s);
        } catch (ai.tegmentum.wasmtime4j.exception.ValidationException e) {
            try {
                // Fallback for a string with invalid UTF-8; unlikely for
                // SPARQL-produced strings but defensive.
                return WitString.of("wf callback: (unrepresentable string)");
            } catch (ai.tegmentum.wasmtime4j.exception.ValidationException e2) {
                throw new AssertionError("static fallback string rejected", e2);
            }
        }
    }

    /**
     * Decode a WIT {@code list<binding>} into an RDF4J BindingSet. Each element
     * is a {@code record binding} with fields ("name": string, "value": value).
     */
    private static BindingSet decodeBindings(final WitList list, final CallbackContext ctx) {
        final MapBindingSet bs = new MapBindingSet();
        for (WitValue elem : list.getElements()) {
            final WitRecord record = (WitRecord) elem;
            final String name = ((WitString) record.getField("name")).getValue();
            final Value rdfValue = WitValueMarshaller.valueFromWit(
                    record.getField("value"), ctx.valueFactory());
            bs.addBinding(name, rdfValue);
        }
        return bs;
    }

    /** {@code option<u32>} → {@link Optional} of int for our range. */
    private static Optional<Integer> decodeOptionalU32(final WitOption option) {
        final java.util.Optional<Object> raw = option.toJava();
        return raw.map(o -> ((Number) o).intValue());
    }

    /**
     * Encode a lazy {@link CloseableIteration} of {@link BindingSet}s as a WIT
     * {@code binding-sets} record. Materialises up to {@code rowCap} rows.
     */
    private static WitRecord encodeBindingSets(final CloseableIteration<BindingSet> iter,
                                               final int rowCap) {
        final LinkedHashSet<String> varsSeen = new LinkedHashSet<>();
        final List<WitValue> rows = new ArrayList<>();
        int rowsSeen = 0;
        while (iter.hasNext() && rowsSeen < rowCap) {
            final BindingSet row = iter.next();
            varsSeen.addAll(row.getBindingNames());
            final List<WitValue> bindings = new ArrayList<>();
            for (String var : row.getBindingNames()) {
                final Value rdfValue = row.getValue(var);
                if (rdfValue == null) continue;
                final WitRecord binding = WitRecord.builder()
                        .field("name", witString(var))
                        .field("value", WitValueMarshaller.toWit(rdfValue))
                        .build();
                bindings.add(binding);
            }
            rows.add(WitList.of(bindings));
            rowsSeen++;
        }
        final List<WitValue> vars = new ArrayList<>(varsSeen.size());
        for (String v : varsSeen) vars.add(witString(v));

        return WitRecord.builder()
                .field("vars", WitList.of(vars))
                .field("rows", WitList.of(rows))
                .build();
    }
}

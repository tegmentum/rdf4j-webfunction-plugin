package ai.tegmentum.rdf4j.webfunctions;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.QueryEvaluationException;
import org.eclipse.rdf4j.query.parser.sparql.aggregate.AggregateCollector;
import org.eclipse.rdf4j.query.parser.sparql.aggregate.AggregateNAryFunction;
import org.eclipse.rdf4j.query.parser.sparql.aggregate.AggregateNAryFunctionFactory;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Predicate;

/**
 * SPARQL custom aggregate {@code <wf:call-agg>(component-url, ...args)} for
 * RDF4J. On each row, calls the component's {@code aggregate-step} export
 * with the evaluated row values (multiplicity is always 1 — RDF4J's aggregate
 * model doesn't surface per-row multiplicity). On aggregation close, calls
 * {@code aggregate-finish} and returns the first row's first bound value.
 *
 * <p>Registered via {@code META-INF/services/org.eclipse.rdf4j.query.parser.sparql.aggregate.AggregateFunctionFactory}
 * — RDF4J's SPARQL parser discovers it on the classpath.
 */
public final class WfCallAgg implements AggregateNAryFunctionFactory {

    public static final String URI = WfCall.NAMESPACE + "call-agg";

    @Override
    public String getIri() {
        return URI;
    }

    @Override
    public int getMinNumberOfArguments() {
        return 2; // component URL + at least one value
    }

    @Override
    public int getMaxNumberOfArguments() {
        return Integer.MAX_VALUE;
    }

    @Override
    public AggregateNAryFunction buildFunction(
            final BiFunction<Integer, BindingSet, Value> evaluationStepByIndex) {
        return new WfCallAggFunction(evaluationStepByIndex);
    }

    @Override
    public AggregateCollector getCollector() {
        return new WfCallCollector();
    }

    /**
     * Per-row processing. Extracts the component URL once (from arg 0) and
     * calls {@code aggregate-step} with args 1..n.
     */
    private static final class WfCallAggFunction
            extends AggregateNAryFunction<WfCallCollector, Value> {

        WfCallAggFunction(final BiFunction<Integer, BindingSet, Value> eval) {
            super(eval);
        }

        @Override
        public void processAggregate(
                final BindingSet bindings,
                final Predicate<List<Value>> distinctValue,
                final WfCallCollector collector)
                throws QueryEvaluationException {
            if (collector.error != null) return; // short-circuit after failure

            try {
                if (collector.instance == null) {
                    final Value urlVal = evaluate(0, bindings);
                    collector.instance = new Rdf4jWasmInstance(toUrl(urlVal));
                }
                // Count args by trying evaluate until we hit an out-of-range
                // — the BiFunction contract doesn't expose arity, so we probe.
                final java.util.List<Value> stepArgs = new java.util.ArrayList<>();
                for (int i = 1; ; i++) {
                    final Value v;
                    try {
                        v = evaluate(i, bindings);
                    } catch (Exception e) {
                        break;
                    }
                    if (v == null) break;
                    stepArgs.add(v);
                }
                collector.instance.aggregateStep(
                        stepArgs.toArray(new Value[0]), 1L);
            } catch (IOException e) {
                collector.error = "wf:call-agg step failed: " + e.getMessage();
            }
        }

        private static URL toUrl(final Value v) {
            final String raw;
            if (v instanceof IRI) raw = ((IRI) v).stringValue();
            else if (v instanceof Literal) raw = ((Literal) v).getLabel();
            else throw new QueryEvaluationException(
                    "wf:call-agg: first arg must be an IRI or string");
            try {
                return new URL(raw);
            } catch (MalformedURLException e) {
                throw new QueryEvaluationException(
                        "wf:call-agg: not a valid URL: " + raw, e);
            }
        }
    }

    /**
     * Holds the wasm instance across rows and produces the final aggregate value.
     */
    private static final class WfCallCollector implements AggregateCollector {
        Rdf4jWasmInstance instance;
        String error;

        @Override
        public Value getFinalValue() {
            try {
                if (error != null) throw new QueryEvaluationException(error);
                if (instance == null) {
                    return SimpleValueFactory.getInstance().createLiteral("");
                }
                final List<WitValueMarshaller.Row> rows =
                        instance.aggregateFinish(SimpleValueFactory.getInstance());
                if (rows.isEmpty() || rows.get(0).values.isEmpty()
                        || rows.get(0).values.get(0) == null) {
                    return SimpleValueFactory.getInstance().createLiteral("");
                }
                return rows.get(0).values.get(0);
            } catch (IOException e) {
                throw new QueryEvaluationException(
                        "wf:call-agg finish failed: " + e.getMessage(), e);
            } finally {
                if (instance != null) {
                    instance.close();
                    instance = null;
                }
            }
        }
    }
}

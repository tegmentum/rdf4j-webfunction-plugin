package ai.tegmentum.rdf4j.webfunctions;

import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.common.iteration.CloseableIteratorIteration;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.query.QueryEvaluationException;
import org.eclipse.rdf4j.query.algebra.evaluation.function.TupleFunction;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * RDF4J {@link TupleFunction} for {@code wf:call}. Given an argument list
 * whose first element is the wasm URL and the rest are per-row inputs to the
 * component's {@code evaluate} export, emits one output tuple per row in the
 * component's {@code binding-sets} return — each tuple containing the row's
 * bound values in the WIT declared order.
 *
 * <p>Registered on the classpath via
 * {@code META-INF/services/org.eclipse.rdf4j.query.algebra.evaluation.function.TupleFunction}.
 *
 * <p>SPARQL-textual invocation works out of the box when the Sail is wired
 * with {@link WfEvaluationStrategyFactory}: that factory returns a {@link
 * org.eclipse.rdf4j.query.algebra.evaluation.impl.TupleFunctionEvaluationStrategy}
 * (RDF4J's default {@code StrictEvaluationStrategy} does not dispatch to
 * TupleFunctions) and prepends {@link WfCallTupleFunctionOptimizer} to the
 * query pipeline so the SPIN-style magic-property syntax
 * {@code (arg1 arg2) wf:call (?out1 ?out2)} is recognised. See {@code
 * TestWfCallTupleFunctionSparql} for the end-to-end path and {@code
 * TestWfCallTupleFunction} for the underlying programmatic bridge.
 */
public final class WfCallTupleFunction implements TupleFunction {

    public static final String URI = WfCall.URI;

    @Override
    public String getURI() {
        return URI;
    }

    @Override
    public CloseableIteration<? extends List<? extends Value>> evaluate(
            final ValueFactory valueFactory, final Value... args) throws QueryEvaluationException {
        if (args == null || args.length < 1) {
            throw new QueryEvaluationException(
                    "wf:call TupleFunction requires at least the component URL argument");
        }
        final URL wasmUrl = toUrl(args[0]);
        final Value[] evalArgs = Arrays.copyOfRange(args, 1, args.length);

        final List<WitValueMarshaller.Row> rows;
        try (Rdf4jWasmInstance instance = new Rdf4jWasmInstance(wasmUrl)) {
            rows = instance.evaluate(valueFactory, evalArgs);
        } catch (IOException e) {
            throw new QueryEvaluationException("wf:call: " + e.getMessage(), e);
        }

        final List<List<Value>> out = new ArrayList<>(rows.size());
        for (WitValueMarshaller.Row row : rows) {
            out.add(row.values); // nulls preserved for unbound cells
        }
        return new CloseableIteratorIteration<>(out.iterator());
    }

    private static URL toUrl(final Value v) {
        final String raw;
        if (v instanceof IRI iri) raw = iri.stringValue();
        else if (v instanceof Literal literal) raw = literal.getLabel();
        else throw new QueryEvaluationException(
                "wf:call: first argument must be an IRI or string");
        try {
            return new URL(raw);
        } catch (MalformedURLException e) {
            throw new QueryEvaluationException("wf:call: not a valid URL: " + raw, e);
        }
    }
}

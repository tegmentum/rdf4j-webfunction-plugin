package ai.tegmentum.rdf4j.webfunctions;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.query.algebra.evaluation.ValueExprEvaluationException;
import org.eclipse.rdf4j.query.algebra.evaluation.function.Function;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;

/**
 * SPARQL filter function {@code wf:call(component-url, args...)} for RDF4J.
 * Loads the WASM component at {@code component-url} (IRI or string literal),
 * invokes its {@code evaluate(list<value>)} export, and returns the first row's
 * first bound value.
 *
 * <p>Registered under {@code http://tegmentum.ai/ns/webfunction/}.
 */
public final class WfCall implements Function {

    public static final String NAMESPACE = "http://tegmentum.ai/ns/webfunction/";
    public static final String URI       = NAMESPACE + "call";

    @Override
    public String getURI() {
        return URI;
    }

    @Override
    public Value evaluate(final ValueFactory vf, final Value... args) {
        if (args == null || args.length < 1) {
            throw new ValueExprEvaluationException(
                    "wf:call requires at least the component URL argument");
        }

        final URL componentUrl = toUrl(args[0]);
        final Value[] callArgs = new Value[args.length - 1];
        System.arraycopy(args, 1, callArgs, 0, callArgs.length);

        try (Rdf4jWasmInstance instance = new Rdf4jWasmInstance(componentUrl)) {
            final List<WitValueMarshaller.Row> rows = instance.evaluate(vf, callArgs);
            if (rows.isEmpty()) {
                throw new ValueExprEvaluationException("wf:call: component returned no rows");
            }
            final WitValueMarshaller.Row row = rows.get(0);
            if (row.values.isEmpty() || row.values.get(0) == null) {
                throw new ValueExprEvaluationException("wf:call: first row has no bound values");
            }
            return row.values.get(0);
        } catch (IOException e) {
            throw new ValueExprEvaluationException("wf:call: " + e.getMessage(), e);
        }
    }

    private static URL toUrl(final Value v) {
        final String raw;
        if (v instanceof IRI iri) {
            raw = iri.stringValue();
        } else if (v instanceof Literal literal) {
            raw = literal.getLabel();
        } else {
            throw new ValueExprEvaluationException(
                    "wf:call: first argument must be an IRI or string");
        }
        try {
            return new URL(raw);
        } catch (MalformedURLException e) {
            throw new ValueExprEvaluationException("wf:call: not a valid URL: " + raw, e);
        }
    }
}

package ai.tegmentum.rdf4j.webfunctions;

import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.algebra.Var;
import org.eclipse.rdf4j.query.algebra.evaluation.function.TupleFunctionRegistry;
import org.eclipse.rdf4j.query.algebra.evaluation.impl.TupleFunctionEvaluationStrategy;
import org.eclipse.rdf4j.query.impl.EmptyBindingSet;
import org.junit.Test;

import java.io.File;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assume.assumeTrue;

/**
 * Verifies the {@link WfCallTupleFunction} pipeline end-to-end via {@link
 * TupleFunctionEvaluationStrategy}'s static bridge method. This is the layer
 * RDF4J's {@code TupleFunctionEvaluationStrategy} uses internally to turn a
 * {@code TupleFunctionCall} into a {@code BindingSet} iterator.
 *
 * <p>Note: the plugin also registers via {@code META-INF/services/org.eclipse
 * .rdf4j.query.algebra.evaluation.function.TupleFunction}, so
 * {@code TupleFunctionRegistry.getInstance().has(URI)} returns true on
 * classpath discovery. Actual SPARQL-syntax invocation requires either a
 * SPINX parser extension or programmatic query-algebra construction — the
 * bridge tested here is what those paths eventually call.
 */
public class TestWfCallTupleFunction {

    private static final String TO_UPPER_WASM =
            System.getProperty("wf.toUpper.wasm",
                    System.getProperty("user.home")
                            + "/git/stardog-webfunction-plugin/src/test/rust/target/wasm32-wasip1/release/to_upper_component.wasm");

    @Test
    public void registeredViaServiceLoader() {
        assertThat(TupleFunctionRegistry.getInstance().has(WfCallTupleFunction.URI)).isTrue();
    }

    @Test
    public void tupleFunctionYieldsUppercasedRow() throws Exception {
        final File wasm = new File(TO_UPPER_WASM);
        assumeTrue("to_upper_component.wasm not found at " + wasm, wasm.exists());

        final ValueFactory vf = SimpleValueFactory.getInstance();
        final Value urlLit = vf.createLiteral(wasm.toURI().toString());
        final Value stardog = vf.createLiteral("stardog");

        final WfCallTupleFunction fn = new WfCallTupleFunction();
        // Result var order matches the WIT declared 'vars' order; wf:to_upper
        // has one output var 'value_0'.
        final List<Var> resultVars = Collections.singletonList(new Var("result"));

        try (CloseableIteration<BindingSet> it =
                     TupleFunctionEvaluationStrategy.evaluate(
                             fn, resultVars, EmptyBindingSet.getInstance(),
                             vf, urlLit, stardog)) {
            assertThat(it.hasNext()).isTrue();
            final BindingSet row = it.next();
            assertThat(row.getValue("result").stringValue()).isEqualTo("STARDOG");
            assertThat(it.hasNext()).isFalse();
        }
    }
}

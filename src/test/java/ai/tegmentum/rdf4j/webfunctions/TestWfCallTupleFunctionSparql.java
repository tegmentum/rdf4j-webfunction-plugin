package ai.tegmentum.rdf4j.webfunctions;

import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.Test;

import java.io.File;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assume.assumeTrue;

/**
 * Closes the historical gap between {@link WfCallTupleFunction}'s programmatic
 * bridge test ({@link TestWfCallTupleFunction}) and actual SPARQL-textual
 * invocation. Uses {@link WfCallTupleFunctionOptimizer} + {@link
 * WfEvaluationStrategyFactory} to make the SPIN-style magic-property list
 * syntax callable directly from SPARQL text.
 */
public class TestWfCallTupleFunctionSparql {

    private static final String TO_UPPER_WASM =
            WasmFixtures.exampleUppercaseWasm();

    private SailRepository repo() {
        final MemoryStore store = new MemoryStore();
        // Wire the tuple-function-aware strategy factory before initialising —
        // the sail materialises the factory lazily on first connection.
        store.setEvaluationStrategyFactory(new WfEvaluationStrategyFactory(null));
        final SailRepository r = new SailRepository(store);
        r.init();
        return r;
    }

    @Test
    public void singleRowSingleVarViaMagicProperty() {
        final File wasm = new File(TO_UPPER_WASM);
        assumeTrue("to_upper_component.wasm not found at " + wasm, wasm.exists());

        final String query =
                "PREFIX wf: <" + WfCall.NAMESPACE + ">\n" +
                        "SELECT ?result WHERE {\n" +
                        "  (<" + wasm.toURI() + "> \"stardog\") wf:call ?result .\n" +
                        "}";

        try (RepositoryConnection conn = repo().getConnection();
             TupleQueryResult r = conn.prepareTupleQuery(query).evaluate()) {
            assertThat(r.hasNext()).isTrue();
            final BindingSet row = r.next();
            assertThat(row.getValue("result").stringValue()).isEqualTo("STARDOG");
            assertThat(r.hasNext()).isFalse();
        }
    }

    // Multi-var multi-row magic-property test retired alongside the
    // stardog-plugin-local multi_var_component fixture. The base
    // sparql-extension filter interface returns a single term; the
    // multi-row multi-var shape belongs to the property-function
    // surface. The replacement webfunctions example-multi-var-filter
    // preserves only the 2-arg describe filter — the 3-var 2-row
    // shape this test asserted no longer has a replacement fixture.
}

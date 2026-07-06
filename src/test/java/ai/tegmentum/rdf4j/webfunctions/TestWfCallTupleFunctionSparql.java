package ai.tegmentum.rdf4j.webfunctions;

import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

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
            System.getProperty("wf.toUpper.wasm",
                    System.getProperty("user.home")
                            + "/git/stardog-webfunction-plugin/src/test/rust/target/wasm32-wasip1/release/to_upper_component.wasm");

    private static final String MULTI_VAR_WASM =
            System.getProperty("wf.multiVar.wasm",
                    System.getProperty("user.home")
                            + "/git/stardog-webfunction-plugin/src/test/rust/target/wasm32-wasip1/release/multi_var_component.wasm");

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

    @Test
    public void multiRowMultiVarViaMagicProperty() {
        final File wasm = new File(MULTI_VAR_WASM);
        assumeTrue("multi_var_component.wasm not found at " + wasm, wasm.exists());

        // multi_var_component returns vars=[label,upper,length] and two rows:
        // ("stardog","STARDOG",7) and ("jena","JENA",4).
        final String query =
                "PREFIX wf: <" + WfCall.NAMESPACE + ">\n" +
                        "SELECT ?label ?upper ?length WHERE {\n" +
                        "  (<" + wasm.toURI() + ">) wf:call (?label ?upper ?length) .\n" +
                        "}";

        try (RepositoryConnection conn = repo().getConnection();
             TupleQueryResult r = conn.prepareTupleQuery(query).evaluate()) {
            final List<String> labels = new ArrayList<>();
            final List<String> uppers = new ArrayList<>();
            final List<String> lengths = new ArrayList<>();
            while (r.hasNext()) {
                final BindingSet row = r.next();
                labels.add(row.getValue("label").stringValue());
                uppers.add(row.getValue("upper").stringValue());
                lengths.add(row.getValue("length").stringValue());
            }
            assertThat(labels).containsExactlyInAnyOrder("stardog", "jena");
            assertThat(uppers).containsExactlyInAnyOrder("STARDOG", "JENA");
            assertThat(lengths).containsExactlyInAnyOrder("7", "4");
        }
    }
}

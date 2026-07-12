package ai.tegmentum.rdf4j.webfunctions;

import ai.tegmentum.rdf4j.webfunctions.rewrite.InvokeRegistry;
import ai.tegmentum.rdf4j.webfunctions.rewrite.InvokeSpec;

import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assume.assumeTrue;

/**
 * End-to-end test for the {@code wf-invoke:<hex>} SERVICE handler.
 *
 * <p>Simulates the state {@code PartialRewrite} leaves the query in after a
 * successful constant fold: an {@link InvokeSpec} sitting in the registry
 * keyed by an id, and a {@code SERVICE <wf-invoke:<hex>>} clause in the
 * query text pointing at that id. Asserts the wasm dispatches and the
 * returned column projects onto the caller variable.
 */
public class TestWfInvokeService {

    private static final String TO_UPPER_WASM =
            System.getProperty("wf.toUpper.wasm",
                    System.getProperty("user.home")
                            + "/git/stardog-webfunction-plugin/src/test/rust/target/wasm32-wasip1/release/to_upper_component.wasm");

    private static Repository REPO;
    private static InvokeRegistry REGISTRY;

    @BeforeClass
    public static void setUp() {
        final MemoryStore store = new MemoryStore();
        REGISTRY = new InvokeRegistry();
        store.setFederatedServiceResolver(new WfServiceResolver(null, REGISTRY));
        final SailRepository sail = new SailRepository(store);
        sail.init();
        REPO = sail;
    }

    @AfterClass
    public static void tearDown() {
        if (REPO != null) REPO.shutDown();
    }

    /**
     * Register an InvokeSpec by hand — this is what {@code PartialRewrite}
     * does at optimize time when it constant-folds
     * {@code wf:partial(<wasm>, "stardog")}. Then run a query whose SERVICE
     * URI targets the folded id and assert the wasm-returned column shows
     * up as an outer binding.
     */
    @Test
    public void wfInvokeServiceInvokesWasmWithPrefoldedArgs() {
        final File wasm = new File(TO_UPPER_WASM);
        assumeTrue("to_upper_component.wasm not found at " + wasm, wasm.exists());

        final long id = REGISTRY.insert(new InvokeSpec(
                wasm.toURI().toString(),
                Collections.singletonList(
                        SimpleValueFactory.getInstance().createLiteral("stardog"))));

        // The BIND is just there so the SERVICE clause is non-empty and
        // RDF4J actually fires the FederatedService — see the note in
        // TestWfCallService#serviceReturnsMultipleRowsAcrossMultipleVars.
        // With no wf:<column> projection triples, WfInvokeService emits
        // every wasm-returned column under its native name, so ?value_0
        // (the to_upper component's sole output var) becomes bound.
        final String queryString =
                "SELECT ?value_0 WHERE {\n" +
                "  SERVICE <" + InvokeRegistry.iriFor(id) + "> {\n" +
                "    BIND(\"\" AS ?_trigger)\n" +
                "  }\n" +
                "}";

        try (RepositoryConnection conn = REPO.getConnection()) {
            final TupleQuery query = conn.prepareTupleQuery(QueryLanguage.SPARQL, queryString);
            try (TupleQueryResult rs = query.evaluate()) {
                assertThat(rs.hasNext()).isTrue();
                final BindingSet row = rs.next();
                assertThat(row.getValue("value_0").stringValue()).isEqualTo("STARDOG");
                assertThat(rs.hasNext()).isFalse();
            }
        }
    }
}

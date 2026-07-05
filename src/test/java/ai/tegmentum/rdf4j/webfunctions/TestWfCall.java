package ai.tegmentum.rdf4j.webfunctions;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assume.assumeTrue;

/**
 * End-to-end smoke test for the RDF4J webfunction binding.
 * Reuses the {@code to_upper_component.wasm} built for the Stardog side.
 */
public class TestWfCall {

    private static final String TO_UPPER_WASM =
            System.getProperty("wf.toUpper.wasm",
                    System.getProperty("user.home")
                            + "/git/stardog-webfunction-plugin/src/test/rust/target/wasm32-wasip1/release/to_upper_component.wasm");

    private static Repository REPO;

    @BeforeClass
    public static void setUp() {
        REPO = new SailRepository(new MemoryStore());
        REPO.init();
    }

    @AfterClass
    public static void tearDown() {
        if (REPO != null) REPO.shutDown();
    }

    @Test
    public void wfCallUppercasesStringViaComponent() {
        final File wasm = new File(TO_UPPER_WASM);
        assumeTrue("to_upper_component.wasm not found at " + wasm
                        + " (override with -Dwf.toUpper.wasm=...)",
                wasm.exists());

        final String queryString =
                "PREFIX wf: <" + WfCall.NAMESPACE + ">\n" +
                "SELECT ?result WHERE {\n" +
                "  BIND(wf:call(<" + wasm.toURI() + ">, \"stardog\") AS ?result)\n" +
                "}";

        try (RepositoryConnection conn = REPO.getConnection()) {
            final TupleQuery query = conn.prepareTupleQuery(QueryLanguage.SPARQL, queryString);
            try (TupleQueryResult rs = query.evaluate()) {
                assertThat(rs.hasNext()).isTrue();
                final BindingSet row = rs.next();
                assertThat(row.getValue("result").stringValue()).isEqualTo("STARDOG");
                assertThat(rs.hasNext()).isFalse();
            }
        }
    }
}

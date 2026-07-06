package ai.tegmentum.rdf4j.webfunctions;

import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.query.algebra.evaluation.federation.FederatedServiceResolver;
import org.eclipse.rdf4j.repository.sparql.federation.SPARQLServiceResolver;
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
 * SPARQL SERVICE test for RDF4J. Uses a {@link WfServiceResolver} wrapping
 * the sail's default {@link SPARQLServiceResolver} so wasm-URL SERVICEs are
 * intercepted and everything else falls through to the standard HTTP resolver.
 */
public class TestWfCallService {

    private static final String TO_UPPER_WASM =
            System.getProperty("wf.toUpper.wasm",
                    System.getProperty("user.home")
                            + "/git/stardog-webfunction-plugin/src/test/rust/target/wasm32-wasip1/release/to_upper_component.wasm");

    private static Repository REPO;
    private static FederatedServiceResolver FALLBACK;

    @BeforeClass
    public static void setUp() {
        final SailRepository sail = new SailRepository(new MemoryStore());
        sail.init();
        REPO = sail;
        FALLBACK = new SPARQLServiceResolver();
        ((MemoryStore) sail.getSail()).setFederatedServiceResolver(new WfServiceResolver(FALLBACK));
    }

    @AfterClass
    public static void tearDown() {
        if (REPO != null) REPO.shutDown();
    }

    @Test
    public void serviceReturnsUppercasedRow() {
        final File wasm = new File(TO_UPPER_WASM);
        assumeTrue("to_upper_component.wasm not found at " + wasm, wasm.exists());

        final String queryString =
                "SELECT ?value_0 WHERE {\n" +
                "  SERVICE <" + wasm.toURI() + "> {\n" +
                "    BIND(\"stardog\" AS ?arg0)\n" +
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

package ai.tegmentum.rdf4j.webfunctions;

import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.StringReader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assume.assumeTrue;

/**
 * SPARQL test for the {@code wf:call-agg} custom aggregate in RDF4J. Uses the
 * shared {@code sum_component.wasm} built for the Stardog binding.
 */
public class TestWfCallAgg {

    private static final String SUM_WASM =
            WasmFixtures.exampleSumAggregateWasm();

    private static Repository REPO;

    @BeforeClass
    public static void setUp() throws Exception {
        REPO = new SailRepository(new MemoryStore());
        REPO.init();
        try (RepositoryConnection conn = REPO.getConnection()) {
            conn.add(new StringReader(
                    "@prefix ex: <http://example.com/> ."
                            + " ex:a ex:val 10 . ex:b ex:val 20 . ex:c ex:val 3 ."),
                    null, RDFFormat.TURTLE);
        }
    }

    @AfterClass
    public static void tearDown() {
        if (REPO != null) REPO.shutDown();
    }

    @Test
    public void sumAggregatesRowValues() {
        final File wasm = new File(SUM_WASM);
        assumeTrue("sum_component.wasm not found at " + wasm
                        + " (override with -Dwf.sum.wasm=...)",
                wasm.exists());

        final String queryString =
                "PREFIX wf: <" + WfCall.NAMESPACE + ">\n" +
                "PREFIX ex: <http://example.com/>\n" +
                "SELECT (<" + WfCallAgg.URI + ">(<" + wasm.toURI() + ">, ?v) AS ?total) WHERE {\n" +
                "  ?s ex:val ?v .\n" +
                "}";

        try (RepositoryConnection conn = REPO.getConnection()) {
            final TupleQuery query = conn.prepareTupleQuery(QueryLanguage.SPARQL, queryString);
            try (TupleQueryResult rs = query.evaluate()) {
                assertThat(rs.hasNext()).isTrue();
                final BindingSet row = rs.next();
                assertThat(row.getValue("total").stringValue()).isEqualTo("33");
                assertThat(rs.hasNext()).isFalse();
            }
        }
    }
}

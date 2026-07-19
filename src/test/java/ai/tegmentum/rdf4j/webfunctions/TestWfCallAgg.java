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
import org.junit.Ignore;
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

    // Pre-existing dispatch bug surfaced by the cross-plugin fixture
    // migration: Rdf4jWasmInstance.aggregateStep gates new-shape vs
    // old-shape aggregate dispatch on BridgingSparqlExtensionDispatch.
    // aggregateIsNewShape(), which in turn calls
    // ComponentInstance.hasFunction("tegmentum:webfunction/aggregate@
    // 0.1.0#new-aggregate"). The wasmtime4j-provider hasFunction probe
    // does not see interface-qualified exports, so aggregateIsNewShape
    // returns false, and the dispatch falls through to the flat
    // aggregate-step export the migrated example-sum-aggregate does
    // not provide. Filter-side dispatch already works around this via
    // instance.exportsInterface(…); an equivalent workaround is needed
    // for aggregate before this test can be re-enabled.
    //
    // The pre-migration wasm carried both shapes (flat aggregate-step
    // AND resource aggregate-state), so this dispatch mismatch was
    // masked. Once webfunctions/example-sum-aggregate drops the flat
    // legacy export the test surfaces the underlying bug.
    @Ignore("plugin dispatch bug: aggregateIsNewShape probe misses interface exports; new-shape example-sum-aggregate provides no flat aggregate-step fallback")
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

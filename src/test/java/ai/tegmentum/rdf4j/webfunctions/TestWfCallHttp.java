package ai.tegmentum.rdf4j.webfunctions;

import com.sun.net.httpserver.HttpServer;
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
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assume.assumeTrue;

/**
 * Verifies wf:call fetches wasm bytes over http:// so the plugin's URL fetch
 * path isn't quietly file:// only. Serves {@code to_upper_component.wasm}
 * from an in-process JDK HTTP server bound to a random loopback port.
 */
public class TestWfCallHttp {

    private static final String TO_UPPER_WASM =
            WasmFixtures.exampleUppercaseWasm();

    private static Repository REPO;
    private static HttpServer SERVER;
    private static String BASE_URL;

    @BeforeClass
    public static void setUp() throws IOException {
        REPO = new SailRepository(new MemoryStore());
        REPO.init();

        final File wasm = new File(TO_UPPER_WASM);
        assumeTrue("to_upper_component.wasm not found at " + wasm
                        + " (override with -Dwf.toUpper.wasm=...)",
                wasm.exists());
        final byte[] wasmBytes = Files.readAllBytes(wasm.toPath());

        SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        SERVER.createContext("/to_upper.wasm", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/wasm");
            exchange.sendResponseHeaders(200, wasmBytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(wasmBytes);
            }
        });
        SERVER.start();
        BASE_URL = "http://127.0.0.1:" + SERVER.getAddress().getPort();
    }

    @AfterClass
    public static void tearDown() {
        if (SERVER != null) {
            SERVER.stop(0);
            SERVER = null;
        }
        if (REPO != null) REPO.shutDown();
    }

    @Test
    public void wfCallUppercasesStringViaHttp() {
        final String url = BASE_URL + "/to_upper.wasm";
        final String queryString =
                "PREFIX wf: <" + WfCall.NAMESPACE + ">\n" +
                "SELECT ?result WHERE {\n" +
                "  BIND(wf:call(<" + url + ">, \"stardog\") AS ?result)\n" +
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

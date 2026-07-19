package ai.tegmentum.rdf4j.webfunctions;

import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.net.URL;
import java.util.List;

import static org.junit.Assume.assumeTrue;

/**
 * Micro-benchmark for the RDF4J binding. First-order signal only — hand-rolled
 * {@code nanoTime} rather than JMH. Gate: {@code -Dbench=1}.
 */
public class TestComponentBench {

    private static final String TO_UPPER_WASM =
            WasmFixtures.exampleUppercaseWasm();

    private static final int WARMUP = 500;
    private static final int MEASURED = 5_000;
    private static final int INSTANTIATIONS = 500;

    private static final ValueFactory VF = SimpleValueFactory.getInstance();

    @Before
    public void gate() {
        assumeTrue("bench off; enable with -Dbench=1",
                "1".equals(System.getProperty("bench")));
        assumeTrue("wasm not built: " + TO_UPPER_WASM,
                new File(TO_UPPER_WASM).exists());
    }

    @After
    public void reset() {
        Rdf4jWasmInstance.resetCache();
    }

    @Test
    public void benchEvaluate() throws Exception {
        final URL url = new File(TO_UPPER_WASM).toURI().toURL();
        final Value arg = VF.createLiteral("stardog");

        try (Rdf4jWasmInstance instance = new Rdf4jWasmInstance(url)) {
            for (int i = 0; i < WARMUP; i++) {
                final List<WitValueMarshaller.Row> rows = instance.evaluate(VF, arg);
                if (rows.isEmpty()) throw new IllegalStateException("empty");
            }
            final long start = System.nanoTime();
            for (int i = 0; i < MEASURED; i++) {
                instance.evaluate(VF, arg);
            }
            final long ns = (System.nanoTime() - start) / MEASURED;
            System.out.printf("evaluate: %,10d ns/op (%,10.0f ops/s)%n",
                    ns, 1e9 / ns);
        }
    }

    @Test
    public void benchInstantiation() throws Exception {
        final URL url = new File(TO_UPPER_WASM).toURI().toURL();
        try (Rdf4jWasmInstance warm = new Rdf4jWasmInstance(url)) {}

        final long start = System.nanoTime();
        for (int i = 0; i < INSTANTIATIONS; i++) {
            new Rdf4jWasmInstance(url).close();
        }
        final long ns = (System.nanoTime() - start) / INSTANTIATIONS;
        System.out.printf("instantiate (cached): %,10d ns/op (%,10.0f ops/s)%n",
                ns, 1e9 / ns);
    }
}

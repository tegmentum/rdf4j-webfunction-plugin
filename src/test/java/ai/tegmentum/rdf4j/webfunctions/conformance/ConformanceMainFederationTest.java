package ai.tegmentum.rdf4j.webfunctions.conformance;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the {@code --federation-config} flag on the
 * conformance runner. Confirms that the runner accepts the flag, loads
 * the JSON via {@link ai.tegmentum.rdf4j.webfunctions.rewrite.FederationRegistry},
 * and reports the parsed source count on stderr.
 *
 * <p>Does not drive a live federation query end-to-end &mdash; that
 * lives in the wf-conformance parity suite. This test's job is just to
 * prove the runner surface is wired.
 */
public class ConformanceMainFederationTest {

    @Test
    public void runnerLoadsFederationConfigAndReportsCount() throws Exception {
        final Path tmp = Files.createTempDirectory("wf-conformance-federation");
        try {
            final Path data = tmp.resolve("data.ttl");
            Files.writeString(data,
                    "@prefix ex: <http://example/> .\n"
                            + "ex:s1 ex:label \"hello world\" .\n",
                    StandardCharsets.UTF_8);

            final Path query = tmp.resolve("query.sparql");
            Files.writeString(query,
                    "SELECT ?s ?l WHERE { ?s <http://example/label> ?l }",
                    StandardCharsets.UTF_8);

            // Two-source fixture: one SPARQL, one wf-search. Structurally
            // identical to the design-memo §03 example (trimmed).
            final Path federationConfig = tmp.resolve("federation.json");
            Files.writeString(federationConfig, """
                    {
                      "sources": [
                        {
                          "name": "products",
                          "type": "sparql",
                          "endpoint": "http://oxigraph-products:7878/query",
                          "predicates": ["http://ex/sku", "http://ex/price"],
                          "probe_ttl_secs": 3600
                        },
                        {
                          "name": "manuals-search",
                          "type": "wf-search",
                          "endpoint": "wf-search:manuals"
                        }
                      ]
                    }
                    """, StandardCharsets.UTF_8);

            final ProcessResult r = runMain(
                    "--data", data.toString(),
                    "--query", query.toString(),
                    "--federation-config", federationConfig.toString());
            assertThat(r.exitCode)
                    .as("stderr:\n" + r.stderr + "\nstdout:\n" + r.stdout)
                    .isZero();

            // The runner emits a stderr diagnostic that names the config
            // path and reports the parsed count.
            assertThat(r.stderr)
                    .as("stderr should confirm the federation registry loaded")
                    .contains("loaded 2 federation source")
                    .contains(federationConfig.toString());

            // The base SPARQL query still executes — the predicate isn't
            // in the registry so the federation pass is a no-op for it.
            assertThat(r.stdout).contains("hello world");
        } finally {
            deleteRecursively(tmp);
        }
    }

    /**
     * A malformed federation config (unknown type discriminator) exits
     * with the distinct {@code federation config error:} prefix so the
     * parity harness can tell it apart from other config failures.
     */
    @Test
    public void runnerRejectsInvalidFederationConfig() throws Exception {
        final Path tmp = Files.createTempDirectory("wf-conformance-federation-bad");
        try {
            final Path data = tmp.resolve("data.ttl");
            Files.writeString(data, "", StandardCharsets.UTF_8);
            final Path query = tmp.resolve("query.sparql");
            Files.writeString(query, "SELECT * WHERE { ?s ?p ?o }", StandardCharsets.UTF_8);

            final Path federationConfig = tmp.resolve("bad.json");
            Files.writeString(federationConfig, """
                    {
                      "sources": [{
                        "name": "weird",
                        "type": "gopher",
                        "endpoint": "gopher://x/"
                      }]
                    }
                    """, StandardCharsets.UTF_8);

            final ProcessResult r = runMain(
                    "--data", data.toString(),
                    "--query", query.toString(),
                    "--federation-config", federationConfig.toString());
            assertThat(r.exitCode).isEqualTo(2);
            assertThat(r.stderr).contains("federation config error");
            assertThat(r.stderr).contains("gopher");
        } finally {
            deleteRecursively(tmp);
        }
    }

    // ---- shell-out helpers -----------------------------------------------

    private static final class ProcessResult {
        int exitCode;
        String stdout;
        String stderr;
    }

    private ProcessResult runMain(final String... args) throws IOException, InterruptedException {
        final String javaHome = System.getProperty("java.home");
        final String javaBin = javaHome + File.separator + "bin" + File.separator + "java";
        final String cp = System.getProperty("java.class.path");

        final List<String> cmd = new ArrayList<>();
        cmd.add(javaBin);
        cmd.add("-cp");
        cmd.add(cp);
        cmd.add(ConformanceMain.class.getName());
        for (String a : args) cmd.add(a);

        final ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        final Process p = pb.start();
        final byte[] out = p.getInputStream().readAllBytes();
        final byte[] err = p.getErrorStream().readAllBytes();
        final ProcessResult r = new ProcessResult();
        r.exitCode = p.waitFor();
        r.stdout = new String(out, StandardCharsets.UTF_8);
        r.stderr = new String(err, StandardCharsets.UTF_8);
        return r;
    }

    private static void deleteRecursively(final Path root) {
        try {
            Files.walk(root)
                    .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        } catch (IOException ignored) {
        }
    }
}

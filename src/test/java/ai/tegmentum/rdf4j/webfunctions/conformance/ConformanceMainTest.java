package ai.tegmentum.rdf4j.webfunctions.conformance;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the {@link ConformanceMain} runner. Shells out
 * exactly as the cross-engine conformance suite does: writes a tiny
 * fixture data.ttl and query.sparql, spawns a JVM that runs
 * {@code ConformanceMain}, parses the SPARQL Results JSON on stdout,
 * and asserts bindings.
 *
 * <p>Uses the surefire fork's classpath ({@code java.class.path}) so
 * this test travels with the plugin jar and its transitive deps
 * without an install step.
 */
public class ConformanceMainTest {

    @Test
    public void runnerEmitsSparqlResultsJsonForTrivialSelect() throws Exception {
        final Path tmp = Files.createTempDirectory("wf-conformance");
        try {
            final Path data = tmp.resolve("data.ttl");
            Files.writeString(data,
                    "@prefix ex: <http://example/> .\n"
                            + "ex:s1 ex:name \"alice\" .\n"
                            + "ex:s2 ex:name \"bob\"   .\n",
                    StandardCharsets.UTF_8);

            final Path query = tmp.resolve("query.sparql");
            Files.writeString(query,
                    "SELECT ?s ?n WHERE { ?s <http://example/name> ?n } ORDER BY ?s",
                    StandardCharsets.UTF_8);

            final ProcessResult r = runMain("--data", data.toString(),
                                            "--query", query.toString());
            assertThat(r.exitCode)
                    .as("stderr:\n" + r.stderr + "\nstdout:\n" + r.stdout)
                    .isZero();

            final JsonNode root = JsonMapper.builder().build().readTree(r.stdout);
            final JsonNode vars = root.path("head").path("vars");
            final Set<String> declaredVars = new HashSet<>();
            for (JsonNode v : vars) declaredVars.add(v.asString());
            assertThat(declaredVars).containsExactlyInAnyOrder("s", "n");

            final JsonNode bindings = root.path("results").path("bindings");
            assertThat(bindings.size()).isEqualTo(2);

            final List<String> names = new ArrayList<>();
            for (JsonNode row : bindings) {
                names.add(row.path("n").path("value").asString());
            }
            assertThat(names).containsExactlyInAnyOrder("alice", "bob");

            // Structural check on the s/n IRI shape per SPARQL Results JSON.
            assertThat(bindings.get(0).path("s").path("type").asString()).isEqualTo("uri");
            assertThat(bindings.get(0).path("n").path("type").asString()).isEqualTo("literal");
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

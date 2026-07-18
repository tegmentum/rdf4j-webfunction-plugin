package ai.tegmentum.rdf4j.webfunctions;

import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.Test;

import java.io.File;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assume.assumeTrue;

/**
 * Isolates the simplest possible v0.3.0 host-callback invocation:
 * {@code host::callback_depth()} — no arguments, u32 return. If this passes
 * end-to-end, the wire-up is correct and the remaining problem in
 * {@link TestWfTreeE2E} is specifically the shape marshalling of
 * {@code execute-query}'s compound arguments. If this fails at the same
 * wasm-boundary trap, the linker binding itself needs a different form.
 *
 * <p>Runs against {@code debug_callback_depth.wasm} in the sibling
 * webfunctions crate.
 */
public class TestCallbackDepth {

    private static final String WASM = System.getProperty("wf.debug.callback.depth.wasm",
            System.getProperty("user.home")
                    + "/git/webfunctions/target/wasm32-wasip1/release/debug_callback_depth.wasm");

    @Test
    public void depthIsZeroAtTopLevel() throws Exception {
        final File wasm = new File(WASM);
        assumeTrue("debug_callback_depth.wasm not built at " + wasm.getAbsolutePath(),
                wasm.exists());

        final MemoryStore store = new MemoryStore();
        store.setEvaluationStrategyFactory(new WfEvaluationStrategyFactory(null));
        final SailRepository repo = new SailRepository(store);
        repo.init();

        try (RepositoryConnection conn = repo.getConnection()) {
            final String sparql =
                "PREFIX wf: <http://tegmentum.ai/ns/webfunction/>\n" +
                "SELECT ?depth WHERE {\n" +
                "  BIND (wf:call(<" + wasm.toURI() + ">) AS ?depth)\n" +
                "}";
            try (TupleQueryResult r = conn.prepareTupleQuery(sparql).evaluate()) {
                assertThat(r.hasNext()).isTrue();
                final BindingSet row = r.next();
                assertThat(row.getValue("depth").stringValue()).isEqualTo("0");
            }
        } finally {
            repo.shutDown();
        }
    }
}

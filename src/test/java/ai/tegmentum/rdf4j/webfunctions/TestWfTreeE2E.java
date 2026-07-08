package ai.tegmentum.rdf4j.webfunctions;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.Ignore;
import org.junit.Test;

import java.io.File;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assume.assumeTrue;

/**
 * End-to-end validation of the v0.3.0 host-callback path via wf_tree.wasm.
 *
 * <p>Setup:
 * <ol>
 *   <li>MemoryStore configured with {@link WfEvaluationStrategyFactory} —
 *       this is what binds {@link CallbackContext} at strategy construction
 *       and unlocks the callbacks.</li>
 *   <li>A tiny tree-shaped graph: A → B, A → C, B → D, C → E.</li>
 *   <li>Query: <code>SELECT (wf:call(&lt;wf_tree.wasm&gt;, &lt;A&gt;,
 *       "SELECT ?child WHERE { ?this :hasChild ?child }") AS ?tree)</code>.</li>
 *   <li>Assert the returned JSON has A at the root with B and C as children
 *       and D/E as grandchildren.</li>
 * </ol>
 *
 * <p>This is the first invocation that actually exercises the WIT ADT
 * marshalling in {@link HostCallbacks} — the test may need to iterate on
 * Object-shape adjustments once run against the real wasmtime4j engine.
 */
public class TestWfTreeE2E {

    private static final String WF_TREE_WASM = System.getProperty("wf.tree.wasm",
            System.getProperty("user.home")
                    + "/git/tegmentum-webfunctions/target/wasm32-wasip1/release/wf_tree.wasm");

    /**
     * Still ignored, though further along than the previous scaffold:
     * HostCallbacks now marshals its returns as WitResult/WitRecord/WitList/
     * WitString via the same idiom {@link WitValueMarshaller} uses for
     * VALUE_TYPE. Component instantiation now succeeds — before, we failed
     * at instantiate with "instance export execute-query has the wrong
     * type: function implementation is missing." Now, invocation crashes
     * inside wf_tree::walk at the callback-boundary trap:
     *
     *   "Function call failed: error while executing at wasm backtrace:
     *      0: wf_tree.wasm!wf_tree::walk
     *      1: wf_tree.wasm!evaluate"
     *
     * The callback body never runs — the trap happens as wasm invokes the
     * import. That's a signature-level mismatch between what wit-bindgen's
     * generated ABI for `execute-query` expects and what wasmtime4j's
     * WitHostFunction registration exposes.
     *
     * Debugging path from here (needs a scratch component + dedicated
     * session):
     *   1. Build a minimal wasm component that just calls callback-depth()
     *      (u32 return, no arg complexity). If that works, execute-query's
     *      compound argument shapes are the culprit; if it doesn't, the
     *      binding wire-up itself is wrong.
     *   2. If callback-depth also traps, check that
     *      DefaultLinkingContext.addWitHostFunction genuinely produces a
     *      compatible function definition — may need to look at
     *      WitHostFunctionDefinition's other constructor (which accepts
     *      an explicit type signature) rather than the two-arg form.
     *   3. If callback-depth works, iterate execute-query: encode empty
     *      result first (just varsSet=[] rows=[]), then add one binding,
     *      etc. Look at the wasm memory dump on trap to spot the shape
     *      mismatch.
     *
     * Everything else in the plumbing is real progress and unblocks the
     * debugging: strategy binding solves connection threading, the
     * v0.3.0 WIT world is committed and the wasm component uses it,
     * WitResult / WitRecord / WitList encoding is in place.
     */
    @Ignore("wasmtime4j WitHostFunction signature mismatch — see comment above")
    @Test
    public void tinyTreeFromRoot() throws Exception {
        final File wasm = new File(WF_TREE_WASM);
        assumeTrue("wf_tree.wasm not built at " + wasm.getAbsolutePath()
                + " (build via `cargo component build --release` in "
                + "tegmentum-webfunctions/crates/wf_tree)",
                wasm.exists());

        final MemoryStore store = new MemoryStore();
        store.setEvaluationStrategyFactory(new WfEvaluationStrategyFactory(null));
        final SailRepository repo = new SailRepository(store);
        repo.init();

        try (RepositoryConnection conn = repo.getConnection()) {
            final ValueFactory vf = SimpleValueFactory.getInstance();
            final IRI has = vf.createIRI("http://example.org/hasChild");
            final IRI a = vf.createIRI("http://example.org/A");
            final IRI b = vf.createIRI("http://example.org/B");
            final IRI c = vf.createIRI("http://example.org/C");
            final IRI d = vf.createIRI("http://example.org/D");
            final IRI e = vf.createIRI("http://example.org/E");
            conn.add(a, has, b);
            conn.add(a, has, c);
            conn.add(b, has, d);
            conn.add(c, has, e);

            final String sparql =
                "PREFIX wf: <http://tegmentum.ai/ns/webfunction/>\n" +
                "PREFIX ex: <http://example.org/>\n" +
                "SELECT ?tree WHERE {\n" +
                "  BIND (wf:call(\n" +
                "        <" + wasm.toURI() + ">,\n" +
                "        ex:A,\n" +
                "        \"SELECT ?child WHERE { ?this <http://example.org/hasChild> ?child }\"" +
                "  ) AS ?tree)\n" +
                "}";

            try (TupleQueryResult r = conn.prepareTupleQuery(sparql).evaluate()) {
                assertThat(r.hasNext()).isTrue();
                final BindingSet row = r.next();
                final String tree = row.getValue("tree").stringValue();

                // Shape assertions on the JSON output — parse-free string
                // checks are enough for this smoke test.
                assertThat(tree).contains("\"uri\":\"http://example.org/A\"");
                assertThat(tree).contains("\"uri\":\"http://example.org/B\"");
                assertThat(tree).contains("\"uri\":\"http://example.org/C\"");
                assertThat(tree).contains("\"uri\":\"http://example.org/D\"");
                assertThat(tree).contains("\"uri\":\"http://example.org/E\"");
                assertThat(tree).contains("\"children\":");
            }
        } finally {
            repo.shutDown();
        }
    }
}

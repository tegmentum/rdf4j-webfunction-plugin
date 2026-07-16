package ai.tegmentum.rdf4j.webfunctions;

import ai.tegmentum.wasmtime4j.component.ComponentVal;
import ai.tegmentum.webassembly4j.api.WitHostFunction;

import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.query.algebra.evaluation.EvaluationStrategy;
import org.eclipse.rdf4j.query.algebra.evaluation.TripleSource;
import org.eclipse.rdf4j.query.algebra.evaluation.impl.DefaultEvaluationStrategy;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.evaluation.RepositoryTripleSource;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for the {@code wf:sagegraph/host@0.1.0#execute-query} host
 * callback backing the wf_sagegraph guest's k-hop SPARQL round-trip.
 *
 * <p>Drives {@link HostCallbacks#sagegraphExecuteQuery()} directly against
 * a MemoryStore-backed SailRepository — verifies that (1) the callback
 * resolves the bound {@link CallbackContext}, (2) SPARQL executes against
 * it, (3) the response comes back as SPARQL 1.1 Results JSON in the
 * {@code result<string, string>} Ok arm, and (4) an unbound context surfaces
 * cleanly on the Err arm.
 *
 * <p>The full wf_sagegraph guest e2e lives elsewhere (parallel guest agent
 * still building it); this test just verifies the linker binding + response
 * shape independent of any guest.
 */
public class TestSagegraphExecuteQuery {

    private static final String EX = "http://example.org/";

    private SailRepository buildRepo() {
        final SailRepository repo = new SailRepository(new MemoryStore());
        repo.init();
        try (RepositoryConnection conn = repo.getConnection()) {
            conn.begin();
            final ValueFactory vf = repo.getValueFactory();
            conn.add(vf.createIRI(EX + "Alice"), vf.createIRI(EX + "knows"),
                    vf.createIRI(EX + "Bob"));
            conn.add(vf.createIRI(EX + "Alice"), vf.createIRI(EX + "knows"),
                    vf.createIRI(EX + "Carol"));
            conn.add(vf.createIRI(EX + "Bob"), vf.createIRI(EX + "knows"),
                    vf.createIRI(EX + "Carol"));
            conn.commit();
        }
        return repo;
    }

    @Test
    public void selectReturnsSparqlResultsJson() {
        final SailRepository repo = buildRepo();
        try (RepositoryConnection conn = repo.getConnection()) {
            final TripleSource ts = new RepositoryTripleSource(conn, true);
            final EvaluationStrategy strategy = new DefaultEvaluationStrategy(ts, null);
            CallbackContext.bind(strategy, ts, repo.getSail());
            try {
                final WitHostFunction fn = HostCallbacks.sagegraphExecuteQuery();
                final String sparql =
                    "SELECT ?o WHERE { <" + EX + "Alice> <" + EX + "knows> ?o } ORDER BY ?o";

                final Object[] out = fn.execute(new Object[] { ComponentVal.string(sparql) });
                assertThat(out).hasSize(1);
                final ComponentVal result = (ComponentVal) out[0];
                final ComponentVal ok = result.asResult().getOk().orElseThrow(
                    () -> new AssertionError("expected Ok, got err: "
                        + result.asResult().getErr().map(ComponentVal::asString).orElse("<none>")));

                // Raw SPARQL 1.1 Results JSON — the guest parses it; we assert
                // envelope shape + that both bindings landed.
                final String json = ok.asString();
                assertThat(json).contains("\"head\"");
                assertThat(json).contains("\"vars\"");
                assertThat(json).contains("\"results\"");
                assertThat(json).contains("\"bindings\"");
                assertThat(json).contains(EX + "Bob");
                assertThat(json).contains(EX + "Carol");
            } finally {
                CallbackContext.unbind();
            }
        } finally {
            repo.shutDown();
        }
    }

    @Test
    public void askReturnsSparqlResultsJsonEnvelope() {
        final SailRepository repo = buildRepo();
        try (RepositoryConnection conn = repo.getConnection()) {
            final TripleSource ts = new RepositoryTripleSource(conn, true);
            final EvaluationStrategy strategy = new DefaultEvaluationStrategy(ts, null);
            CallbackContext.bind(strategy, ts, repo.getSail());
            try {
                final WitHostFunction fn = HostCallbacks.sagegraphExecuteQuery();
                // ASK routes through CallbackContext.executeSelect's ASK arm,
                // which materialises a single-row iteration with a `_ask` var
                // bound to xsd:boolean. Serialises to tuple-shape Results JSON
                // — fine for the guest since it just needs a stable string
                // it can parse.
                final String sparql =
                    "ASK { <" + EX + "Alice> <" + EX + "knows> <" + EX + "Bob> }";

                final Object[] out = fn.execute(new Object[] { ComponentVal.string(sparql) });
                final ComponentVal result = (ComponentVal) out[0];
                final ComponentVal ok = result.asResult().getOk().orElseThrow(
                    () -> new AssertionError("expected Ok for ASK, got err"));
                assertThat(ok.asString()).contains("true");
            } finally {
                CallbackContext.unbind();
            }
        } finally {
            repo.shutDown();
        }
    }

    @Test
    public void noContextBoundSurfacesAsErr() {
        // Explicitly no CallbackContext.bind — simulates a guest reaching
        // for execute-query outside a wf:call frame.
        final WitHostFunction fn = HostCallbacks.sagegraphExecuteQuery();
        final Object[] out = fn.execute(new Object[] {
            ComponentVal.string("SELECT ?s WHERE { ?s ?p ?o }") });
        final ComponentVal result = (ComponentVal) out[0];
        final ComponentVal err = result.asResult().getErr().orElseThrow(
            () -> new AssertionError("expected Err when no strategy is bound"));
        assertThat(err.asString()).contains("no strategy bound");
    }

    @Test
    public void parseErrorSurfacesAsErr() {
        final SailRepository repo = buildRepo();
        try (RepositoryConnection conn = repo.getConnection()) {
            final TripleSource ts = new RepositoryTripleSource(conn, true);
            final EvaluationStrategy strategy = new DefaultEvaluationStrategy(ts, null);
            CallbackContext.bind(strategy, ts, repo.getSail());
            try {
                final WitHostFunction fn = HostCallbacks.sagegraphExecuteQuery();
                final Object[] out = fn.execute(new Object[] {
                    ComponentVal.string("this is not a sparql query") });
                final ComponentVal result = (ComponentVal) out[0];
                assertThat(result.asResult().getErr()).isPresent();
            } finally {
                CallbackContext.unbind();
            }
        } finally {
            repo.shutDown();
        }
    }
}

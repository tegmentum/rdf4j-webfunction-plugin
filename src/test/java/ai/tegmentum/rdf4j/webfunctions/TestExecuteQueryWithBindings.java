package ai.tegmentum.rdf4j.webfunctions;

import ai.tegmentum.wasmtime4j.component.ComponentVal;
import ai.tegmentum.webassembly4j.api.WitHostFunction;

import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.query.algebra.evaluation.EvaluationStrategy;
import org.eclipse.rdf4j.query.algebra.evaluation.TripleSource;
import org.eclipse.rdf4j.query.algebra.evaluation.impl.DefaultEvaluationStrategy;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.evaluation.RepositoryTripleSource;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for the v0.6 {@code execute-query-with-bindings} host callback.
 * Drives {@link HostCallbacks#executeQueryWithBindings()} directly with a
 * hand-shaped {@code binding-sets} seed rather than through wasm — the test
 * isn't about linker plumbing (that's covered elsewhere via {@code
 * debug_*.wasm}), it's about seed → BindingSetAssignment conversion and the
 * outer VALUES-join semantics.
 *
 * <p>Fixture: three people, all typed {@code ex:Person}. Seed is a two-row
 * matrix (?p = ex:Alice, ex:Bob). The query looks up {@code ?p rdf:type ?t}
 * and returns rows for those two subjects only.
 */
public class TestExecuteQueryWithBindings {

    private static final String EX = "http://example.org/";

    private static ComponentVal iri(final String s) {
        return ComponentVal.variant("iri", ComponentVal.string(s));
    }

    private static ComponentVal bindingRow(final String var, final ComponentVal value) {
        final Map<String, ComponentVal> fields = new LinkedHashMap<>();
        fields.put("name", ComponentVal.string(var));
        fields.put("value", value);
        return ComponentVal.record(fields);
    }

    private static ComponentVal bindingSets(final List<String> vars,
                                            final List<List<ComponentVal>> rows) {
        final List<ComponentVal> varsVals = new ArrayList<>();
        for (String v : vars) varsVals.add(ComponentVal.string(v));
        final List<ComponentVal> rowVals = new ArrayList<>();
        for (List<ComponentVal> row : rows) rowVals.add(ComponentVal.list(row));
        final Map<String, ComponentVal> fields = new LinkedHashMap<>();
        fields.put("vars", ComponentVal.list(varsVals));
        fields.put("rows", ComponentVal.list(rowVals));
        return ComponentVal.record(fields);
    }

    /**
     * Set up a MemoryStore-backed SailRepository with three ex:Person
     * subjects and bind a {@link CallbackContext} directly against a fresh
     * evaluation strategy — that's the same shape
     * {@link WfEvaluationStrategyFactory#createEvaluationStrategy} produces
     * during a real query, minus the extra optimizer wiring the test path
     * doesn't need.
     */
    private SailRepository buildRepo() {
        final SailRepository repo = new SailRepository(new MemoryStore());
        repo.init();
        try (RepositoryConnection conn = repo.getConnection()) {
            conn.begin();
            final ValueFactory vf = repo.getValueFactory();
            conn.add(vf.createIRI(EX + "Alice"), RDF.TYPE, vf.createIRI(EX + "Person"));
            conn.add(vf.createIRI(EX + "Bob"), RDF.TYPE, vf.createIRI(EX + "Person"));
            conn.add(vf.createIRI(EX + "Carol"), RDF.TYPE, vf.createIRI(EX + "Person"));
            conn.commit();
        }
        return repo;
    }

    @Test
    public void seedTwoRowsJoinsWithQueryPattern() {
        final SailRepository repo = buildRepo();
        try (RepositoryConnection conn = repo.getConnection()) {
            final TripleSource ts = new RepositoryTripleSource(conn, true);
            final EvaluationStrategy strategy = new DefaultEvaluationStrategy(ts, null);
            CallbackContext.bind(strategy, ts, repo.getSail());
            try {
                final WitHostFunction fn = HostCallbacks.executeQueryWithBindings();
                final List<List<ComponentVal>> rows = new ArrayList<>();
                rows.add(java.util.List.of(bindingRow("p", iri(EX + "Alice"))));
                rows.add(java.util.List.of(bindingRow("p", iri(EX + "Bob"))));
                final ComponentVal seed = bindingSets(java.util.List.of("p"), rows);
                final String sparql =
                    "SELECT ?p ?t WHERE { ?p <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> ?t }";

                final Object[] out = fn.execute(new Object[] {
                    ComponentVal.string(sparql), seed, ComponentVal.none()
                });
                assertThat(out).hasSize(1);
                final ComponentVal result = (ComponentVal) out[0];
                final ComponentVal ok = result.asResult().getOk().orElseThrow(
                    () -> new AssertionError("expected Ok, got err: "
                        + result.asResult().getErr().map(ComponentVal::asString).orElse("<none>")));

                final Map<String, ComponentVal> bs = ok.asRecord();
                final List<ComponentVal> rowsOut = bs.get("rows").asList();

                // Two rows expected — Alice and Bob — Carol filtered out.
                assertThat(rowsOut).hasSize(2);
                final List<String> subjectIris = new ArrayList<>();
                for (ComponentVal row : rowsOut) {
                    for (ComponentVal b : row.asList()) {
                        final Map<String, ComponentVal> bf = b.asRecord();
                        if ("p".equals(bf.get("name").asString())) {
                            subjectIris.add(bf.get("value").asVariant()
                                .getPayload().orElseThrow().asString());
                        }
                    }
                }
                assertThat(subjectIris).containsExactlyInAnyOrder(EX + "Alice", EX + "Bob");
            } finally {
                CallbackContext.unbind();
            }
        } finally {
            repo.shutDown();
        }
    }

    @Test
    public void malformedSeedSurfacesAsErr() {
        final SailRepository repo = buildRepo();
        try (RepositoryConnection conn = repo.getConnection()) {
            final TripleSource ts = new RepositoryTripleSource(conn, true);
            final EvaluationStrategy strategy = new DefaultEvaluationStrategy(ts, null);
            CallbackContext.bind(strategy, ts, repo.getSail());
            try {
                final WitHostFunction fn = HostCallbacks.executeQueryWithBindings();
                // Missing `rows` field — record only carries `vars`.
                final Map<String, ComponentVal> bad = new LinkedHashMap<>();
                bad.put("vars", ComponentVal.list(java.util.List.of(ComponentVal.string("p"))));
                final ComponentVal seed = ComponentVal.record(bad);

                final Object[] out = fn.execute(new Object[] {
                    ComponentVal.string("SELECT ?p WHERE { ?p ?a ?b }"),
                    seed, ComponentVal.none()
                });
                final ComponentVal result = (ComponentVal) out[0];
                final ComponentVal err = result.asResult().getErr().orElseThrow(
                    () -> new AssertionError("expected Err from malformed seed, got ok"));
                assertThat(err.asString()).contains("seed missing");
            } finally {
                CallbackContext.unbind();
            }
        } finally {
            repo.shutDown();
        }
    }
}

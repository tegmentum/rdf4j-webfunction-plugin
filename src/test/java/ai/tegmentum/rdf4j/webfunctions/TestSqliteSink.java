package ai.tegmentum.rdf4j.webfunctions;

import ai.tegmentum.wasmtime4j.component.ComponentVal;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test for the v0.5 {@link SqliteSink} + WIT ↔ SQLite marshalling.
 *
 * <p>Exercises the sink lifecycle end-to-end without needing an external
 * wasm component: open an in-memory sqlite, run DDL, insert, select,
 * close. The WIT payloads assembled here match what the wasm-side
 * {@code sink-open} / {@code sink-execute} host imports receive after
 * wasmtime4j's linker adapter unpacks the guest arguments.
 */
public class TestSqliteSink {

    private static ComponentVal stringLit(final String label) {
        return literalWith(label, "http://www.w3.org/2001/XMLSchema#string");
    }

    private static ComponentVal intLit(final long n) {
        return literalWith(Long.toString(n), "http://www.w3.org/2001/XMLSchema#integer");
    }

    private static ComponentVal literalWith(final String label, final String datatype) {
        final Map<String, ComponentVal> fields = new LinkedHashMap<>();
        fields.put("label", ComponentVal.string(label));
        fields.put("datatype", ComponentVal.string(datatype));
        fields.put("lang", ComponentVal.none());
        return ComponentVal.variant("literal", ComponentVal.record(fields));
    }

    @Test
    public void ddlInsertSelectRoundtrip() throws Exception {
        try (Sink sink = SqliteSink.open("sqlite://memory")) {
            // DDL — vars=[], rows=[]
            final ComponentVal ddlResult = sink.execute(
                    "CREATE TABLE person (id TEXT PRIMARY KEY, name TEXT, age INTEGER)",
                    List.of());
            assertThat(ddlResult.isRecord()).isTrue();
            final Map<String, ComponentVal> ddlFields = ddlResult.asRecord();
            assertThat(ddlFields.get("vars").asList()).isEmpty();
            assertThat(ddlFields.get("rows").asList()).isEmpty();

            // INSERT with parameter binding — WIT literal → JDBC scalar.
            sink.execute("INSERT INTO person (id, name, age) VALUES (?, ?, ?)",
                    List.of(stringLit("p1"), stringLit("Ada"), intLit(37)));
            sink.execute("INSERT INTO person (id, name, age) VALUES (?, ?, ?)",
                    List.of(stringLit("p2"), stringLit("Grace"), intLit(45)));

            // SELECT — two rows, three columns.
            final ComponentVal selResult = sink.execute(
                    "SELECT id, name, age FROM person ORDER BY id", List.of());
            final Map<String, ComponentVal> selFields = selResult.asRecord();
            assertThat(selFields.get("vars").asList()).hasSize(3);
            assertThat(selFields.get("vars").asList().get(0).asString()).isEqualTo("id");
            assertThat(selFields.get("vars").asList().get(2).asString()).isEqualTo("age");

            final List<ComponentVal> rows = selFields.get("rows").asList();
            assertThat(rows).hasSize(2);

            // Row 0: id=p1, name=Ada, age=37. Each binding is a record
            // { name, value } and value is a WIT literal variant.
            final List<ComponentVal> row0 = rows.get(0).asList();
            assertThat(row0).hasSize(3);
            final Map<String, ComponentVal> idBinding = row0.get(0).asRecord();
            assertThat(idBinding.get("name").asString()).isEqualTo("id");
            final Map<String, ComponentVal> idLit = idBinding.get("value").asVariant()
                    .getPayload().orElseThrow().asRecord();
            assertThat(idLit.get("label").asString()).isEqualTo("p1");

            // Age column should have come back as xsd:integer even though
            // the column type is INTEGER (SQLite type affinity).
            final Map<String, ComponentVal> ageBinding = row0.get(2).asRecord();
            final Map<String, ComponentVal> ageLit = ageBinding.get("value").asVariant()
                    .getPayload().orElseThrow().asRecord();
            assertThat(ageLit.get("label").asString()).isEqualTo("37");
            assertThat(ageLit.get("datatype").asString())
                    .isEqualTo("http://www.w3.org/2001/XMLSchema#integer");
        }
    }

    @Test
    public void openMemoryVariants() throws Exception {
        // Both documented shorthand shapes should open an in-memory sqlite.
        try (Sink a = SqliteSink.open("sqlite://memory")) {
            assertThat(a).isNotNull();
        }
        try (Sink b = SqliteSink.open("sqlite:///:memory:")) {
            assertThat(b).isNotNull();
        }
    }
}

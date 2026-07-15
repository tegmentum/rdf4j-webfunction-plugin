package ai.tegmentum.rdf4j.webfunctions;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for
 * {@link Rdf4jWasmInstance#mergePositionalLimitIntoOptsJson(String, long)},
 * the pure JSON-merge core of the wf:partial explicit-callsite 5-vs-6
 * arg fold. Covers the {@code document_federated} /
 * {@code document_managed} callsite shape:
 *
 * <pre>
 *   wf:partial(&lt;WASM&gt;, MANTICORE, SIRIX, index, query,
 *              20, '{"include_body":true,"highlight":false}')
 * </pre>
 *
 * where the guest declares {@code search(a, b, c, d, opts)} with
 * {@code limit} living inside the {@code opts} record. Mirrors
 * {@code oxigraph-wf/src/wf_call.rs::positional_limit_fold_tests} and
 * the Jena / QLever counterparts so all four engines coerce identical
 * callsites into identical invocations.
 */
public class PositionalLimitFoldTest {

    @Test
    public void mergesLimitIntoOptsObject() {
        final String opts = "{\"include_body\":true,\"highlight\":false}";
        final String merged = Rdf4jWasmInstance.mergePositionalLimitIntoOptsJson(opts, 20L);
        assertThat(merged).isNotNull();
        // Round-trip through Jackson to make the assertion resilient
        // to key-order shuffles.
        final tools.jackson.databind.json.JsonMapper mapper =
                tools.jackson.databind.json.JsonMapper.builder().build();
        final tools.jackson.databind.JsonNode node;
        try {
            node = mapper.readTree(merged);
        } catch (RuntimeException e) {
            throw new AssertionError("merged JSON must round-trip", e);
        }
        assertThat(node.get("limit").asLong()).isEqualTo(20L);
        assertThat(node.get("include_body").asBoolean()).isTrue();
        assertThat(node.get("highlight").asBoolean()).isFalse();
    }

    @Test
    public void mergesIntoEmptyObject() {
        final String merged = Rdf4jWasmInstance.mergePositionalLimitIntoOptsJson("{}", 10L);
        assertThat(merged).isNotNull();
        assertThat(merged).contains("\"limit\":10");
    }

    @Test
    public void explicitOptsLimitWinsOverPositional() {
        // Matches the URL-sugar path's `or_insert_with` semantics: an
        // explicit "limit":50 in the opts blob is NOT overwritten by
        // the positional 20. Users who spell both get the explicit
        // one, same as they would through the URL-sugar path.
        final String opts = "{\"limit\":50,\"include_body\":true}";
        final String merged = Rdf4jWasmInstance.mergePositionalLimitIntoOptsJson(opts, 20L);
        assertThat(merged).isNotNull();
        assertThat(merged).contains("\"limit\":50");
        assertThat(merged).doesNotContain("\"limit\":20");
    }

    @Test
    public void returnsNullForNonObjectJson() {
        // A JSON array / scalar isn't a valid opts blob — the merge
        // must decline so the honest arg-count-mismatch error surfaces
        // downstream instead of a misleading fold.
        assertThat(Rdf4jWasmInstance.mergePositionalLimitIntoOptsJson("[1,2,3]", 20L)).isNull();
        assertThat(Rdf4jWasmInstance.mergePositionalLimitIntoOptsJson("42", 20L)).isNull();
        assertThat(Rdf4jWasmInstance.mergePositionalLimitIntoOptsJson("\"str\"", 20L)).isNull();
    }

    @Test
    public void returnsNullForMalformedJson() {
        assertThat(Rdf4jWasmInstance.mergePositionalLimitIntoOptsJson("{not json}", 20L)).isNull();
        assertThat(Rdf4jWasmInstance.mergePositionalLimitIntoOptsJson("", 20L)).isNull();
    }
}

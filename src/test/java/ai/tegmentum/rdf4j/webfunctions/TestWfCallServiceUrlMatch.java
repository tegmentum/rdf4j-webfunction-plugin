package ai.tegmentum.rdf4j.webfunctions;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@link WfCallService#matchesWasmUrl(String)}. Locks
 * down the narrowing that keeps {@code SERVICE <http://.../query>}
 * URLs — the ones the wf_federation rewrite pass emits for SPARQL
 * sources — from being hijacked as wasm URLs and routed through
 * {@link WfCallService} (which then does a GET, gets non-wasm bytes,
 * and SILENT-swallows the failure). See {@code WfCallService} javadoc
 * for the full context.
 */
public class TestWfCallServiceUrlMatch {

    @Test
    public void fileAndIpfsUrisAlwaysMatch() {
        assertThat(WfCallService.matchesWasmUrl("file:///tmp/x.wasm")).isTrue();
        assertThat(WfCallService.matchesWasmUrl("file:///tmp/mystery-blob")).isTrue();
        assertThat(WfCallService.matchesWasmUrl("ipfs://Qmhash/mod.wasm")).isTrue();
        assertThat(WfCallService.matchesWasmUrl("ipfs://Qmhash/")).isTrue();
    }

    @Test
    public void httpWithWasmSuffixMatches() {
        assertThat(WfCallService.matchesWasmUrl("http://cdn.example/mod.wasm")).isTrue();
        assertThat(WfCallService.matchesWasmUrl("https://cdn.example/mod.wasm")).isTrue();
        // Query strings and fragments do not disqualify — the suffix
        // check operates on the path portion.
        assertThat(WfCallService.matchesWasmUrl("http://cdn.example/mod.wasm?v=1")).isTrue();
        assertThat(WfCallService.matchesWasmUrl("https://cdn.example/mod.wasm#sig")).isTrue();
        // `.component.wasm` naturally falls out of the `.wasm` check.
        assertThat(WfCallService.matchesWasmUrl("http://cdn.example/mod.component.wasm")).isTrue();
    }

    @Test
    public void httpWithoutWasmSuffixDoesNotMatch() {
        // These are what the wf_federation rewrite emits for
        // `type = "sparql"` sources — they must be routed to the
        // SPARQL fallback resolver, not treated as wasm bytes.
        assertThat(WfCallService.matchesWasmUrl("http://127.0.0.1:12345/query")).isFalse();
        assertThat(WfCallService.matchesWasmUrl("http://sparql.example/query")).isFalse();
        assertThat(WfCallService.matchesWasmUrl("https://sparql.example/query")).isFalse();
        // Even `wasm` appearing in the query string doesn't count
        // (the suffix check trims the query string first).
        assertThat(WfCallService.matchesWasmUrl("http://sparql.example/query?wasm=yes"))
                .isFalse();
    }

    @Test
    public void unknownSchemesDoNotMatch() {
        assertThat(WfCallService.matchesWasmUrl(null)).isFalse();
        assertThat(WfCallService.matchesWasmUrl("wf-search:manuals")).isFalse();
        assertThat(WfCallService.matchesWasmUrl("wf-invoke:0xabc")).isFalse();
        assertThat(WfCallService.matchesWasmUrl("gopher://x/")).isFalse();
    }
}

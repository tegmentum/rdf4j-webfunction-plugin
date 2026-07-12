package ai.tegmentum.rdf4j.webfunctions;

import org.junit.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Substrate-wide entry-point resolution parity check. Mirrors the
 * resolver in {@code oxigraph-wf/src/wf_call.rs::resolve_entry_point}
 * (commit {@code 3a92707}). The four-step order must hold:
 *
 * <ol>
 *   <li>caller override</li>
 *   <li>{@code evaluate} (substrate default)</li>
 *   <li>single top-level function export</li>
 *   <li>error listing visible exports</li>
 * </ol>
 */
public class TestEntryPointResolution {

    /**
     * Backwards-compat guarantee: every existing guest exports
     * {@code evaluate}, and it wins over any other export the resolver
     * happens to see.
     */
    @Test
    public void picksEvaluateWhenPresent() {
        final String resolved = EntryPointResolver.resolve(
                List.of("aggregate-step", "evaluate", "doc"), null);
        assertThat(resolved).isEqualTo("evaluate");
    }

    /**
     * The wf_fulltext guest exports {@code search} (its WIT world is
     * {@code wf:fulltext@0.1.0}, distinct from
     * {@code stardog:webfunction} — no {@code evaluate} to fall back to).
     * A guest that exports a single non-{@code evaluate} function
     * resolves to that function.
     */
    @Test
    public void picksSearchForWfFulltext() {
        final String resolved = EntryPointResolver.resolve(List.of("search"), null);
        assertThat(resolved).isEqualTo("search");
    }

    @Test
    public void errorsOnAmbiguousMultiExportGuest() {
        // No `evaluate`, and multiple top-level exports — the resolver
        // has no way to pick one and must surface the export list so
        // the caller can supply an override.
        assertThatThrownBy(() ->
                EntryPointResolver.resolve(List.of("search", "index-put"), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("multiple function exports")
                .hasMessageContaining("search")
                .hasMessageContaining("index-put");
    }

    /**
     * Caller-supplied override wins even when {@code evaluate} is
     * present — this is the escape hatch that lets rewrite passes
     * dispatch to a WIT world's alternate entry point.
     */
    @Test
    public void overrideWinsOverEvaluate() {
        final String resolved = EntryPointResolver.resolve(
                List.of("evaluate", "search"), "search");
        assertThat(resolved).isEqualTo("search");
    }

    /**
     * An empty (or unknown-empty) override string should NOT be treated
     * as a caller override — that's the same semantic as
     * {@code Option::None} on the Rust side.
     */
    @Test
    public void emptyOverrideFallsThroughToAutoDetect() {
        final String resolved = EntryPointResolver.resolve(
                List.of("evaluate"), "");
        assertThat(resolved).isEqualTo("evaluate");
    }

    @Test
    public void errorsOnEmptyExportList() {
        assertThatThrownBy(() -> EntryPointResolver.resolve(List.of(), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no function exports");
    }
}

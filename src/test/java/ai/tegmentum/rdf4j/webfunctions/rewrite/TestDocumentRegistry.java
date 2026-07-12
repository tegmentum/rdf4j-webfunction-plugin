package ai.tegmentum.rdf4j.webfunctions.rewrite;

import ai.tegmentum.rdf4j.webfunctions.rewrite.DocumentRegistry.DocumentIndex;
import ai.tegmentum.rdf4j.webfunctions.rewrite.DocumentRegistry.DocumentMode;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.junit.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Parity check for the document registry parser + lookup semantics.
 * Mirrors the shape of {@link TestFulltextRegistry}; validates §07 of
 * the design memo at {@code wf-conformance/docs/design/wf-document.md}.
 */
public class TestDocumentRegistry {

    private static DocumentRegistry parse(final String json) {
        // Let IllegalArgumentException surface untouched so tests can
        // assertThatThrownBy against it directly; only wrap the (rare)
        // Jackson parse errors so the harness doesn't have to declare
        // a broad throws clause.
        final JsonNode root;
        try {
            root = JsonMapper.builder().build().readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return DocumentRegistry.fromJson(root);
    }

    @Test
    public void parsesMinimalManagedEntry() {
        final DocumentRegistry reg = parse("""
                {
                  "documents": [{
                    "name": "manuals",
                    "mode": "managed",
                    "guest_url": "file:///opt/wf_document.wasm",
                    "search_backend": "http://localhost:9308",
                    "storage_backend": "http://localhost:8080",
                    "search_index": "manuals",
                    "sirix_database": "docs",
                    "sirix_resource": "manuals",
                    "revision_retention": "latest"
                  }]
                }""");
        assertThat(reg.size()).isEqualTo(1);
        final DocumentIndex e = reg.byName("manuals");
        assertThat(e).isNotNull();
        assertThat(e.mode()).isEqualTo(DocumentMode.MANAGED);
        assertThat(e.guestUrl()).isEqualTo("file:///opt/wf_document.wasm");
        assertThat(e.searchBackend()).isEqualTo("http://localhost:9308");
        assertThat(e.storageBackend()).isEqualTo("http://localhost:8080");
        assertThat(e.searchIndex()).isEqualTo("manuals");
        assertThat(e.sirixDatabase()).isEqualTo("docs");
        assertThat(e.sirixResource()).isEqualTo("manuals");
        assertThat(e.revisionRetention()).isEqualTo("latest");
        // Sweep interval omitted → OptionalInt.empty(); default is
        // DEFAULT_SWEEP_INTERVAL_SECS, applied at consumption time.
        assertThat(e.sweepIntervalSecs()).isEmpty();
        assertThat(e.optsJson()).isEqualTo("{}");
    }

    @Test
    public void parsesMinimalFederatedEntry() {
        final DocumentRegistry reg = parse("""
                {
                  "documents": [{
                    "name": "external",
                    "mode": "federated",
                    "guest_url": "file:///opt/wf_document.wasm",
                    "search_backend": "http://search.example/",
                    "storage_backend": "http://sirix.example/",
                    "search_index": "external",
                    "sirix_database": "external",
                    "sirix_resource": "external"
                  }]
                }""");
        assertThat(reg.size()).isEqualTo(1);
        final DocumentIndex e = reg.byName("external");
        assertThat(e).isNotNull();
        assertThat(e.mode()).isEqualTo(DocumentMode.FEDERATED);
        // Federated normalizes sweep + retention to empty/absent.
        assertThat(e.sweepIntervalSecs()).isEmpty();
        assertThat(e.revisionRetention()).isEmpty();
    }

    /** Design memo §07's verbatim single-entry example. */
    @Test
    public void parsesMemoExampleVerbatim() {
        final DocumentRegistry reg = parse("""
                {
                  "documents": [{
                    "name": "manuals",
                    "mode": "managed",
                    "guest_url": "file:///…/wf_document.wasm",
                    "search_backend": "http://localhost:9308",
                    "storage_backend": "http://localhost:8080",
                    "search_index": "manuals",
                    "sirix_database": "docs",
                    "sirix_resource": "manuals",
                    "sweep_interval_secs": 300,
                    "revision_retention": "latest"
                  }]
                }""");
        assertThat(reg.size()).isEqualTo(1);
        final DocumentIndex e = reg.byName("manuals");
        assertThat(e.mode()).isEqualTo(DocumentMode.MANAGED);
        assertThat(e.guestUrl()).isEqualTo("file:///…/wf_document.wasm");
        assertThat(e.searchBackend()).isEqualTo("http://localhost:9308");
        assertThat(e.storageBackend()).isEqualTo("http://localhost:8080");
        assertThat(e.searchIndex()).isEqualTo("manuals");
        assertThat(e.sirixDatabase()).isEqualTo("docs");
        assertThat(e.sirixResource()).isEqualTo("manuals");
        assertThat(e.sweepIntervalSecs()).hasValue(300);
        assertThat(e.revisionRetention()).isEqualTo("latest");
    }

    @Test
    public void byNameLookupHitsAndMisses() {
        final DocumentRegistry reg = parse("""
                {
                  "documents": [
                    {
                      "name": "a",
                      "mode": "managed",
                      "guest_url": "file:///g.wasm",
                      "search_backend": "s1",
                      "storage_backend": "st1",
                      "search_index": "a",
                      "sirix_database": "a",
                      "sirix_resource": "a",
                      "revision_retention": "latest"
                    },
                    {
                      "name": "b",
                      "mode": "federated",
                      "guest_url": "file:///g.wasm",
                      "search_backend": "s2",
                      "storage_backend": "st2",
                      "search_index": "b",
                      "sirix_database": "b",
                      "sirix_resource": "b"
                    }
                  ]
                }""");
        assertThat(reg.byName("a")).isNotNull();
        assertThat(reg.byName("a").mode()).isEqualTo(DocumentMode.MANAGED);
        assertThat(reg.byName("b")).isNotNull();
        assertThat(reg.byName("b").mode()).isEqualTo(DocumentMode.FEDERATED);
        assertThat(reg.byName("unknown")).isNull();
    }

    /**
     * managedEntries must skip FEDERATED — the periodic sweep is a
     * MANAGED-only affair (memo §08).
     */
    @Test
    public void managedEntriesSkipsFederated() {
        final DocumentRegistry reg = parse("""
                {
                  "documents": [
                    {
                      "name": "fed",
                      "mode": "federated",
                      "guest_url": "file:///g.wasm",
                      "search_backend": "s",
                      "storage_backend": "st",
                      "search_index": "fed",
                      "sirix_database": "fed",
                      "sirix_resource": "fed"
                    },
                    {
                      "name": "mgd",
                      "mode": "managed",
                      "guest_url": "file:///g.wasm",
                      "search_backend": "s",
                      "storage_backend": "st",
                      "search_index": "mgd",
                      "sirix_database": "mgd",
                      "sirix_resource": "mgd",
                      "revision_retention": "latest"
                    }
                  ]
                }""");
        final List<String> managedNames = reg.managedEntries().stream()
                .map(DocumentIndex::name)
                .toList();
        assertThat(managedNames).containsExactly("mgd");
    }

    @Test
    public void rejectsUnknownMode() {
        assertThatThrownBy(() -> parse("""
                {
                  "documents": [{
                    "name": "weird",
                    "mode": "hybrid",
                    "guest_url": "file:///g.wasm",
                    "search_backend": "s",
                    "storage_backend": "st",
                    "search_index": "weird",
                    "sirix_database": "weird",
                    "sirix_resource": "weird"
                  }]
                }"""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown mode")
                .hasMessageContaining("hybrid")
                .hasMessageContaining("weird");
    }

    @Test
    public void rejectsMissingName() {
        assertThatThrownBy(() -> parse("""
                {
                  "documents": [{
                    "mode": "managed",
                    "guest_url": "file:///g.wasm",
                    "search_backend": "s",
                    "storage_backend": "st",
                    "search_index": "x",
                    "sirix_database": "x",
                    "sirix_resource": "x",
                    "revision_retention": "latest"
                  }]
                }"""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    public void rejectsMissingMode() {
        assertThatThrownBy(() -> parse("""
                {
                  "documents": [{
                    "name": "orphan",
                    "guest_url": "file:///g.wasm",
                    "search_backend": "s",
                    "storage_backend": "st",
                    "search_index": "x",
                    "sirix_database": "x",
                    "sirix_resource": "x"
                  }]
                }"""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mode")
                .hasMessageContaining("orphan");
    }

    @Test
    public void rejectsMissingGuestUrl() {
        assertThatThrownBy(() -> parse("""
                {
                  "documents": [{
                    "name": "urlless",
                    "mode": "managed",
                    "search_backend": "s",
                    "storage_backend": "st",
                    "search_index": "x",
                    "sirix_database": "x",
                    "sirix_resource": "x",
                    "revision_retention": "latest"
                  }]
                }"""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("guest_url")
                .hasMessageContaining("urlless");
    }

    @Test
    public void rejectsMissingSearchBackend() {
        assertThatThrownBy(() -> parse("""
                {
                  "documents": [{
                    "name": "nosearch",
                    "mode": "managed",
                    "guest_url": "file:///g.wasm",
                    "storage_backend": "st",
                    "search_index": "x",
                    "sirix_database": "x",
                    "sirix_resource": "x",
                    "revision_retention": "latest"
                  }]
                }"""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("search_backend")
                .hasMessageContaining("nosearch");
    }

    @Test
    public void rejectsMissingStorageBackend() {
        assertThatThrownBy(() -> parse("""
                {
                  "documents": [{
                    "name": "nostorage",
                    "mode": "managed",
                    "guest_url": "file:///g.wasm",
                    "search_backend": "s",
                    "search_index": "x",
                    "sirix_database": "x",
                    "sirix_resource": "x",
                    "revision_retention": "latest"
                  }]
                }"""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("storage_backend")
                .hasMessageContaining("nostorage");
    }

    @Test
    public void rejectsMissingSearchIndex() {
        assertThatThrownBy(() -> parse("""
                {
                  "documents": [{
                    "name": "noindex",
                    "mode": "managed",
                    "guest_url": "file:///g.wasm",
                    "search_backend": "s",
                    "storage_backend": "st",
                    "sirix_database": "x",
                    "sirix_resource": "x",
                    "revision_retention": "latest"
                  }]
                }"""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("search_index")
                .hasMessageContaining("noindex");
    }

    @Test
    public void rejectsMissingSirixDatabase() {
        assertThatThrownBy(() -> parse("""
                {
                  "documents": [{
                    "name": "nodb",
                    "mode": "managed",
                    "guest_url": "file:///g.wasm",
                    "search_backend": "s",
                    "storage_backend": "st",
                    "search_index": "x",
                    "sirix_resource": "x",
                    "revision_retention": "latest"
                  }]
                }"""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sirix_database")
                .hasMessageContaining("nodb");
    }

    @Test
    public void rejectsMissingSirixResource() {
        assertThatThrownBy(() -> parse("""
                {
                  "documents": [{
                    "name": "nores",
                    "mode": "managed",
                    "guest_url": "file:///g.wasm",
                    "search_backend": "s",
                    "storage_backend": "st",
                    "search_index": "x",
                    "sirix_database": "x",
                    "revision_retention": "latest"
                  }]
                }"""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sirix_resource")
                .hasMessageContaining("nores");
    }

    @Test
    public void rejectsManagedMissingRevisionRetention() {
        assertThatThrownBy(() -> parse("""
                {
                  "documents": [{
                    "name": "noret",
                    "mode": "managed",
                    "guest_url": "file:///g.wasm",
                    "search_backend": "s",
                    "storage_backend": "st",
                    "search_index": "x",
                    "sirix_database": "x",
                    "sirix_resource": "x"
                  }]
                }"""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("revision_retention")
                .hasMessageContaining("noret");
    }

    /**
     * v1.0 lift: {@code "all"} is now accepted (memo §10). Time-travel
     * search rides on top of the retention=all sweep — the config parse
     * has to let it through so the operator can opt in.
     */
    @Test
    public void acceptsRevisionRetentionAll() {
        final DocumentRegistry reg = parse("""
                {
                  "documents": [{
                    "name": "greedy",
                    "mode": "managed",
                    "guest_url": "file:///g.wasm",
                    "search_backend": "s",
                    "storage_backend": "st",
                    "search_index": "x",
                    "sirix_database": "x",
                    "sirix_resource": "x",
                    "revision_retention": "all"
                  }]
                }""");
        final DocumentIndex e = reg.byName("greedy");
        assertThat(e).isNotNull();
        assertThat(e.revisionRetention()).isEqualTo("all");
    }

    @Test
    public void rejectsUnknownRevisionRetention() {
        assertThatThrownBy(() -> parse("""
                {
                  "documents": [{
                    "name": "weirdret",
                    "mode": "managed",
                    "guest_url": "file:///g.wasm",
                    "search_backend": "s",
                    "storage_backend": "st",
                    "search_index": "x",
                    "sirix_database": "x",
                    "sirix_resource": "x",
                    "revision_retention": "sometimes"
                  }]
                }"""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("revision_retention")
                .hasMessageContaining("sometimes")
                .hasMessageContaining("weirdret");
    }

    /**
     * Federated normalizes sweep + retention to empty/absent even if
     * the caller supplies them — those fields are meaningless when the
     * substrate doesn't own either backend.
     */
    @Test
    public void federatedNormalizesSweepAndRetention() {
        final DocumentRegistry reg = parse("""
                {
                  "documents": [{
                    "name": "fed",
                    "mode": "federated",
                    "guest_url": "file:///g.wasm",
                    "search_backend": "s",
                    "storage_backend": "st",
                    "search_index": "fed",
                    "sirix_database": "fed",
                    "sirix_resource": "fed",
                    "sweep_interval_secs": 42,
                    "revision_retention": "latest"
                  }]
                }""");
        final DocumentIndex e = reg.byName("fed");
        assertThat(e.sweepIntervalSecs()).isEmpty();
        assertThat(e.revisionRetention()).isEmpty();
    }

    @Test
    public void emptyRegistrySemantics() {
        final DocumentRegistry reg = DocumentRegistry.empty();
        assertThat(reg.isEmpty()).isTrue();
        assertThat(reg.size()).isZero();
        assertThat(reg.byName("whatever")).isNull();
        assertThat(reg.managedEntries()).isEmpty();
        assertThat(reg.entries()).isEmpty();
    }

    // -----------------------------------------------------------------
    // v1.0 window / tail retention policies (memo `wf-document-v1.md` §03)
    // -----------------------------------------------------------------

    private static String managedWithRetention(final String retentionJson) {
        return """
                {
                  "documents": [{
                    "name": "manuals",
                    "mode": "managed",
                    "guest_url": "file:///x.wasm",
                    "search_backend": "http://s/",
                    "storage_backend": "http://t/",
                    "search_index": "manuals",
                    "sirix_database": "d",
                    "sirix_resource": "manuals",
                    "sweep_interval_secs": 300,
                    "revision_retention": %s
                  }]
                }
                """.formatted(retentionJson);
    }

    @Test
    public void acceptsWindowDays() {
        final DocumentRegistry reg = parse(managedWithRetention("{\"window\": \"30d\"}"));
        assertThat(reg.byName("manuals").revisionRetention()).isEqualTo("window:30d");
    }

    @Test
    public void acceptsWindowHours() {
        final DocumentRegistry reg = parse(managedWithRetention("{\"window\": \"24h\"}"));
        assertThat(reg.byName("manuals").revisionRetention()).isEqualTo("window:24h");
    }

    @Test
    public void acceptsWindowMinutes() {
        final DocumentRegistry reg = parse(managedWithRetention("{\"window\": \"5m\"}"));
        assertThat(reg.byName("manuals").revisionRetention()).isEqualTo("window:5m");
    }

    @Test
    public void acceptsTailPositive() {
        final DocumentRegistry reg = parse(managedWithRetention("{\"tail\": 10}"));
        assertThat(reg.byName("manuals").revisionRetention()).isEqualTo("tail:10");
    }

    @Test
    public void rejectsWindowBadUnit() {
        assertThatThrownBy(() -> parse(managedWithRetention("{\"window\": \"30x\"}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown unit")
                .hasMessageContaining("30x")
                .hasMessageContaining("manuals");
    }

    @Test
    public void rejectsWindowEmpty() {
        assertThatThrownBy(() -> parse(managedWithRetention("{\"window\": \"\"}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be empty")
                .hasMessageContaining("manuals");
    }

    @Test
    public void rejectsTailZero() {
        assertThatThrownBy(() -> parse(managedWithRetention("{\"tail\": 0}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive integer")
                .hasMessageContaining("manuals");
    }

    @Test
    public void rejectsTailNegative() {
        assertThatThrownBy(() -> parse(managedWithRetention("{\"tail\": -1}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive integer")
                .hasMessageContaining("manuals");
    }

    @Test
    public void rejectsUnknownDiscriminant() {
        assertThatThrownBy(() -> parse(managedWithRetention("{\"unknown\": \"value\"}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown revision_retention discriminant")
                .hasMessageContaining("unknown")
                .hasMessageContaining("manuals");
    }

    /**
     * Opts pass through verbatim. Downstream guests parse the JSON
     * themselves so the substrate doesn't need to understand it.
     */
    @Test
    public void optsPassThroughVerbatim() {
        final DocumentRegistry reg = parse("""
                {
                  "documents": [{
                    "name": "withopts",
                    "mode": "managed",
                    "guest_url": "file:///g.wasm",
                    "search_backend": "s",
                    "storage_backend": "st",
                    "search_index": "withopts",
                    "sirix_database": "docs",
                    "sirix_resource": "withopts",
                    "opts": {"include_body": true, "highlight": true},
                    "revision_retention": "latest"
                  }]
                }""");
        final DocumentIndex e = reg.byName("withopts");
        assertThat(e.optsJson())
                .contains("include_body")
                .contains("highlight");
    }
}

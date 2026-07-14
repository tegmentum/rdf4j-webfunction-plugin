package ai.tegmentum.rdf4j.webfunctions.rewrite;

import ai.tegmentum.rdf4j.webfunctions.rewrite.FederationRegistry.FederationSource;
import ai.tegmentum.rdf4j.webfunctions.rewrite.FederationRegistry.SourceType;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.junit.Test;

import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Parity check for the federation registry parser + lookup semantics.
 * Mirrors the shape of {@link TestDocumentRegistry} and
 * {@link TestFulltextRegistry}; validates §03 of the design memo at
 * {@code wf-conformance/docs/design/wf-federation.md}.
 */
public class TestFederationRegistry {

    private static FederationRegistry parse(final String json) {
        final JsonNode root;
        try {
            root = JsonMapper.builder().build().readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return FederationRegistry.fromJson(root);
    }

    @Test
    public void parsesMinimalSparqlSource() {
        final FederationRegistry reg = parse("""
                {
                  "sources": [{
                    "name": "products",
                    "type": "sparql",
                    "endpoint": "http://oxigraph-products:7878/query"
                  }]
                }""");
        assertThat(reg.size()).isEqualTo(1);
        final FederationSource s = reg.byName("products");
        assertThat(s).isNotNull();
        assertThat(s.name()).isEqualTo("products");
        assertThat(s.sourceType()).isEqualTo(SourceType.SPARQL);
        assertThat(s.endpoint()).isEqualTo("http://oxigraph-products:7878/query");
        assertThat(s.predicates()).isEmpty();
        assertThat(s.probeTtlSecs()).isEmpty();
    }

    @Test
    public void parsesMemoExampleVerbatim() {
        // Verbatim §03 fixture — three sources, mixed types + predicate lists.
        final FederationRegistry reg = parse("""
                {
                  "sources": [
                    {
                      "name": "products",
                      "type": "sparql",
                      "endpoint": "http://oxigraph-products:7878/query",
                      "predicates": ["http://ex/sku", "http://ex/price", "http://ex/label"],
                      "probe_ttl_secs": 3600
                    },
                    {
                      "name": "reviews",
                      "type": "sparql",
                      "endpoint": "http://oxigraph-reviews:7878/query",
                      "predicates": ["http://ex/review_of", "http://ex/rating", "http://ex/reviewer"]
                    },
                    {
                      "name": "manuals-search",
                      "type": "wf-search",
                      "endpoint": "wf-search:manuals",
                      "predicates": []
                    }
                  ]
                }""");
        assertThat(reg.size()).isEqualTo(3);
        assertThat(reg.byName("products").probeTtlSecs()).hasValue(3600);
        assertThat(reg.byName("manuals-search").sourceType()).isEqualTo(SourceType.WF_SEARCH);
        assertThat(reg.byName("reviews").predicates()).hasSize(3);
    }

    @Test
    public void parsesAllSourceTypes() {
        final FederationRegistry reg = parse("""
                {
                  "sources": [
                    {"name": "a", "type": "sparql",       "endpoint": "http://a/"},
                    {"name": "b", "type": "wf-search",    "endpoint": "wf-search:b"},
                    {"name": "c", "type": "wf-fetch",     "endpoint": "wf-fetch:c"},
                    {"name": "d", "type": "wf-document",  "endpoint": "wf-document:d"},
                    {"name": "e", "type": "http-sparql",  "endpoint": "https://ext.example/query"}
                  ]
                }""");
        assertThat(reg.byName("a").sourceType()).isEqualTo(SourceType.SPARQL);
        assertThat(reg.byName("b").sourceType()).isEqualTo(SourceType.WF_SEARCH);
        assertThat(reg.byName("c").sourceType()).isEqualTo(SourceType.WF_FETCH);
        assertThat(reg.byName("d").sourceType()).isEqualTo(SourceType.WF_DOCUMENT);
        assertThat(reg.byName("e").sourceType()).isEqualTo(SourceType.HTTP_SPARQL);
    }

    @Test
    public void byNameHitsAndMisses() {
        final FederationRegistry reg = parse("""
                {"sources": [{"name": "x", "type": "sparql", "endpoint": "http://x/"}]}""");
        assertThat(reg.byName("x")).isNotNull();
        assertThat(reg.byName("y")).isNull();
    }

    @Test
    public void findByPredicateSingleSource() {
        final FederationRegistry reg = parse("""
                {"sources": [
                    {"name": "a", "type": "sparql", "endpoint": "http://a/",
                     "predicates": ["http://ex/p1"]}
                ]}""");
        final List<FederationSource> hits = reg.findByPredicate("http://ex/p1");
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).name()).isEqualTo("a");
    }

    @Test
    public void findByPredicateMultipleSources() {
        // Predicate declared by two sources → both come back so the
        // rewrite can emit a Union.
        final FederationRegistry reg = parse("""
                {"sources": [
                    {"name": "a", "type": "sparql", "endpoint": "http://a/",
                     "predicates": ["http://ex/shared"]},
                    {"name": "b", "type": "sparql", "endpoint": "http://b/",
                     "predicates": ["http://ex/shared"]}
                ]}""");
        final List<FederationSource> hits = reg.findByPredicate("http://ex/shared");
        assertThat(hits).extracting(FederationSource::name)
                .containsExactlyInAnyOrder("a", "b");
    }

    @Test
    public void findByPredicateMissReturnsEmpty() {
        final FederationRegistry reg = parse("""
                {"sources": [{"name": "a", "type": "sparql", "endpoint": "http://a/",
                              "predicates": ["http://ex/p1"]}]}""");
        assertThat(reg.findByPredicate("http://ex/nope")).isEmpty();
    }

    @Test
    public void sourcesReturnsAllInOrder() {
        final FederationRegistry reg = parse("""
                {"sources": [
                    {"name": "first",  "type": "sparql", "endpoint": "http://1/"},
                    {"name": "second", "type": "sparql", "endpoint": "http://2/"}
                ]}""");
        assertThat(reg.sources()).extracting(FederationSource::name)
                .containsExactly("first", "second");
    }

    @Test
    public void emptyRegistrySemantics() {
        final FederationRegistry reg = FederationRegistry.empty();
        assertThat(reg.isEmpty()).isTrue();
        assertThat(reg.size()).isZero();
        assertThat(reg.byName("whatever")).isNull();
        assertThat(reg.findByPredicate("http://ex/anything")).isEmpty();
        assertThat(reg.sources()).isEmpty();
    }

    @Test
    public void rejectsMissingName() {
        assertThatThrownBy(() -> parse("""
                {"sources": [{"type": "sparql", "endpoint": "http://x/"}]}"""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    public void rejectsMissingType() {
        assertThatThrownBy(() -> parse("""
                {"sources": [{"name": "orphan", "endpoint": "http://x/"}]}"""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("type")
                .hasMessageContaining("orphan");
    }

    @Test
    public void rejectsUnknownType() {
        assertThatThrownBy(() -> parse("""
                {"sources": [{"name": "weird", "type": "gopher",
                              "endpoint": "gopher://x/"}]}"""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown type")
                .hasMessageContaining("gopher")
                .hasMessageContaining("weird");
    }

    @Test
    public void rejectsMissingEndpoint() {
        assertThatThrownBy(() -> parse("""
                {"sources": [{"name": "noep", "type": "sparql"}]}"""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("endpoint")
                .hasMessageContaining("noep");
    }

    @Test
    public void rejectsDuplicateNames() {
        assertThatThrownBy(() -> parse("""
                {"sources": [
                    {"name": "dup", "type": "sparql", "endpoint": "http://1/"},
                    {"name": "dup", "type": "sparql", "endpoint": "http://2/"}
                ]}"""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate name")
                .hasMessageContaining("dup");
    }

    @Test
    public void ofBuildsFromInMemorySources() {
        // Non-JSON factory path — used by tests and future non-JSON loaders.
        final FederationSource a = new FederationSource(
                "a", SourceType.SPARQL, "http://a/",
                List.of("http://ex/p1"), OptionalInt.empty());
        final FederationSource b = new FederationSource(
                "b", SourceType.WF_SEARCH, "wf-search:b",
                List.of(), OptionalInt.of(120));
        final FederationRegistry reg = FederationRegistry.of(List.of(a, b));
        assertThat(reg.size()).isEqualTo(2);
        assertThat(reg.byName("b").probeTtlSecs()).hasValue(120);
        assertThat(reg.findByPredicate("http://ex/p1"))
                .extracting(FederationSource::name)
                .containsExactly("a");
    }

    // ---------------------------------------------------------------------
    // v0.2 cost model — cardinality hints
    // ---------------------------------------------------------------------

    @Test
    public void cardinalityHintParsesAndSurvivesRoundtrip() {
        final FederationRegistry reg = parse("""
                {"sources": [{
                    "name": "products",
                    "type": "sparql",
                    "endpoint": "http://ex/query",
                    "predicates": ["http://ex/sku"],
                    "cardinality_hint": 5000
                }]}""");
        final FederationSource e = reg.byName("products");
        assertThat(e.cardinalityHint()).hasValue(5000L);
        assertThat(e.cardinalityFor("http://ex/sku")).hasValue(5000L);
        // Missing predicate falls back to source-wide hint.
        assertThat(e.cardinalityFor("http://ex/other")).hasValue(5000L);
    }

    @Test
    public void perPredicateCardinalityHintsOverrideSourceHint() {
        final FederationRegistry reg = parse("""
                {"sources": [{
                    "name": "products",
                    "type": "sparql",
                    "endpoint": "http://ex/query",
                    "predicates": ["http://ex/sku", "http://ex/label"],
                    "cardinality_hint": 5000,
                    "cardinality_hints": {"http://ex/sku": 100}
                }]}""");
        final FederationSource e = reg.byName("products");
        assertThat(e.cardinalityFor("http://ex/sku")).hasValue(100L);
        assertThat(e.cardinalityFor("http://ex/label")).hasValue(5000L);
    }

    @Test
    public void cardinalityAbsentReturnsEmpty() {
        final FederationRegistry reg = parse("""
                {"sources": [{
                    "name": "products",
                    "type": "sparql",
                    "endpoint": "http://ex/query",
                    "predicates": ["http://ex/sku"]
                }]}""");
        assertThat(reg.byName("products").cardinalityFor("http://ex/sku")).isEmpty();
    }

    // ---------------------------------------------------------------------
    // v0.2 probe mode
    // ---------------------------------------------------------------------

    @Test
    public void probeModeParsesFromJsonRoot() {
        final FederationRegistry reg = parse("""
                {
                    "probe_mode": true,
                    "probe_ttl_secs": 300,
                    "sources": [{
                        "name": "s", "type": "sparql", "endpoint": "http://ex"
                    }]
                }""");
        assertThat(reg.probeMode()).isTrue();
        assertThat(reg.probeTtlSecs()).isEqualTo(300);
    }

    @Test
    public void probeModeDefaultsOffWhenAbsent() {
        final FederationRegistry reg = parse("""
                {"sources": [{
                    "name": "s", "type": "sparql", "endpoint": "http://ex"
                }]}""");
        assertThat(reg.probeMode()).isFalse();
        assertThat(reg.probeTtlSecs()).isEqualTo(3600);
    }

    @Test
    public void probeCacheHitAvoidsReprobe() throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        final FederationRegistry reg = parse("""
                {
                    "probe_mode": true,
                    "sources": [{
                        "name": "s",
                        "type": "sparql",
                        "endpoint": "http://ex/query"
                    }]
                }""").withProbeFn((src, pred) -> {
                    calls.incrementAndGet();
                    return true;
                });
        final FederationSource src = reg.byName("s");
        assertThat(reg.probePredicate(src, "http://ex/p")).isTrue();
        assertThat(reg.probePredicate(src, "http://ex/p")).isTrue();
        assertThat(calls.get()).as("cache hit must skip re-probe").isEqualTo(1);
    }

    @Test
    public void probeCacheTtlExpiryReprobes() throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        // 0-second TTL — every lookup is stale.
        final FederationRegistry reg = parse("""
                {
                    "probe_mode": true,
                    "probe_ttl_secs": 0,
                    "sources": [{
                        "name": "s",
                        "type": "sparql",
                        "endpoint": "http://ex/query"
                    }]
                }""").withProbeFn((src, pred) -> {
                    calls.incrementAndGet();
                    return true;
                });
        final FederationSource src = reg.byName("s");
        reg.probePredicate(src, "http://ex/p");
        // Sleep 5ms past 0 to ensure elapsed > 0-second TTL.
        Thread.sleep(5);
        reg.probePredicate(src, "http://ex/p");
        assertThat(calls.get()).as("TTL expiry must trigger re-probe").isEqualTo(2);
    }

    @Test
    public void probeEndpointDownSurfacesError() {
        final FederationRegistry reg = parse("""
                {
                    "probe_mode": true,
                    "sources": [{
                        "name": "s",
                        "type": "sparql",
                        "endpoint": "http://ex/query"
                    }]
                }""").withProbeFn((src, pred) -> {
                    throw new java.net.ConnectException("connection refused");
                });
        final FederationSource src = reg.byName("s");
        assertThatThrownBy(() -> reg.probePredicate(src, "http://ex/p"))
                .isInstanceOf(java.net.ConnectException.class)
                .hasMessageContaining("connection refused");
    }

    @Test
    public void probeModeExtendsFindByPredicate() {
        // 'declared' has ex:p statically; 'undeclared' does not but
        // probes true. findByPredicateProbing surfaces both.
        final FederationRegistry reg = parse("""
                {
                    "probe_mode": true,
                    "sources": [
                        {"name": "declared", "type": "sparql",
                         "endpoint": "http://a/query",
                         "predicates": ["http://ex/p"]},
                        {"name": "undeclared", "type": "sparql",
                         "endpoint": "http://b/query"}
                    ]
                }""").withProbeFn((src, pred) -> "undeclared".equals(src.name()));
        final List<FederationSource> hits = reg.findByPredicateProbing("http://ex/p");
        assertThat(hits).extracting(FederationSource::name)
                .containsExactlyInAnyOrder("declared", "undeclared");
    }

    @Test
    public void probeModeOffIsStaticOnly() {
        final FederationRegistry reg = parse("""
                {"sources": [
                    {"name": "a", "type": "sparql", "endpoint": "http://a",
                     "predicates": ["http://ex/p"]}
                ]}""");
        // findByPredicateProbing matches findByPredicate when probe mode is off.
        assertThat(reg.findByPredicateProbing("http://ex/p"))
                .extracting(FederationSource::name)
                .containsExactly("a");
    }
}

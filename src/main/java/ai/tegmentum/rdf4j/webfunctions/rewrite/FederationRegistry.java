package ai.tegmentum.rdf4j.webfunctions.rewrite;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Planner-side catalog of federatable sources. Mirrors §03 and §11 of
 * the design memo at {@code wf-conformance/docs/design/wf-federation.md}.
 * v0.1 is static-only: the registry declares, per source, the endpoint,
 * the source type, and (optionally) a predicate coverage list; the
 * federation rewrite pass looks up patterns by predicate and dispatches
 * to the assigned source.
 *
 * <ul>
 *   <li><b>{@link SourceType#SPARQL}</b> &mdash; plain SPARQL 1.1
 *       endpoint owned/adjacent to the substrate.</li>
 *   <li><b>{@link SourceType#WF_SEARCH}</b> /
 *       <b>{@link SourceType#WF_FETCH}</b> /
 *       <b>{@link SourceType#WF_DOCUMENT}</b> &mdash; substrate URL-sugar
 *       sources ({@code wf-search:<name>}, {@code wf-fetch:<name>},
 *       {@code wf-document:<name>}) expanded further by the sibling
 *       {@code WfSearchRewrite} etc. passes.</li>
 *   <li><b>{@link SourceType#HTTP_SPARQL}</b> &mdash; external SPARQL
 *       endpoint reached over HTTP with unpredictable latency (memo §07
 *       cost table).</li>
 * </ul>
 *
 * <p>Read-only after load; an empty registry (no {@code --federation-config}
 * flag) is a valid state and every consumer treats it as an unconditional
 * no-op. Identical lifecycle to {@link DocumentRegistry} and
 * {@link FulltextRegistry}.
 */
public final class FederationRegistry {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    /** Discriminates the substrate-dispatch shape for a source (memo §03). */
    public enum SourceType {
        /** Plain SPARQL 1.1 endpoint. */
        SPARQL,
        /** Substrate wf-search URL-sugar source. */
        WF_SEARCH,
        /** Substrate wf-fetch URL-sugar source. */
        WF_FETCH,
        /** Substrate wf-document URL-sugar source. */
        WF_DOCUMENT,
        /**
         * Substrate wf-vector URL-sugar source
         * (wf-conformance/docs/design/wf-vector.md &sect;04). RDF4J has
         * no native vector index in v0.1; the federation pass still
         * emits {@code SERVICE <wf-vector:<name>?...>} for entries of
         * this type, but the URL stays unfolded and will error unless
         * a wf-vector-capable backend is federated in some other way.
         * See memo &sect;10 for the v0.2+ path to a native co-located
         * index on RDF4J.
         */
        WF_VECTOR,
        /**
         * Substrate {@code wf-relational:} URL sugar (wf-relational
         * design memo &sect;04). {@link FederationSource#endpoint()}
         * carries a {@code postgres://…/db} URL &mdash; Postgres is
         * the only v0.1 backend. The federation pass emits
         * {@code SERVICE <wf-relational:<name>>} and defers dispatch
         * to {@code wf_fetch}, whose shape descriptor's
         * {@code sink_kind = "postgres"} tells the guest to speak
         * Postgres-SQL. RDF4J ships the registry plumbing in v0.1;
         * end-to-end dispatch is gated on the host sink layer
         * growing a Postgres backend (memo &sect;11 step 2).
         */
        WF_RELATIONAL,
        /**
         * Substrate {@code wf-sagegraph:} URL sugar (wf-sagegraph design
         * memo &sect;04). RDF4J has no native sagegraph embedder in v0.1;
         * the federation pass still emits
         * {@code SERVICE <wf-sagegraph:<name>?node=<uri>&k=<n>>} for
         * entries of this type, but the URL stays unfolded and will
         * error unless a wf-sagegraph-capable backend is federated in
         * some other way. v0.1 ships end-to-end coverage only on
         * Oxigraph's embedded degree-features embedder (memo &sect;13).
         */
        WF_SAGEGRAPH,
        /** External SPARQL endpoint reached over HTTP. */
        HTTP_SPARQL
    }

    private final List<FederationSource> sources;
    /** Name &rarr; index-in-{@code sources} for administrative lookup. */
    private final Map<String, Integer> nameToSource;
    /**
     * Predicate IRI &rarr; list of source indexes. A predicate may be
     * declared by more than one source; the rewrite pass turns those into
     * a UNION of per-source SERVICE clauses (memo §04 step 2).
     */
    private final Map<String, List<Integer>> predicateToSources;
    /**
     * v0.2 probe-mode toggle. When true,
     * {@link #findByPredicateProbing(String)} consults the probe cache
     * (and issues fresh ASK queries via {@link ProbeFn} on a miss) in
     * addition to the static predicate index.
     */
    private boolean probeMode;
    /**
     * Registry-wide default probe TTL (seconds). Per-source
     * {@link FederationSource#probeTtlSecs()} overrides this when set.
     */
    private long probeTtlSecs = 3600;
    private final ProbeCache probeCache = new ProbeCache();
    private ProbeFn probeFn;

    private FederationRegistry(final List<FederationSource> sources,
                               final Map<String, Integer> nameToSource,
                               final Map<String, List<Integer>> predicateToSources) {
        this.sources = List.copyOf(sources);
        this.nameToSource = Map.copyOf(nameToSource);
        // Freeze the inner lists too so callers can't mutate the index.
        final Map<String, List<Integer>> frozen = new HashMap<>();
        for (Map.Entry<String, List<Integer>> e : predicateToSources.entrySet()) {
            frozen.put(e.getKey(), List.copyOf(e.getValue()));
        }
        this.predicateToSources = Map.copyOf(frozen);
    }

    /**
     * Empty registry &mdash; the uninstrumented-startup state. Every
     * lookup is a no-op; {@link #isEmpty()} returns true.
     */
    public static FederationRegistry empty() {
        return new FederationRegistry(List.of(), Map.of(), Map.of());
    }

    /** v0.2 probe-mode toggle accessor. */
    public boolean probeMode() { return probeMode; }

    /** v0.2 registry-wide default probe TTL, seconds. */
    public long probeTtlSecs() { return probeTtlSecs; }

    /** Shared probe cache. Callers may warm or inspect it. */
    public ProbeCache probeCache() { return probeCache; }

    /**
     * Install a probe function. Chained builder &mdash; production
     * wires an ASK-probe implementation; tests wire a mock.
     */
    public FederationRegistry withProbeFn(final ProbeFn fn) {
        this.probeFn = fn;
        return this;
    }

    /**
     * Flip probe mode on/off programmatically. Usually the flag comes
     * from JSON via {@code probe_mode: true}; this accessor is for tests
     * and other programmatic construction.
     */
    public FederationRegistry withProbeMode(final boolean on) {
        this.probeMode = on;
        return this;
    }

    /**
     * v0.2 probe entry point &mdash; check whether {@code source}
     * covers {@code predicateIri}. Consults the cache first (with the
     * per-source TTL when set, else the registry-wide default); on
     * miss dispatches through the injected probe function and caches
     * the result. Throws on transport / protocol failure &mdash;
     * callers treat that as "skip this source for this plan" per memo
     * &sect;04.
     */
    public boolean probePredicate(final FederationSource source, final String predicateIri)
            throws Exception {
        final Duration ttl = source.probeTtlSecs().isPresent()
                ? Duration.ofSeconds(source.probeTtlSecs().getAsInt())
                : Duration.ofSeconds(probeTtlSecs);
        final Optional<Boolean> cached = probeCache.get(source.name(), predicateIri, ttl);
        if (cached.isPresent()) return cached.get();
        if (probeFn == null) {
            throw new IllegalStateException("no probe function configured");
        }
        final boolean has = probeFn.probe(source, predicateIri);
        probeCache.put(source.name(), predicateIri, has);
        return has;
    }

    /**
     * v0.2 probe-mode augmented find. Same shape as
     * {@link #findByPredicate(String)} (statically-declared coverage);
     * when {@link #probeMode()} is on also asks every registered source
     * without declared coverage via {@link #probePredicate}. Silently
     * drops sources whose probe throws (endpoint down / auth) &mdash;
     * memo &sect;04 skip-and-log.
     */
    public List<FederationSource> findByPredicateProbing(final String iri) {
        final List<FederationSource> hits = new ArrayList<>(findByPredicate(iri));
        if (!probeMode) return hits;
        final Set<String> already = new HashSet<>();
        for (FederationSource s : hits) already.add(s.name());
        for (FederationSource entry : sources) {
            if (already.contains(entry.name())) continue;
            try {
                if (probePredicate(entry, iri)) {
                    hits.add(entry);
                }
            } catch (Exception e) {
                // Probe failure: skip this source for this plan
                // (memo §04). Log at stderr so operators can trace
                // flap patterns without flooding.
                System.err.println("wf_federation probe: source `" + entry.name()
                        + "` predicate `" + iri + "` failed: " + e.getMessage());
            }
        }
        return hits;
    }

    public boolean isEmpty() { return sources.isEmpty(); }
    public int size()        { return sources.size(); }

    /** Administrative lookup — resolve a source by its {@code name} field. */
    public FederationSource byName(final String name) {
        final Integer idx = nameToSource.get(name);
        return idx == null ? null : sources.get(idx);
    }

    /**
     * Return every source whose {@code predicates} list declares
     * {@code iri}. Predicate can be in multiple sources (memo §04 step
     * 2) — the caller decides between the unambiguous, multi-source, or
     * unregistered branches. Returns an empty list when no source
     * declares the predicate.
     */
    public List<FederationSource> findByPredicate(final String iri) {
        final List<Integer> idxs = predicateToSources.get(iri);
        if (idxs == null || idxs.isEmpty()) return List.of();
        final List<FederationSource> out = new ArrayList<>(idxs.size());
        for (int i : idxs) out.add(sources.get(i));
        return Collections.unmodifiableList(out);
    }

    /** All registered sources, insertion order. */
    public List<FederationSource> sources() { return sources; }

    /**
     * Build a registry from in-memory sources. Convenient for tests and
     * non-JSON loaders. The name and predicate indexes are derived;
     * duplicate names are rejected.
     */
    public static FederationRegistry of(final Iterable<FederationSource> sourcesIn) {
        final List<FederationSource> list = new ArrayList<>();
        final Map<String, Integer> nameToSource = new HashMap<>();
        final Map<String, List<Integer>> predicateToSources = new HashMap<>();
        for (FederationSource s : sourcesIn) {
            final int idx = list.size();
            if (nameToSource.put(s.name(), idx) != null) {
                throw new IllegalArgumentException(
                        "federation registry source `" + s.name() + "`: duplicate name");
            }
            for (String pred : s.predicates()) {
                predicateToSources.computeIfAbsent(pred, k -> new ArrayList<>()).add(idx);
            }
            list.add(s);
        }
        return new FederationRegistry(list, nameToSource, predicateToSources);
    }

    /**
     * Load a registry from the JSON shape declared in §03 of the design
     * memo. An absent file is an error; empty-registry semantics are
     * what {@link #empty()} (no CLI flag) gives you, not what a missing
     * config file gives you.
     */
    public static FederationRegistry loadFromJson(final Path path) throws IOException {
        final String text = Files.readString(path);
        final JsonNode root;
        try {
            root = MAPPER.readTree(text);
        } catch (RuntimeException e) {
            throw new IOException(
                    "parsing federation registry at " + path + ": " + e.getMessage(), e);
        }
        return fromJson(root);
    }

    /**
     * Parse a registry from an already-decoded {@link JsonNode}.
     * Extracted so unit tests can drive the parser without hitting the
     * filesystem.
     */
    public static FederationRegistry fromJson(final JsonNode root) {
        final JsonNode sources = root.path("sources");
        final List<FederationSource> parsed = new ArrayList<>();
        if (sources != null && sources.isArray()) {
            for (JsonNode raw : sources) {
                parsed.add(parseSource(raw));
            }
        }
        final FederationRegistry reg = of(parsed);
        // v0.2 additions — root-level probe_mode / probe_ttl_secs. Absent
        // = static-only (v0.1 behavior); TTL defaults to 3600s.
        if (root.hasNonNull("probe_mode")) {
            reg.probeMode = root.get("probe_mode").asBoolean();
        }
        if (root.hasNonNull("probe_ttl_secs")) {
            reg.probeTtlSecs = root.get("probe_ttl_secs").asLong();
        }
        return reg;
    }

    private static FederationSource parseSource(final JsonNode raw) {
        final String name;
        if (raw.hasNonNull("name")) {
            name = raw.get("name").asString();
        } else {
            throw new IllegalArgumentException(
                    "federation registry source missing required field `name`");
        }
        if (!raw.hasNonNull("type")) {
            throw new IllegalArgumentException(
                    "federation registry source `" + name + "`: missing required field `type`");
        }
        final String typeStr = raw.get("type").asString();
        final SourceType type = switch (typeStr) {
            case "sparql", "SPARQL"           -> SourceType.SPARQL;
            case "wf-search", "wf_search"     -> SourceType.WF_SEARCH;
            case "wf-fetch", "wf_fetch"       -> SourceType.WF_FETCH;
            case "wf-document", "wf_document" -> SourceType.WF_DOCUMENT;
            case "wf-vector", "wf_vector" -> SourceType.WF_VECTOR;
            case "wf-relational", "wf_relational" -> SourceType.WF_RELATIONAL;
            case "wf-sagegraph", "wf_sagegraph" -> SourceType.WF_SAGEGRAPH;
            case "http-sparql", "http_sparql" -> SourceType.HTTP_SPARQL;
            default -> throw new IllegalArgumentException(
                    "federation registry source `" + name + "`: unknown type `" + typeStr
                            + "` (expected `sparql`, `wf-search`, `wf-fetch`, `wf-document`, "
                            + "`wf-vector`, `wf-relational`, `wf-sagegraph`, or `http-sparql`)");
        };
        if (!raw.hasNonNull("endpoint")) {
            throw new IllegalArgumentException(
                    "federation registry source `" + name + "`: missing required field `endpoint`");
        }
        final String endpoint = raw.get("endpoint").asString();

        final List<String> predicates = new ArrayList<>();
        final JsonNode predsNode = raw.path("predicates");
        if (predsNode != null && predsNode.isArray()) {
            for (JsonNode p : predsNode) predicates.add(p.asString());
        }

        final OptionalInt probeTtl = raw.hasNonNull("probe_ttl_secs")
                ? OptionalInt.of(raw.get("probe_ttl_secs").asInt())
                : OptionalInt.empty();

        final Optional<Boolean> silent;
        if (raw.hasNonNull("silent")) {
            final JsonNode silentNode = raw.get("silent");
            if (!silentNode.isBoolean()) {
                throw new IllegalArgumentException(
                        "federation registry source `" + name
                                + "`: `silent` must be a boolean");
            }
            silent = Optional.of(silentNode.asBoolean());
        } else {
            silent = Optional.empty();
        }

        // v0.2 cost model — source-wide and per-predicate cardinality
        // hints. Absent means "unknown"; the rewrite pass sorts
        // unknown-cardinality sources last.
        final OptionalLong cardinalityHint = raw.hasNonNull("cardinality_hint")
                ? OptionalLong.of(raw.get("cardinality_hint").asLong())
                : OptionalLong.empty();
        final Map<String, Long> cardinalityHints;
        final JsonNode hintsNode = raw.path("cardinality_hints");
        if (hintsNode != null && hintsNode.isObject()) {
            final Map<String, Long> tmp = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> prop : hintsNode.properties()) {
                final JsonNode v = prop.getValue();
                if (v != null && v.isNumber()) {
                    tmp.put(prop.getKey(), v.asLong());
                }
            }
            cardinalityHints = tmp;
        } else {
            cardinalityHints = Map.of();
        }

        // v0.3 wf-relational extension — capture the top-level
        // `relational` block on the source entry. Absent for every
        // non-relational source (and for wf-relational sources that
        // ship without a descriptor). Silent capture regardless of
        // source type; WfRelationalRewrite gates use on
        // sourceType == WF_RELATIONAL.
        final Optional<RelationalConfig> relationalConfig =
                raw.hasNonNull("relational")
                        ? Optional.of(parseRelationalConfig(raw.get("relational")))
                        : Optional.empty();

        return new FederationSource(name, type, endpoint, predicates, probeTtl, silent,
                cardinalityHint, cardinalityHints, relationalConfig);
    }

    private static RelationalConfig parseRelationalConfig(final JsonNode raw) {
        final String sinkKind = raw.path("sink_kind").asString("postgres");
        final String table = raw.path("table").asString("");
        final String subjectColumn = raw.path("subject_column").asString("");
        final String anchorClass = raw.path("anchor").hasNonNull("class")
                ? raw.get("anchor").get("class").asString()
                : null;
        final List<Column> cols = new ArrayList<>();
        final JsonNode columns = raw.path("columns");
        if (columns != null && columns.isArray()) {
            for (JsonNode c : columns) {
                final String cn = c.path("name").asString("");
                final String role = c.path("role").asString("");
                final String predicate = c.hasNonNull("predicate")
                        ? c.get("predicate").asString() : null;
                final String xsdType = c.hasNonNull("type")
                        ? c.get("type").asString() : null;
                cols.add(new Column(cn, role, predicate, xsdType));
            }
        }
        final String iriTemplate = raw.hasNonNull("iri_template")
                ? raw.get("iri_template").asString() : null;
        final boolean emitProvenance = raw.path("emit_provenance").asBoolean(false);
        final String schemaVersion = raw.hasNonNull("schema_version")
                ? raw.get("schema_version").asString() : null;
        return new RelationalConfig(sinkKind, table, subjectColumn, new Anchor(anchorClass),
                cols, iriTemplate, emitProvenance, schemaVersion);
    }

    /**
     * One registered federation source. Mirrors the JSON shape declared
     * in §03 of the design memo.
     */
    public static final class FederationSource {
        private final String name;
        private final SourceType sourceType;
        private final String endpoint;
        private final List<String> predicates;
        private final OptionalInt probeTtlSecs;
        private final Optional<Boolean> silent;
        private final OptionalLong cardinalityHint;
        private final Map<String, Long> cardinalityHints;
        private final Optional<RelationalConfig> relationalConfig;

        /**
         * Legacy five-field constructor kept for callers that don't
         * need to specify a silent override. Delegates with
         * {@link Optional#empty()} so the rewrite pass falls back to
         * the per-source-type default (memo &sect;08).
         */
        public FederationSource(final String name,
                                final SourceType sourceType,
                                final String endpoint,
                                final List<String> predicates,
                                final OptionalInt probeTtlSecs) {
            this(name, sourceType, endpoint, predicates, probeTtlSecs, Optional.empty());
        }

        /**
         * Six-field constructor &mdash; predates v0.2's cost-model
         * plumbing. Delegates with empty cardinality hints (source
         * sorts last under the unknown-sort-last rule).
         */
        public FederationSource(final String name,
                                final SourceType sourceType,
                                final String endpoint,
                                final List<String> predicates,
                                final OptionalInt probeTtlSecs,
                                final Optional<Boolean> silent) {
            this(name, sourceType, endpoint, predicates, probeTtlSecs, silent,
                    OptionalLong.empty(), Map.of());
        }

        /**
         * Eight-field constructor &mdash; predates v0.3's wf-relational
         * unification. Delegates with an empty {@link #relationalConfig()}
         * so non-relational sources keep their existing wire shape.
         */
        public FederationSource(final String name,
                                final SourceType sourceType,
                                final String endpoint,
                                final List<String> predicates,
                                final OptionalInt probeTtlSecs,
                                final Optional<Boolean> silent,
                                final OptionalLong cardinalityHint,
                                final Map<String, Long> cardinalityHints) {
            this(name, sourceType, endpoint, predicates, probeTtlSecs, silent,
                    cardinalityHint, cardinalityHints, Optional.empty());
        }

        public FederationSource(final String name,
                                final SourceType sourceType,
                                final String endpoint,
                                final List<String> predicates,
                                final OptionalInt probeTtlSecs,
                                final Optional<Boolean> silent,
                                final OptionalLong cardinalityHint,
                                final Map<String, Long> cardinalityHints,
                                final Optional<RelationalConfig> relationalConfig) {
            this.name = name;
            this.sourceType = sourceType;
            this.endpoint = endpoint;
            this.predicates = List.copyOf(predicates);
            this.probeTtlSecs = probeTtlSecs;
            this.silent = silent;
            this.cardinalityHint = cardinalityHint;
            this.cardinalityHints = Map.copyOf(cardinalityHints);
            this.relationalConfig = relationalConfig;
        }

        public String name()                   { return name; }
        public SourceType sourceType()         { return sourceType; }
        public String endpoint()               { return endpoint; }
        public List<String> predicates()       { return predicates; }
        /**
         * v0.2 probe cache TTL hint (memo §02). v0.1 doesn't use this
         * value at runtime but the parser accepts + preserves it so
         * operator configs written today keep working when probe mode
         * lands.
         */
        public OptionalInt probeTtlSecs()      { return probeTtlSecs; }
        /**
         * Optional override for {@code SERVICE SILENT} semantics on
         * this source's emitted {@code SERVICE} clauses.
         * {@link Optional#empty()} means "use the per-source-type
         * default" &mdash; SPARQL / HTTP_SPARQL default to silent
         * (network endpoints; transport errors degrade to empty
         * bindings without probing); WF_SEARCH / WF_FETCH / WF_DOCUMENT
         * default to non-silent (substrate-local dispatch; a failure
         * is a real bug the operator should see). Explicit value wins.
         * See design memo &sect;08 for the resolution rule.
         */
        public Optional<Boolean> silent()      { return silent; }
        /**
         * v0.2 cost model &mdash; source-wide cardinality hint
         * (approximate row count the source returns per pattern). Used
         * by the rewrite pass to reorder emitted SERVICE clauses
         * smaller-first (memo &sect;04 step 4 / &sect;07). Empty means
         * "unknown"; unknown-cardinality sources sort last. Per-predicate
         * hints in {@link #cardinalityHints()} win over this value when
         * they match.
         */
        public OptionalLong cardinalityHint()  { return cardinalityHint; }
        /**
         * v0.2 cost model &mdash; per-predicate cardinality hints. Keys
         * are predicate IRIs. Consulted first by
         * {@link #cardinalityFor(String)}; on a miss the lookup falls
         * back to {@link #cardinalityHint()} and then to
         * unknown-sort-last.
         */
        public Map<String, Long> cardinalityHints() { return cardinalityHints; }

        /**
         * v0.2 cost model &mdash; best cardinality estimate for
         * {@code predicateIri} on this source. Per-predicate hint wins
         * over the source-wide hint; empty means "unknown" and the
         * rewrite pass sorts unknown-cardinality sources last.
         */
        public OptionalLong cardinalityFor(final String predicateIri) {
            if (cardinalityHints.containsKey(predicateIri)) {
                return OptionalLong.of(cardinalityHints.get(predicateIri));
            }
            return cardinalityHint;
        }

        /**
         * v0.3 extension &mdash; {@code wf-relational} source shape
         * descriptor. Populated from the JSON top-level {@code relational}
         * key on the source entry (adapter renders it in
         * {@code render_relational_descriptor}).
         * {@link Optional#empty()} for every non-{@code wf-relational}
         * source, and also for {@code wf-relational} sources that ship
         * without a descriptor block (the {@link WfRelationalRewrite}
         * pass short-circuits those).
         *
         * <p>Design memo: {@code wf-conformance/docs/design/wf-relational.md}
         * &sect;04. Prior to v0.3 this lived in a sidecar
         * {@code WfRelationalRegistry}; the sidecar was folded into
         * {@link FederationSource} so all per-source state travels
         * together. Future extension source types ({@code wf-vector},
         * {@code wf-search}) will follow the same pattern &mdash;
         * first-class optional field, discoverable at the type level.
         */
        public Optional<RelationalConfig> relationalConfig() { return relationalConfig; }
    }

    // ---------------------------------------------------------------------
    // v0.3 wf-relational shape descriptor.
    //
    // The federation-config JSON's `wf-relational` sources carry an
    // optional `relational` block that the WfRelationalRewrite pass
    // needs to build the wf_fetch descriptor (adapter emits it under
    // the key `relational`). Prior to v0.3 this lived in a sibling
    // WfRelationalRegistry that re-parsed the same file; folding it
    // into FederationSource unifies the two registries so all
    // per-source state travels together and future extension source
    // types (wf-vector, wf-search) can follow the same pattern.
    // ---------------------------------------------------------------------

    /**
     * Shape descriptor block for a {@code wf-relational} federation
     * source. Mirrors the JSON the adapter emits under the
     * {@code relational} key on the source entry &mdash; see
     * {@code wf-conformance/src/adapter/mod.rs::render_relational_descriptor}.
     *
     * <p>Kept as a proper struct (not opaque JSON) so the rewrite pass
     * gets typed access to {@link #columnsByPredicate()} for the BGP
     * fold.
     */
    public static final class RelationalConfig {
        private final String sinkKind;
        private final String table;
        private final String subjectColumn;
        private final Anchor anchor;
        private final List<Column> columns;
        private final String iriTemplate;   // nullable
        private final boolean emitProvenance;
        private final String schemaVersion; // nullable

        public RelationalConfig(final String sinkKind, final String table,
                                final String subjectColumn, final Anchor anchor,
                                final List<Column> columns, final String iriTemplate,
                                final boolean emitProvenance, final String schemaVersion) {
            this.sinkKind = sinkKind;
            this.table = table;
            this.subjectColumn = subjectColumn;
            this.anchor = anchor == null ? new Anchor(null) : anchor;
            this.columns = List.copyOf(columns);
            this.iriTemplate = iriTemplate;
            this.emitProvenance = emitProvenance;
            this.schemaVersion = schemaVersion;
        }

        public String sinkKind()        { return sinkKind; }
        public String table()           { return table; }
        public String subjectColumn()   { return subjectColumn; }
        public Anchor anchor()          { return anchor; }
        public List<Column> columns()   { return columns; }
        public String iriTemplate()     { return iriTemplate; }
        public boolean emitProvenance() { return emitProvenance; }
        public String schemaVersion()   { return schemaVersion; }

        /**
         * Predicate IRI &rarr; column name lookup for the rewrite
         * pass. Skips the {@code subject_iri} role (its column carries
         * the subject binding, not a column predicate).
         */
        public Map<String, String> columnsByPredicate() {
            final Map<String, String> out = new HashMap<>();
            for (Column c : columns) {
                if ("subject_iri".equals(c.role())) continue;
                if (c.predicate() != null) out.put(c.predicate(), c.name());
            }
            return out;
        }
    }

    public static final class Anchor {
        private final String anchorClass; // nullable

        public Anchor(final String anchorClass) {
            this.anchorClass = anchorClass;
        }

        public String anchorClass() { return anchorClass; }
    }

    public static final class Column {
        private final String name;
        private final String role;
        private final String predicate; // nullable
        private final String xsdType;   // nullable, "type" in JSON

        public Column(final String name, final String role,
                      final String predicate, final String xsdType) {
            this.name = name;
            this.role = role;
            this.predicate = predicate;
            this.xsdType = xsdType;
        }

        public String name()      { return name; }
        public String role()      { return role; }
        public String predicate() { return predicate; }
        public String xsdType()   { return xsdType; }
    }

    // ---------------------------------------------------------------------
    // v0.2 Probe mode — ASK-cache discovery of predicate coverage.
    // Design memo §02 (two modes: static / probe), §10 (v0.2 wire the
    // COUNT-star + ASK probes). When probeMode = true on the registry,
    // a predicate that no source statically declares gets tested per
    // source via `ASK { ?s <predicate> ?o }`. Positive results extend
    // the find-by-predicate view for the plan; results cache per
    // (source, predicate) with a TTL so subsequent plans within the
    // window skip the round-trip.
    //
    // The probe function itself is injectable (ProbeFn) so unit tests
    // can simulate endpoints without spinning up a real HTTP listener.
    // ---------------------------------------------------------------------

    /**
     * Injectable probe function. Given {@code (source, predicateIri)},
     * returns {@code true} when the source covers the predicate,
     * {@code false} otherwise. Throws on transport / protocol failure
     * &mdash; {@link #findByPredicateProbing(String)} treats an
     * exception as "skip this source for this plan" per memo &sect;04.
     */
    public interface ProbeFn {
        boolean probe(FederationSource src, String predicateIri) throws Exception;
    }

    /**
     * Per-registry probe cache. Keyed by {@code (sourceName,
     * predicateIri)}; value is {@code (hasPredicate, cachedAt)}. TTL
     * lives on the registry ({@link #probeTtlSecs()}) or on the source
     * ({@link FederationSource#probeTtlSecs()} overrides). Backed by a
     * plain {@link HashMap} guarded by an intrinsic lock &mdash; probe
     * checks are rare compared to plan-time reads, so contention is
     * not a design concern.
     */
    public static final class ProbeCache {
        /** Cache key. */
        public record CacheKey(String source, String predicate) {}
        /** Cache entry. */
        public record CacheEntry(boolean hasIt, Instant when) {}

        private final Object lock = new Object();
        private final Map<CacheKey, CacheEntry> entries = new HashMap<>();

        /**
         * Cache lookup &mdash; returns {@code Optional.of(hasPred)}
         * when a non-expired entry exists; {@link Optional#empty()}
         * when absent or expired (caller re-probes).
         */
        public Optional<Boolean> get(final String source, final String predicate, final Duration ttl) {
            synchronized (lock) {
                final CacheEntry e = entries.get(new CacheKey(source, predicate));
                if (e == null) return Optional.empty();
                if (Duration.between(e.when(), Instant.now()).compareTo(ttl) > 0) {
                    return Optional.empty();
                }
                return Optional.of(e.hasIt());
            }
        }

        /** Store a probe result. */
        public void put(final String source, final String predicate, final boolean hasIt) {
            synchronized (lock) {
                entries.put(new CacheKey(source, predicate), new CacheEntry(hasIt, Instant.now()));
            }
        }

        /** Total entries currently cached. Used by unit tests. */
        public int size() {
            synchronized (lock) {
                return entries.size();
            }
        }
    }
}

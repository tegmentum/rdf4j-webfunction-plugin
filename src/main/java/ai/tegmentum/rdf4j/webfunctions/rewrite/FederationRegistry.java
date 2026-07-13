package ai.tegmentum.rdf4j.webfunctions.rewrite;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

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
        return of(parsed);
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
            case "http-sparql", "http_sparql" -> SourceType.HTTP_SPARQL;
            default -> throw new IllegalArgumentException(
                    "federation registry source `" + name + "`: unknown type `" + typeStr
                            + "` (expected `sparql`, `wf-search`, `wf-fetch`, `wf-document`, "
                            + "or `http-sparql`)");
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

        return new FederationSource(name, type, endpoint, predicates, probeTtl);
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

        public FederationSource(final String name,
                                final SourceType sourceType,
                                final String endpoint,
                                final List<String> predicates,
                                final OptionalInt probeTtlSecs) {
            this.name = name;
            this.sourceType = sourceType;
            this.endpoint = endpoint;
            this.predicates = List.copyOf(predicates);
            this.probeTtlSecs = probeTtlSecs;
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
    }
}

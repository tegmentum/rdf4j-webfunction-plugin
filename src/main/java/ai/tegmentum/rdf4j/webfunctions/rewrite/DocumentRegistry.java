package ai.tegmentum.rdf4j.webfunctions.rewrite;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

/**
 * Planner-side catalog of registered document indexes. v0.2 companion
 * to {@link FulltextRegistry}: same two-mode framing, applied to the
 * search + storage compose described in the design memo at
 * {@code wf-conformance/docs/design/wf-document.md} §03 and §07.
 *
 * <ul>
 *   <li><b>{@link DocumentMode#MANAGED}</b> &mdash; the substrate owns
 *       both backends. Ingest goes into SirixDB; the periodic sweep
 *       keeps the search backend (e.g. Manticore) mirroring Sirix's
 *       committed state. Managed entries carry sweep + retention
 *       metadata.</li>
 *   <li><b>{@link DocumentMode#FEDERATED}</b> &mdash; the substrate is a
 *       pure client of both backends. No sweep, no retention semantics;
 *       both stores were populated out-of-band. Federated entries drop
 *       the sweep + retention fields on load.</li>
 * </ul>
 *
 * <p>Read-only after load; an empty registry (no config flag) is a
 * valid state and every consumer treats it as an unconditional no-op.
 * Identical lifecycle to {@link FulltextRegistry}.
 *
 * <p>v0.2 wires the registry only for parse + validation + admin
 * lookup. wf_document is invoked exclusively through explicit
 * {@code SERVICE ?svc}; there is no companion rewrite pass in this
 * release (see memo §11.6).
 */
public final class DocumentRegistry {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    /**
     * Default sweep cadence when a MANAGED entry omits
     * {@code sweep_interval_secs}. Matches §07 of the memo (300s is
     * the memo-cited example, and §08 describes the resulting
     * consistency window).
     */
    public static final int DEFAULT_SWEEP_INTERVAL_SECS = 300;

    /** Which of the two modes from §03 of the memo an entry is in. */
    public enum DocumentMode {
        /**
         * Substrate owns both backends. The sweep mirrors Sirix into
         * the search backend. Requires {@code sweep_interval_secs}
         * (defaulted) and {@code revision_retention}.
         */
        MANAGED,
        /**
         * Substrate is a client of both backends. No sweep, no
         * retention; both are populated out-of-band.
         */
        FEDERATED
    }

    private final List<DocumentIndex> entries;
    /** Name &rarr; index-in-{@code entries} for administrative lookup. */
    private final Map<String, Integer> nameToEntry;

    private DocumentRegistry(final List<DocumentIndex> entries,
                             final Map<String, Integer> nameToEntry) {
        this.entries = List.copyOf(entries);
        this.nameToEntry = Map.copyOf(nameToEntry);
    }

    /**
     * Empty registry &mdash; the uninstrumented-startup state. Every
     * lookup is a no-op; {@link #isEmpty()} returns true.
     */
    public static DocumentRegistry empty() {
        return new DocumentRegistry(List.of(), Map.of());
    }

    public boolean isEmpty() { return entries.isEmpty(); }
    public int size()        { return entries.size(); }

    /** Administrative lookup — resolve an entry by its {@code name} field. */
    public DocumentIndex byName(final String name) {
        final Integer idx = nameToEntry.get(name);
        return idx == null ? null : entries.get(idx);
    }

    /**
     * Iterate every {@code MANAGED} entry. Used by the periodic
     * sweep to enumerate what needs Sirix &rarr; search-backend
     * mirroring. FEDERATED entries are skipped by construction.
     */
    public List<DocumentIndex> managedEntries() {
        final List<DocumentIndex> out = new ArrayList<>();
        for (DocumentIndex e : entries) {
            if (e.mode() == DocumentMode.MANAGED) out.add(e);
        }
        return out;
    }

    /**
     * All registered entries, regardless of mode. Used by callers
     * that need the full set (e.g. reporting entry counts).
     */
    public List<DocumentIndex> entries() { return entries; }

    /**
     * Build a registry from in-memory entries. Convenient for tests
     * and non-JSON loaders. The name-to-entry map is derived; duplicate
     * names are rejected.
     */
    public static DocumentRegistry of(final Iterable<DocumentIndex> entriesIn) {
        final List<DocumentIndex> list = new ArrayList<>();
        final Map<String, Integer> nameToEntry = new HashMap<>();
        for (DocumentIndex e : entriesIn) {
            final int idx = list.size();
            if (nameToEntry.put(e.name(), idx) != null) {
                throw new IllegalArgumentException(
                        "document registry entry `" + e.name() + "`: duplicate name");
            }
            list.add(e);
        }
        return new DocumentRegistry(list, nameToEntry);
    }

    /**
     * Load a registry from the JSON shape declared in §07 of the
     * design memo. An absent file is an error; empty-registry
     * semantics are what {@link #empty()} (no CLI flag) gives you,
     * not what a missing config file gives you.
     */
    public static DocumentRegistry loadFromJson(final Path path) throws IOException {
        final String text = Files.readString(path);
        final JsonNode root;
        try {
            root = MAPPER.readTree(text);
        } catch (RuntimeException e) {
            throw new IOException(
                    "parsing document registry at " + path + ": " + e.getMessage(), e);
        }
        return fromJson(root);
    }

    /**
     * Parse a registry from an already-decoded {@link JsonNode}.
     * Extracted so unit tests can drive the parser without hitting
     * the filesystem.
     */
    public static DocumentRegistry fromJson(final JsonNode root) {
        final JsonNode documents = root.path("documents");
        final List<DocumentIndex> parsed = new ArrayList<>();
        if (documents != null && documents.isArray()) {
            for (JsonNode raw : documents) {
                parsed.add(parseEntry(raw));
            }
        }
        return of(parsed);
    }

    private static DocumentIndex parseEntry(final JsonNode raw) {
        final String name;
        if (raw.hasNonNull("name")) {
            name = raw.get("name").asString();
        } else {
            throw new IllegalArgumentException(
                    "document registry entry missing required field `name`");
        }
        if (!raw.hasNonNull("mode")) {
            throw new IllegalArgumentException(
                    "document registry entry `" + name + "`: missing required field `mode`");
        }
        final String modeStr = raw.get("mode").asString();
        final DocumentMode mode = switch (modeStr) {
            case "managed", "Managed" -> DocumentMode.MANAGED;
            case "federated", "Federated" -> DocumentMode.FEDERATED;
            default -> throw new IllegalArgumentException(
                    "document registry entry `" + name + "`: unknown mode `" + modeStr
                            + "` (expected `managed` or `federated`)");
        };

        final String guestUrl = requireString(raw, name, "guest_url");
        final String searchBackend = requireString(raw, name, "search_backend");
        final String storageBackend = requireString(raw, name, "storage_backend");
        final String searchIndex = requireString(raw, name, "search_index");
        final String sirixDatabase = requireString(raw, name, "sirix_database");
        final String sirixResource = requireString(raw, name, "sirix_resource");

        // Preserve opts as canonical JSON so callers pass it straight
        // to the guest without re-materializing a JsonNode. `{}` when absent.
        final String optsJson;
        if (raw.hasNonNull("opts")) {
            optsJson = raw.get("opts").toString();
        } else {
            optsJson = "{}";
        }

        // sweep_interval_secs + revision_retention are only meaningful
        // for MANAGED; strip them on FEDERATED so downstream code can
        // rely on the struct-level invariant without re-checking mode.
        final OptionalInt sweepInterval;
        final String revisionRetention;
        switch (mode) {
            case MANAGED -> {
                sweepInterval = raw.hasNonNull("sweep_interval_secs")
                        ? OptionalInt.of(raw.get("sweep_interval_secs").asInt())
                        : OptionalInt.empty();
                if (!raw.hasNonNull("revision_retention")) {
                    throw new IllegalArgumentException(
                            "document registry entry `" + name + "`: managed entries must "
                                    + "declare `revision_retention`");
                }
                // v1.0: accepts either string forms (`latest` / `all`,
                // v0.2 backwards-compat) or object forms
                // `{"window": "<duration>"}` / `{"tail": <positive int>}`.
                // `parseRevisionRetention` canonicalizes both shapes into a
                // single wire string the sweep dispatches on (memo
                // `wf-document-v1.md` §03).
                revisionRetention = parseRevisionRetention(name, raw.get("revision_retention"));
            }
            case FEDERATED -> {
                sweepInterval = OptionalInt.empty();
                revisionRetention = "";
            }
            default -> throw new IllegalStateException("unreachable mode: " + mode);
        }

        return new DocumentIndex(
                name, mode, guestUrl, searchBackend, storageBackend,
                searchIndex, sirixDatabase, sirixResource, optsJson,
                sweepInterval, revisionRetention);
    }

    private static String requireString(final JsonNode raw, final String name, final String field) {
        if (!raw.hasNonNull(field)) {
            throw new IllegalArgumentException(
                    "document registry entry `" + name + "`: missing required field `"
                            + field + "`");
        }
        return raw.get(field).asString();
    }

    /**
     * Parse a {@code revision_retention} JSON value (string or object)
     * into the canonical wire string the sweep dispatches on.
     *
     * <ul>
     *   <li>{@code "latest"} / {@code "all"} — v0.2 backwards-compat, pass through.</li>
     *   <li>{@code {"window": "<duration>"}} — canonicalizes to
     *       {@code "window:<duration>"}.</li>
     *   <li>{@code {"tail": <positive int>}} — canonicalizes to
     *       {@code "tail:<N>"}.</li>
     * </ul>
     *
     * <p>Entry-scoped errors so a misconfigured deployment fails loud at
     * boot, not at sweep time. See memo {@code wf-document-v1.md} &sect;03.
     */
    private static String parseRevisionRetention(final String name, final JsonNode raw) {
        if (raw.isString()) {
            final String s = raw.asString();
            if ("latest".equals(s) || "all".equals(s)) {
                return s;
            }
            throw new IllegalArgumentException(
                    "document registry entry `" + name + "`: unknown revision_retention `"
                            + s + "` (expected `latest`, `all`, `{\"window\": \"<duration>\"}`, "
                            + "or `{\"tail\": <positive int>}`)");
        }
        if (raw.isObject()) {
            // Jackson 3: propertyNames() returns Collection<String>.
            final List<String> keys = new ArrayList<>(raw.propertyNames());
            if (keys.size() != 1) {
                throw new IllegalArgumentException(
                        "document registry entry `" + name + "`: revision_retention object must "
                                + "carry exactly one of `window` / `tail`; got "
                                + keys.size() + " key(s)");
            }
            final String key = keys.get(0);
            final JsonNode value = raw.get(key);
            switch (key) {
                case "window": {
                    if (value == null || !value.isString()) {
                        throw new IllegalArgumentException(
                                "document registry entry `" + name + "`: revision_retention "
                                        + "`window` value must be a duration string like `30d`");
                    }
                    final String normalized = parseDuration(name, value.asString());
                    return "window:" + normalized;
                }
                case "tail": {
                    if (value == null || !value.isIntegralNumber()) {
                        throw new IllegalArgumentException(
                                "document registry entry `" + name + "`: revision_retention "
                                        + "`tail` value must be a positive integer");
                    }
                    final long n = value.asLong();
                    if (n <= 0) {
                        throw new IllegalArgumentException(
                                "document registry entry `" + name + "`: revision_retention "
                                        + "`tail` must be a positive integer, got " + n);
                    }
                    return "tail:" + n;
                }
                default:
                    throw new IllegalArgumentException(
                            "document registry entry `" + name + "`: unknown revision_retention "
                                    + "discriminant `" + key + "` (expected `window` or `tail`)");
            }
        }
        throw new IllegalArgumentException(
                "document registry entry `" + name + "`: revision_retention must be a string "
                        + "or object");
    }

    /**
     * Parse a duration literal like {@code 30d}, {@code 24h}, {@code 5m}.
     * Rejects empty, unknown unit, missing numeric prefix, non-positive.
     * Returns the canonical form (same digits, same unit letter) so the
     * wire form re-emits operator intent verbatim.
     */
    private static String parseDuration(final String name, final String s) {
        if (s == null || s.isEmpty()) {
            throw new IllegalArgumentException(
                    "document registry entry `" + name + "`: revision_retention `window` "
                            + "duration must not be empty");
        }
        final char unit = s.charAt(s.length() - 1);
        if (unit != 'd' && unit != 'h' && unit != 'm') {
            throw new IllegalArgumentException(
                    "document registry entry `" + name + "`: revision_retention `window` "
                            + "duration `" + s + "` has unknown unit (expected `d`, `h`, or `m`)");
        }
        final String digits = s.substring(0, s.length() - 1);
        if (digits.isEmpty()) {
            throw new IllegalArgumentException(
                    "document registry entry `" + name + "`: revision_retention `window` "
                            + "duration `" + s + "` is missing the numeric prefix");
        }
        final long n;
        try {
            n = Long.parseLong(digits);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "document registry entry `" + name + "`: revision_retention `window` "
                            + "duration `" + s + "` numeric prefix is not a non-negative integer");
        }
        if (n <= 0) {
            throw new IllegalArgumentException(
                    "document registry entry `" + name + "`: revision_retention `window` "
                            + "duration must be positive, got `" + s + "`");
        }
        return "" + n + unit;
    }

    /**
     * One registered document index. Mirrors the JSON shape declared
     * in §07 of the design memo. Java analog of the Rust
     * {@code DocumentIndex} record.
     */
    public static final class DocumentIndex {
        private final String name;
        private final DocumentMode mode;
        private final String guestUrl;
        private final String searchBackend;
        private final String storageBackend;
        private final String searchIndex;
        private final String sirixDatabase;
        private final String sirixResource;
        private final String optsJson;
        private final OptionalInt sweepIntervalSecs;
        private final String revisionRetention;

        public DocumentIndex(final String name,
                             final DocumentMode mode,
                             final String guestUrl,
                             final String searchBackend,
                             final String storageBackend,
                             final String searchIndex,
                             final String sirixDatabase,
                             final String sirixResource,
                             final String optsJson,
                             final OptionalInt sweepIntervalSecs,
                             final String revisionRetention) {
            this.name = name;
            this.mode = mode;
            this.guestUrl = guestUrl;
            this.searchBackend = searchBackend;
            this.storageBackend = storageBackend;
            this.searchIndex = searchIndex;
            this.sirixDatabase = sirixDatabase;
            this.sirixResource = sirixResource;
            this.optsJson = optsJson;
            this.sweepIntervalSecs = sweepIntervalSecs;
            this.revisionRetention = revisionRetention;
        }

        public String name()             { return name; }
        public DocumentMode mode()       { return mode; }
        public String guestUrl()         { return guestUrl; }
        public String searchBackend()    { return searchBackend; }
        public String storageBackend()   { return storageBackend; }
        public String searchIndex()      { return searchIndex; }
        public String sirixDatabase()    { return sirixDatabase; }
        public String sirixResource()    { return sirixResource; }
        /** Raw JSON string of the {@code opts} object; passed through verbatim to the guest. */
        public String optsJson()         { return optsJson; }
        /**
         * MANAGED-only. {@link OptionalInt#empty()} means the sweep
         * uses its {@link #DEFAULT_SWEEP_INTERVAL_SECS default}.
         * Always empty for FEDERATED.
         */
        public OptionalInt sweepIntervalSecs() { return sweepIntervalSecs; }
        /**
         * MANAGED-only. v0.2 accepts only {@code "latest"}; the value
         * is always the empty string for FEDERATED (the field is
         * meaningless there).
         */
        public String revisionRetention() { return revisionRetention; }
    }
}

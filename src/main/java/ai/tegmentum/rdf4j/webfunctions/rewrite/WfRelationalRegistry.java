package ai.tegmentum.rdf4j.webfunctions.rewrite;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sidecar registry: per-source shape descriptors for {@code wf-relational}
 * federation sources. Design memo:
 * {@code wf-conformance/docs/design/wf-relational.md} &sect;04.
 *
 * <p>The federation-config JSON's {@code wf-relational} sources carry a
 * {@code relational} block (adapter renders it in
 * {@code wf-conformance/src/adapter/mod.rs::render_relational_descriptor}).
 * {@link FederationRegistry} deliberately drops this extension &mdash; it
 * only reads the memo &sect;03 fields for source dispatch. This registry
 * loads the same JSON and captures the descriptor block per source name
 * so {@link WfRelationalRewrite} can translate
 * {@code SERVICE <wf-relational:<name>>} into a wf_fetch dispatch with
 * the Postgres sink URL and shape descriptor baked in.
 *
 * <h2>Design constraint</h2>
 * {@link FederationRegistry} is off-limits (parallel agent territory).
 * This sibling registry reads the same file but only extracts the fields
 * {@link WfRelationalRewrite} needs. Empty file, missing file, or file
 * with no {@code wf-relational} sources &rarr; empty registry, and every
 * consumer treats empty as an unconditional no-op.
 *
 * <p>Java sibling of {@code oxigraph-wf/src/wf_relational_registry.rs}.
 */
public final class WfRelationalRegistry {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private final Map<String, RelationalEntry> entries;

    private WfRelationalRegistry(final Map<String, RelationalEntry> entries) {
        this.entries = Map.copyOf(entries);
    }

    /** Empty registry — the uninstrumented-startup state. */
    public static WfRelationalRegistry empty() {
        return new WfRelationalRegistry(Map.of());
    }

    public boolean isEmpty() { return entries.isEmpty(); }
    public int size()        { return entries.size(); }

    /**
     * Administrative lookup. Returns {@code null} when the name isn't a
     * {@code wf-relational} source (either absent from the JSON or a
     * different source type).
     */
    public RelationalEntry byName(final String name) {
        return entries.get(name);
    }

    /**
     * Load from the same JSON file {@link FederationRegistry} consumes.
     * Only {@code wf-relational} sources with a {@code relational} block
     * are captured; every other entry is silently skipped. A missing
     * file returns an empty registry (matches the empty-registry
     * semantics of every sibling registry).
     */
    public static WfRelationalRegistry loadFromJson(final Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return empty();
        }
        final String text = Files.readString(path);
        return fromJsonText(text);
    }

    /**
     * Parse a JSON blob directly. Useful for tests and embedded configs.
     * Malformed JSON errors; a valid JSON with no {@code wf-relational}
     * sources yields an empty registry.
     */
    public static WfRelationalRegistry fromJsonText(final String text) throws IOException {
        final JsonNode root;
        try {
            root = MAPPER.readTree(text);
        } catch (RuntimeException e) {
            throw new IOException(
                    "parsing wf-relational registry: " + e.getMessage(), e);
        }
        return fromJson(root);
    }

    /**
     * Parse a registry from an already-decoded {@link JsonNode}.
     * Extracted so unit tests can drive the parser without hitting
     * the filesystem.
     */
    public static WfRelationalRegistry fromJson(final JsonNode root) {
        final Map<String, RelationalEntry> out = new LinkedHashMap<>();
        if (root == null) return empty();
        final JsonNode sources = root.path("sources");
        if (sources == null || !sources.isArray()) return empty();
        for (JsonNode src : sources) {
            final String typeStr = src.path("type").asString("");
            if (!"wf-relational".equals(typeStr) && !"wf_relational".equals(typeStr)) continue;
            if (!src.hasNonNull("name") || !src.hasNonNull("endpoint")) continue;
            if (!src.hasNonNull("relational")) continue;
            final String name = src.get("name").asString();
            final String endpoint = src.get("endpoint").asString();
            final RelationalDescriptor descriptor = parseDescriptor(src.get("relational"));
            out.put(name, new RelationalEntry(name, endpoint, descriptor));
        }
        return new WfRelationalRegistry(out);
    }

    private static RelationalDescriptor parseDescriptor(final JsonNode raw) {
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
                final String predicate = c.hasNonNull("predicate") ? c.get("predicate").asString() : null;
                final String xsdType = c.hasNonNull("type") ? c.get("type").asString() : null;
                cols.add(new Column(cn, role, predicate, xsdType));
            }
        }
        final String iriTemplate = raw.hasNonNull("iri_template") ? raw.get("iri_template").asString() : null;
        final boolean emitProvenance = raw.path("emit_provenance").asBoolean(false);
        final String schemaVersion = raw.hasNonNull("schema_version") ? raw.get("schema_version").asString() : null;
        return new RelationalDescriptor(sinkKind, table, subjectColumn, new Anchor(anchorClass),
                cols, iriTemplate, emitProvenance, schemaVersion);
    }

    /**
     * One entry captured from a {@code wf-relational} federation source.
     * {@code endpoint} is the FederationSource's Postgres URL, propagated
     * verbatim so the rewrite pass can bake {@code sink} =
     * {@code <endpoint>#<table>} into the descriptor at fold time.
     */
    public static final class RelationalEntry {
        private final String name;
        private final String endpoint;
        private final RelationalDescriptor descriptor;

        public RelationalEntry(final String name, final String endpoint,
                               final RelationalDescriptor descriptor) {
            this.name = name;
            this.endpoint = endpoint;
            this.descriptor = descriptor;
        }

        public String name()                       { return name; }
        public String endpoint()                   { return endpoint; }
        public RelationalDescriptor descriptor()   { return descriptor; }
    }

    /**
     * Parsed shape descriptor block. Mirrors the JSON shape the adapter
     * emits &mdash; see {@code render_relational_descriptor} in the
     * conformance runner. Kept as a proper struct (not opaque JSON) so
     * the rewrite pass gets typed access to {@link #columnsByPredicate()}
     * for the BGP fold.
     */
    public static final class RelationalDescriptor {
        private final String sinkKind;
        private final String table;
        private final String subjectColumn;
        private final Anchor anchor;
        private final List<Column> columns;
        private final String iriTemplate;
        private final boolean emitProvenance;
        private final String schemaVersion;

        public RelationalDescriptor(final String sinkKind, final String table,
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

        public String sinkKind()          { return sinkKind; }
        public String table()             { return table; }
        public String subjectColumn()     { return subjectColumn; }
        public Anchor anchor()            { return anchor; }
        public List<Column> columns()     { return columns; }
        public String iriTemplate()       { return iriTemplate; }
        public boolean emitProvenance()   { return emitProvenance; }
        public String schemaVersion()     { return schemaVersion; }

        /**
         * Predicate IRI &rarr; column name lookup for the rewrite pass.
         * Skips the {@code subject_iri} role (its column carries the
         * subject binding, not a column predicate).
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
        private final String predicate;   // nullable
        private final String xsdType;     // nullable, "type" in JSON

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
}

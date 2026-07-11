package ai.tegmentum.rdf4j.webfunctions.rewrite;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Planner-side catalog of materialized shapes. Loaded once at startup
 * from the {@code shapes} table written by {@code wf_materialize}.
 *
 * <p>Read-only after load; a subsequent materialize call refreshes the
 * SQLite side but the server has to be restarted to reflect the change.
 * Absent file or absent table yield an empty registry &mdash; first-boot
 * before any materialization has run is a valid state.
 *
 * <p>Java port of {@code oxigraph-wf/src/shape_registry.rs}.
 */
public final class ShapeRegistry {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private final Map<String, ShapeEntry> byName;
    /** Predicate IRI &rarr; shape name, for O(1) BGP predicate lookup. */
    private final Map<String, String> predicateToShape;

    private ShapeRegistry(final Map<String, ShapeEntry> byName,
                          final Map<String, String> predicateToShape) {
        this.byName = byName;
        this.predicateToShape = predicateToShape;
    }

    public static ShapeRegistry empty() {
        return new ShapeRegistry(Map.of(), Map.of());
    }

    /**
     * Build a registry from in-memory {@link ShapeEntry}s. Convenient
     * for tests and non-SQLite loaders. The predicate &rarr; shape
     * index is derived from each entry's declared columns.
     */
    public static ShapeRegistry of(final Iterable<ShapeEntry> entries) {
        final Map<String, ShapeEntry> byName = new HashMap<>();
        final Map<String, String> predicateToShape = new HashMap<>();
        for (ShapeEntry e : entries) {
            byName.put(e.name(), e);
            for (String pred : e.columnsByPredicate().keySet()) {
                predicateToShape.put(pred, e.name());
            }
        }
        return new ShapeRegistry(Map.copyOf(byName), Map.copyOf(predicateToShape));
    }

    public boolean isEmpty() { return byName.isEmpty(); }
    public int size()        { return byName.size(); }

    /** Look up a shape by any of its column predicates. */
    public ShapeEntry findByPredicate(final String iri) {
        final String name = predicateToShape.get(iri);
        return name == null ? null : byName.get(name);
    }

    public ShapeEntry shapeByName(final String name) {
        return byName.get(name);
    }

    public static ShapeRegistry loadFromSqlite(final Path dbPath, final String table) throws SQLException {
        final String url = "jdbc:sqlite:" + dbPath.toAbsolutePath();
        try (Connection conn = DriverManager.getConnection(url)) {
            if (!AliasMap.tableExists(conn, table)) {
                return empty();
            }
            final Map<String, ShapeEntry> byName = new HashMap<>();
            final Map<String, String> predicateToShape = new HashMap<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT name, descriptor FROM " + AliasMap.safeIdent(table));
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    final String name = rs.getString(1);
                    final String descriptor = rs.getString(2);
                    final ShapeEntry entry = parseEntry(name, descriptor);
                    for (String pred : entry.columnsByPredicate().keySet()) {
                        predicateToShape.put(pred, name);
                    }
                    byName.put(name, entry);
                }
            }
            return new ShapeRegistry(Map.copyOf(byName), Map.copyOf(predicateToShape));
        }
    }

    static ShapeEntry parseEntry(final String name, final String descriptorJson) {
        final JsonNode root = MAPPER.readTree(descriptorJson);
        final JsonNode anchor = root.path("anchor");
        final String anchorClass = anchor.hasNonNull("class")
                ? anchor.get("class").asString() : null;

        final Map<String, String> columnsByPredicate = new LinkedHashMap<>();
        String subjectColumn = null;
        final JsonNode columns = root.path("columns");
        if (columns != null && columns.isArray()) {
            for (JsonNode c : columns) {
                final String cName = c.path("name").asString();
                final String role  = c.path("role").asString();
                if ("subject_iri".equals(role)) {
                    subjectColumn = cName;
                    continue;
                }
                if (c.hasNonNull("predicate")) {
                    columnsByPredicate.put(c.get("predicate").asString(), cName);
                }
            }
        }
        return new ShapeEntry(
                name,
                descriptorJson,
                anchorClass,
                columnsByPredicate,
                subjectColumn == null ? "id" : subjectColumn);
    }
}

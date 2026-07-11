package ai.tegmentum.rdf4j.webfunctions.rewrite;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/**
 * Alias &rarr; canonical IRI map loaded from a SQLite {@code aliases}
 * table produced by {@code wf_canonicalize}.
 *
 * <p>Read-only after startup; the same map is shared across every
 * request. Missing DB file or missing table both yield an empty map so
 * first-boot before any canonicalize has run is a valid state and
 * consumers pay zero rewrite cost.
 *
 * <p>Java port of {@code oxigraph-wf/src/alias_rewrite.rs::AliasMap}.
 */
public final class AliasMap {

    private final Map<String, String> aliases;

    private AliasMap(final Map<String, String> aliases) {
        this.aliases = aliases;
    }

    /** Empty map &mdash; same effect as running without an alias DB. */
    public static AliasMap empty() {
        return new AliasMap(Map.of());
    }

    /**
     * Build a map from an in-memory {@code alias -> canonical} table.
     * Convenient for tests and for callers that want to build the map
     * from a non-SQLite source. The input is defensively copied.
     */
    public static AliasMap of(final Map<String, String> aliases) {
        return new AliasMap(Map.copyOf(aliases));
    }

    /**
     * Populate from a SQLite database's {@code aliases} table. Missing
     * table (first-boot before canonicalize has ever run) is not an
     * error &mdash; callers get an empty map. Any other SQL error
     * propagates.
     */
    public static AliasMap loadFromSqlite(final Path dbPath, final String table) throws SQLException {
        final String url = "jdbc:sqlite:" + dbPath.toAbsolutePath();
        try (Connection conn = DriverManager.getConnection(url)) {
            if (!tableExists(conn, table)) {
                return empty();
            }
            final Map<String, String> map = new HashMap<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT alias, canonical FROM " + safeIdent(table));
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    map.put(rs.getString(1), rs.getString(2));
                }
            }
            return new AliasMap(Map.copyOf(map));
        }
    }

    public int size() {
        return aliases.size();
    }

    public boolean isEmpty() {
        return aliases.isEmpty();
    }

    /**
     * Look up an alias's canonical form. Returns {@code null} for IRIs
     * that aren't aliased (the vast majority in a well-behaved dataset).
     */
    public String get(final String alias) {
        return aliases.get(alias);
    }

    static boolean tableExists(final Connection conn, final String table) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    /**
     * SQLite identifier passthrough. The table name is a startup config
     * value; the caller is trusted, but strip anything that isn't a
     * word character so a malformed value can't produce a SQL syntax
     * error hidden in a table-missing check.
     */
    static String safeIdent(final String s) {
        final StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_') sb.append(c);
        }
        return sb.length() == 0 ? "aliases" : sb.toString();
    }
}

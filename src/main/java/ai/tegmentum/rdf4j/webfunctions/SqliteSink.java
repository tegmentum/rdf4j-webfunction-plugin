package ai.tegmentum.rdf4j.webfunctions;

import ai.tegmentum.wasmtime4j.component.ComponentVal;
import ai.tegmentum.wasmtime4j.component.ComponentVariant;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * v0.5 {@link Sink} backed by SQLite via the {@code org.xerial.sqlite-jdbc}
 * driver. Adapts the WIT {@code value} marshalling from
 * {@code oxigraph-wf/src/sink.rs} — {@code xsd:integer}/{@code xsd:decimal}/
 * {@code xsd:boolean} literals coerce to the native SQL scalar, everything
 * else falls through to TEXT.
 *
 * <p>URL parsing convention:
 * <ul>
 *   <li>{@code sqlite://memory} or {@code sqlite:///:memory:} — anonymous
 *       in-memory database (used by tests).</li>
 *   <li>{@code sqlite:///data/mv.db} — filesystem path {@code /data/mv.db}.</li>
 *   <li>{@code sqlite:///data/mv.db#person} — {@code #fragment} is metadata
 *       the guest can read; the sink does not enforce it.</li>
 * </ul>
 */
public final class SqliteSink implements Sink {

    private static final String XSD_INTEGER = "http://www.w3.org/2001/XMLSchema#integer";
    private static final String XSD_INT = "http://www.w3.org/2001/XMLSchema#int";
    private static final String XSD_LONG = "http://www.w3.org/2001/XMLSchema#long";
    private static final String XSD_SHORT = "http://www.w3.org/2001/XMLSchema#short";
    private static final String XSD_BYTE = "http://www.w3.org/2001/XMLSchema#byte";
    private static final String XSD_DECIMAL = "http://www.w3.org/2001/XMLSchema#decimal";
    private static final String XSD_DOUBLE = "http://www.w3.org/2001/XMLSchema#double";
    private static final String XSD_FLOAT = "http://www.w3.org/2001/XMLSchema#float";
    private static final String XSD_BOOLEAN = "http://www.w3.org/2001/XMLSchema#boolean";
    private static final String XSD_STRING = "http://www.w3.org/2001/XMLSchema#string";

    private final Connection conn;

    private SqliteSink(final Connection conn) {
        this.conn = conn;
    }

    /**
     * Open a SQLite sink for the given URL. Matches the
     * {@code sqlite://…} shapes documented on {@link SqliteSink}. Errors
     * propagate as {@link SQLException} — the calling host closure wraps
     * them into a WIT {@code err} payload.
     */
    public static SqliteSink open(final String url) throws SQLException {
        // java.net.URI's parser rejects sqlite:///foo but accepts
        // sqlite://memory. Both are valid inputs from a guest, so parse
        // manually: everything after "sqlite:" is either "//host…" or a
        // path prefix.
        final String rest;
        if (url.regionMatches(true, 0, "sqlite:", 0, 7)) {
            rest = url.substring(7);
        } else {
            throw new SQLException("sqlite sink URL must start with sqlite: — got " + url);
        }
        // Strip fragment (guest metadata; sink does not enforce it).
        final int hash = rest.indexOf('#');
        final String noFrag = hash >= 0 ? rest.substring(0, hash) : rest;
        // Strip "//" authority prefix if present. sqlite://memory has
        // authority=memory + empty path; sqlite:///path.db has empty
        // authority + path=/path.db.
        String path;
        if (noFrag.startsWith("//")) {
            final String afterSlashes = noFrag.substring(2);
            final int firstSlash = afterSlashes.indexOf('/');
            if (firstSlash < 0) {
                // No path after authority (e.g. sqlite://memory).
                path = "";
                // Treat authority "memory" as in-memory shorthand.
                if ("memory".equalsIgnoreCase(afterSlashes)) {
                    return openMemory();
                }
                // Authority-only URL with an unrecognized host is ambiguous;
                // treat as an error rather than opening a random file.
                throw new SQLException(
                    "sqlite URL has no path and unrecognized authority `"
                    + afterSlashes + "`; use sqlite://memory or sqlite:///path");
            }
            path = afterSlashes.substring(firstSlash);
        } else {
            path = noFrag;
        }
        if (path.isEmpty() || "/:memory:".equals(path) || ":memory:".equals(path)) {
            return openMemory();
        }
        final Connection c = DriverManager.getConnection("jdbc:sqlite:" + path);
        return new SqliteSink(c);
    }

    private static SqliteSink openMemory() throws SQLException {
        final Connection c = DriverManager.getConnection("jdbc:sqlite::memory:");
        return new SqliteSink(c);
    }

    @Override
    public ComponentVal execute(final String query, final List<ComponentVal> params) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(query)) {
            for (int i = 0; i < params.size(); i++) {
                bindParam(stmt, i + 1, params.get(i));
            }
            // Do we have a rows-returning statement? PreparedStatement's
            // metadata is cheaper than execute() then getMetaData() —
            // SQLite JDBC lets us ask before firing.
            final boolean hasResultSet = stmt.execute();
            if (!hasResultSet) {
                // DDL / INSERT / UPDATE / DELETE — return empty binding-sets.
                return emptyBindingSets();
            }
            try (ResultSet rs = stmt.getResultSet()) {
                return encodeBindingSets(rs);
            }
        }
    }

    @Override
    public void close() {
        try {
            conn.close();
        } catch (SQLException ignored) {
            // Swallow — WIT sink-close returns ok on the happy path and
            // best-effort on the shutdown path; a driver-side close
            // failure isn't observable to the guest.
        }
    }

    // ---- WIT ↔ SQLite parameter marshalling --------------------------------

    /**
     * Bind one WIT {@code value} variant to a JDBC parameter. Mirrors
     * {@code val_to_sqlite} in the oxigraph reference — IRI values become
     * TEXT (their lexical form), bnodes become TEXT with an {@code _:}
     * prefix, and literals coerce common xsd datatypes to their native
     * SQLite scalars.
     */
    private static void bindParam(final PreparedStatement stmt,
                                  final int idx,
                                  final ComponentVal v) throws SQLException {
        if (!v.isVariant()) {
            throw new SQLException("sink param " + idx
                    + ": expected WIT value variant, got " + v.getType());
        }
        final ComponentVariant cv = v.asVariant();
        final String caseName = cv.getCaseName();
        final Optional<ComponentVal> payload = cv.getPayload();
        switch (caseName) {
            case "iri": {
                stmt.setString(idx, payload.map(ComponentVal::asString).orElse(""));
                return;
            }
            case "bnode": {
                stmt.setString(idx, "_:" + payload.map(ComponentVal::asString).orElse(""));
                return;
            }
            case "literal": {
                final Map<String, ComponentVal> fields = payload
                        .orElseThrow(() -> new SQLException("literal variant missing payload"))
                        .asRecord();
                final String label = fields.get("label").asString();
                final String datatype = fields.get("datatype").asString();
                bindLiteralCoerced(stmt, idx, label, datatype);
                return;
            }
            default:
                throw new SQLException("unknown value variant case: " + caseName);
        }
    }

    private static void bindLiteralCoerced(final PreparedStatement stmt,
                                           final int idx,
                                           final String label,
                                           final String datatype) throws SQLException {
        switch (datatype) {
            case XSD_INTEGER:
            case XSD_INT:
            case XSD_LONG:
            case XSD_SHORT:
            case XSD_BYTE: {
                try {
                    stmt.setLong(idx, Long.parseLong(label));
                } catch (NumberFormatException e) {
                    stmt.setString(idx, label);
                }
                return;
            }
            case XSD_DECIMAL:
            case XSD_DOUBLE:
            case XSD_FLOAT: {
                try {
                    stmt.setDouble(idx, Double.parseDouble(label));
                } catch (NumberFormatException e) {
                    stmt.setString(idx, label);
                }
                return;
            }
            case XSD_BOOLEAN: {
                if ("true".equals(label) || "1".equals(label)) {
                    stmt.setInt(idx, 1);
                } else if ("false".equals(label) || "0".equals(label)) {
                    stmt.setInt(idx, 0);
                } else {
                    stmt.setString(idx, label);
                }
                return;
            }
            default:
                stmt.setString(idx, label);
        }
    }

    // ---- SQLite → WIT result-set marshalling --------------------------------

    /**
     * Encode a JDBC {@link ResultSet} as WIT {@code binding-sets}. The
     * reverse mapping is looser than the forward one: JDBC only tells us
     * the SQL type, so INTEGER → xsd:integer, REAL → xsd:decimal, TEXT →
     * xsd:string. Guests that need stricter typing project their own
     * conversions on top.
     */
    private static ComponentVal encodeBindingSets(final ResultSet rs) throws SQLException {
        final ResultSetMetaData meta = rs.getMetaData();
        final int colCount = meta.getColumnCount();
        final List<String> colNames = new ArrayList<>(colCount);
        final List<Integer> colTypes = new ArrayList<>(colCount);
        for (int i = 1; i <= colCount; i++) {
            final String label = meta.getColumnLabel(i);
            colNames.add(label != null ? label : "?column?");
            colTypes.add(meta.getColumnType(i));
        }

        final List<ComponentVal> varsVals = new ArrayList<>(colCount);
        for (String n : colNames) varsVals.add(ComponentVal.string(n));

        final List<ComponentVal> rowVals = new ArrayList<>();
        while (rs.next()) {
            final List<ComponentVal> bindings = new ArrayList<>(colCount);
            for (int i = 1; i <= colCount; i++) {
                final ComponentVal cell = cellToWit(rs, i, colTypes.get(i - 1));
                if (cell == null) continue; // NULL → treat as UNDEF per WIT semantics.
                final Map<String, ComponentVal> bindingFields = new LinkedHashMap<>();
                bindingFields.put("name", ComponentVal.string(colNames.get(i - 1)));
                bindingFields.put("value", cell);
                bindings.add(ComponentVal.record(bindingFields));
            }
            rowVals.add(ComponentVal.list(bindings));
        }

        final Map<String, ComponentVal> bs = new LinkedHashMap<>();
        bs.put("vars", ComponentVal.list(varsVals));
        bs.put("rows", ComponentVal.list(rowVals));
        return ComponentVal.record(bs);
    }

    private static ComponentVal cellToWit(final ResultSet rs,
                                          final int col,
                                          final int sqlType) throws SQLException {
        switch (sqlType) {
            case Types.INTEGER:
            case Types.BIGINT:
            case Types.SMALLINT:
            case Types.TINYINT: {
                final long v = rs.getLong(col);
                if (rs.wasNull()) return null;
                return literal(Long.toString(v), XSD_INTEGER);
            }
            case Types.REAL:
            case Types.FLOAT:
            case Types.DOUBLE:
            case Types.DECIMAL:
            case Types.NUMERIC: {
                final double v = rs.getDouble(col);
                if (rs.wasNull()) return null;
                return literal(Double.toString(v), XSD_DECIMAL);
            }
            case Types.BOOLEAN:
            case Types.BIT: {
                final boolean v = rs.getBoolean(col);
                if (rs.wasNull()) return null;
                return literal(v ? "true" : "false", XSD_BOOLEAN);
            }
            case Types.NULL:
                return null;
            case Types.VARCHAR:
            case Types.CHAR:
            case Types.LONGVARCHAR:
            case Types.CLOB:
            default: {
                // SQLite reports most cells as VARCHAR/NULL/INTEGER/REAL;
                // fall through to string handling for anything unrecognized
                // so the guest gets *something* rather than a hard error.
                final String s = rs.getString(col);
                if (s == null || rs.wasNull()) return null;
                return literal(s, XSD_STRING);
            }
        }
    }

    private static ComponentVal literal(final String label, final String datatype) {
        final Map<String, ComponentVal> fields = new LinkedHashMap<>();
        fields.put("label", ComponentVal.string(label));
        fields.put("datatype", ComponentVal.string(datatype));
        fields.put("lang", ComponentVal.none());
        return ComponentVal.variant("literal", ComponentVal.record(fields));
    }

    private static ComponentVal emptyBindingSets() {
        final Map<String, ComponentVal> bs = new LinkedHashMap<>();
        bs.put("vars", ComponentVal.list(List.of()));
        bs.put("rows", ComponentVal.list(List.of()));
        return ComponentVal.record(bs);
    }
}

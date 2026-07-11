package ai.tegmentum.rdf4j.webfunctions.rewrite;

import org.eclipse.rdf4j.query.MalformedQueryException;
import org.eclipse.rdf4j.query.algebra.Extension;
import org.eclipse.rdf4j.query.algebra.ExtensionElem;
import org.eclipse.rdf4j.query.algebra.Projection;
import org.eclipse.rdf4j.query.algebra.QueryModelNode;
import org.eclipse.rdf4j.query.algebra.QueryRoot;
import org.eclipse.rdf4j.query.algebra.TupleExpr;
import org.eclipse.rdf4j.query.algebra.ValueExpr;
import org.eclipse.rdf4j.query.algebra.helpers.AbstractQueryModelVisitor;
import org.eclipse.rdf4j.query.parser.ParsedQuery;
import org.eclipse.rdf4j.query.parser.sparql.SPARQLParser;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Query-time conversion rules. Loaded once at startup from the
 * {@code conversions} table; grouped by target predicate; each rule
 * also indexed by its virtual {@code urn:wf:conversion:*} graph IRI.
 *
 * <p>Java port of {@code oxigraph-wf/src/conversion_registry.rs}.
 */
public final class ConversionRegistry {

    private static final long FNV64_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV64_PRIME  = 0x100000001b3L;

    private final Map<String, List<ConversionRule>> byTarget;
    private final Map<String, ConversionRule>       byGraphIri;

    private ConversionRegistry(final Map<String, List<ConversionRule>> byTarget,
                               final Map<String, ConversionRule> byGraphIri) {
        this.byTarget   = byTarget;
        this.byGraphIri = byGraphIri;
    }

    public static ConversionRegistry empty() {
        return new ConversionRegistry(Map.of(), Map.of());
    }

    /**
     * Build a registry from an in-memory list of (target, source,
     * expression) rules. Convenient for tests and non-SQLite loaders.
     */
    public static ConversionRegistry of(final Iterable<String[]> rows) throws MalformedQueryException {
        final Map<String, java.util.List<ConversionRule>> byTarget = new HashMap<>();
        final Map<String, ConversionRule> byGraphIri = new HashMap<>();
        for (String[] row : rows) {
            if (row.length != 3) {
                throw new IllegalArgumentException("conversion row must be [target, source, expr]");
            }
            final String target = row[0];
            final String source = row[1];
            final String expr   = row[2];
            final ValueExpr parsed = parseExpression(expr);
            final String graphIri = mintGraphIri(target, source);
            final ConversionRule rule = new ConversionRule(target, source, expr, graphIri, parsed);
            byGraphIri.put(graphIri, rule);
            byTarget.computeIfAbsent(target, k -> new java.util.ArrayList<>()).add(rule);
        }
        final Map<String, java.util.List<ConversionRule>> byTargetFrozen = new HashMap<>();
        for (Map.Entry<String, java.util.List<ConversionRule>> e : byTarget.entrySet()) {
            byTargetFrozen.put(e.getKey(), java.util.List.copyOf(e.getValue()));
        }
        return new ConversionRegistry(Map.copyOf(byTargetFrozen), Map.copyOf(byGraphIri));
    }

    public boolean isEmpty() { return byGraphIri.isEmpty(); }
    public int size()        { return byGraphIri.size(); }

    /** Rules whose target predicate matches {@code iri}. Empty list if none. */
    public List<ConversionRule> rulesForTarget(final String iri) {
        return byTarget.getOrDefault(iri, Collections.emptyList());
    }

    /** Rule keyed by its virtual graph IRI. */
    public ConversionRule ruleByGraph(final String iri) {
        return byGraphIri.get(iri);
    }

    public static ConversionRegistry loadFromSqlite(final Path dbPath, final String table) throws SQLException {
        final String url = "jdbc:sqlite:" + dbPath.toAbsolutePath();
        try (Connection conn = DriverManager.getConnection(url)) {
            if (!AliasMap.tableExists(conn, table)) {
                return empty();
            }
            final Map<String, List<ConversionRule>> byTarget = new HashMap<>();
            final Map<String, ConversionRule> byGraphIri = new HashMap<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT target_predicate, source_predicate, expression FROM "
                            + AliasMap.safeIdent(table));
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    final String target = rs.getString(1);
                    final String source = rs.getString(2);
                    final String expr   = rs.getString(3);
                    final ValueExpr parsed;
                    try {
                        parsed = parseExpression(expr);
                    } catch (MalformedQueryException e) {
                        throw new SQLException("parsing conversion expression for ("
                                + target + ", " + source + "): `" + expr + "` -> " + e.getMessage(), e);
                    }
                    final String graphIri = mintGraphIri(target, source);
                    final ConversionRule rule = new ConversionRule(
                            target, source, expr, graphIri, parsed);
                    byGraphIri.put(graphIri, rule);
                    byTarget.computeIfAbsent(target, k -> new java.util.ArrayList<>()).add(rule);
                }
            }
            // Freeze the value lists.
            final Map<String, List<ConversionRule>> byTargetFrozen = new HashMap<>(byTarget.size());
            for (Map.Entry<String, List<ConversionRule>> e : byTarget.entrySet()) {
                byTargetFrozen.put(e.getKey(), List.copyOf(e.getValue()));
            }
            return new ConversionRegistry(Map.copyOf(byTargetFrozen), Map.copyOf(byGraphIri));
        }
    }

    /**
     * Parse a bare SPARQL scalar expression by wrapping it in a minimal
     * {@code SELECT (({expr}) AS ?_bind) WHERE {}} query and lifting the
     * {@link ExtensionElem} expression out. RDF4J, like spargebra, has
     * no bare-expression entry point.
     */
    static ValueExpr parseExpression(final String text) throws MalformedQueryException {
        final String wrapped = "SELECT ((" + text + ") AS ?_bind) WHERE {}";
        final ParsedQuery parsed = new SPARQLParser().parseQuery(wrapped, null);
        final ExtensionExpressionFinder finder = new ExtensionExpressionFinder();
        parsed.getTupleExpr().visit(finder);
        if (finder.found == null) {
            throw new MalformedQueryException(
                    "wrapped SELECT did not produce an Extension node: `" + text + "`");
        }
        return finder.found;
    }

    private static final class ExtensionExpressionFinder extends AbstractQueryModelVisitor<RuntimeException> {
        ValueExpr found;

        @Override
        public void meet(final Extension node) {
            for (ExtensionElem elem : node.getElements()) {
                if ("_bind".equals(elem.getName())) {
                    found = elem.getExpr();
                    return;
                }
            }
            // First extension without a matching name still lets us dig
            // deeper &mdash; some parsers add wrapper extensions.
            super.meet(node);
        }

        @Override public void meet(final Projection node) { super.meet(node); }
        @Override public void meet(final QueryRoot node)  { super.meet(node); }

        @Override
        protected void meetNode(final QueryModelNode n) {
            n.visitChildren(this);
        }
    }

    /**
     * Mint a deterministic {@code urn:wf:conversion:*} IRI from a
     * target/source pair. Human-readable in the common case, with a
     * stable FNV-64 hash suffix.
     */
    static String mintGraphIri(final String target, final String source) {
        final String targetShort = shortName(target);
        final String sourceShort = shortName(source);
        final long hash = fnv64(target + "\0" + source);
        return String.format("urn:wf:conversion:%s_from_%s:%016x", targetShort, sourceShort, hash);
    }

    static String shortName(final String iri) {
        final int hash = iri.lastIndexOf('#');
        final String afterHash = hash >= 0 ? iri.substring(hash + 1) : iri;
        final int slash = afterHash.lastIndexOf('/');
        final String afterSlash = slash >= 0 ? afterHash.substring(slash + 1) : afterHash;
        final int colon = afterSlash.lastIndexOf(':');
        final String afterColon = colon >= 0 ? afterSlash.substring(colon + 1) : afterSlash;
        if (afterColon.isEmpty()) return "iri";
        final StringBuilder sb = new StringBuilder(afterColon.length());
        for (int i = 0; i < afterColon.length(); i++) {
            final char c = afterColon.charAt(i);
            sb.append(Character.isLetterOrDigit(c) ? c : '_');
        }
        return sb.toString();
    }

    static long fnv64(final String s) {
        long h = FNV64_OFFSET;
        final byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (byte b : bytes) {
            h ^= (b & 0xFFL);
            h *= FNV64_PRIME;
        }
        return h;
    }

    /** Iterate every registered rule. */
    public Iterable<ConversionRule> rules() {
        return byGraphIri.values();
    }

    // Exposed for tests.
    static TupleExpr __topLevel(final ParsedQuery q) { return q.getTupleExpr(); }
}

package ai.tegmentum.rdf4j.webfunctions.rewrite;

import org.eclipse.rdf4j.model.BNode;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.query.algebra.And;
import org.eclipse.rdf4j.query.algebra.Compare;
import org.eclipse.rdf4j.query.algebra.Filter;
import org.eclipse.rdf4j.query.algebra.Join;
import org.eclipse.rdf4j.query.algebra.Or;
import org.eclipse.rdf4j.query.algebra.QueryModelNode;
import org.eclipse.rdf4j.query.algebra.StatementPattern;
import org.eclipse.rdf4j.query.algebra.TupleExpr;
import org.eclipse.rdf4j.query.algebra.ValueConstant;
import org.eclipse.rdf4j.query.algebra.ValueExpr;
import org.eclipse.rdf4j.query.algebra.Var;

/**
 * Minimal algebra-to-SPARQL renderer for the sub-tree shapes that
 * {@link WfFederationRewrite} places inside a {@link
 * org.eclipse.rdf4j.query.algebra.Service} body. Handles {@link Join}s
 * of {@link StatementPattern}s and optional {@link Filter}s over the
 * BGP.
 *
 * <h3>Why this exists</h3>
 *
 * RDF4J's {@link org.eclipse.rdf4j.query.algebra.Service} constructor
 * captures a raw SPARQL string ({@code serviceExpressionString}) that
 * {@link org.eclipse.rdf4j.repository.sparql.federation.SPARQLFederatedService}
 * hands to the remote endpoint verbatim &mdash; the algebra tree
 * underneath is not consulted for the wire message (see
 * {@code Service.initPreparedQueryString} in RDF4J 6.0.0). If the pass
 * that constructs a fresh {@code Service} passes {@code ""} for this
 * argument, the dispatcher ships {@code SELECT ... WHERE {}} (empty
 * BGP), and every remote returns zero rows &mdash; symptom: silent
 * failure that shows up as {@code bindings=[]}.
 *
 * <h3>Grammar coverage</h3>
 *
 * The rewrite emits exactly these node types under a Service body,
 * so that's what this renderer covers:
 *
 * <ul>
 *   <li>{@link StatementPattern} &rarr; {@code <s> <p> <o> .}</li>
 *   <li>{@link Join} of the above &rarr; flatten to a BGP.</li>
 *   <li>{@link Filter} wrapping the above &rarr; {@code FILTER(...)}
 *       after the body. Filter conditions cover {@link And}, {@link Or},
 *       {@link Compare} between {@link Var}s and {@link ValueConstant}s.
 *       Any unknown condition node throws
 *       {@link UnsupportedOperationException} so caller failures don't
 *       silently emit a wrong body.</li>
 * </ul>
 *
 * IRIs, literals, and datatypes are rendered as absolute forms
 * ({@code <iri>}, {@code "lex"^^<xsd-iri>}, {@code "lex"@lang}) so the
 * caller doesn't have to maintain a prefix map on the Service.
 */
final class BgpSparqlRenderer {

    private BgpSparqlRenderer() {}

    /**
     * Render {@code body} as a SPARQL BGP + optional FILTERs suitable for
     * splicing between the {@code {} }braces{@code }} of a SERVICE clause.
     */
    static String render(final TupleExpr body) {
        final StringBuilder out = new StringBuilder();
        renderExpr(body, out);
        return out.toString().trim();
    }

    private static void renderExpr(final QueryModelNode node, final StringBuilder out) {
        if (node instanceof StatementPattern sp) {
            renderStatementPattern(sp, out);
        } else if (node instanceof Join j) {
            renderExpr(j.getLeftArg(), out);
            renderExpr(j.getRightArg(), out);
        } else if (node instanceof Filter f) {
            renderExpr(f.getArg(), out);
            out.append("FILTER(");
            renderCondition(f.getCondition(), out);
            out.append(") ");
        } else {
            throw new UnsupportedOperationException(
                    "BgpSparqlRenderer: unsupported node under Service body: "
                            + node.getClass().getName());
        }
    }

    private static void renderStatementPattern(final StatementPattern sp, final StringBuilder out) {
        renderVar(sp.getSubjectVar(), out);
        out.append(' ');
        renderVar(sp.getPredicateVar(), out);
        out.append(' ');
        renderVar(sp.getObjectVar(), out);
        out.append(" . ");
    }

    private static void renderVar(final Var v, final StringBuilder out) {
        if (v == null) {
            throw new IllegalStateException("null Var in statement pattern");
        }
        if (v.hasValue()) {
            renderValue(v.getValue(), out);
        } else {
            out.append('?').append(v.getName());
        }
    }

    private static void renderValue(final Value value, final StringBuilder out) {
        if (value instanceof IRI iri) {
            out.append('<').append(iri.stringValue()).append('>');
        } else if (value instanceof Literal lit) {
            out.append('"');
            escapeLiteral(lit.getLabel(), out);
            out.append('"');
            if (lit.getLanguage().isPresent()) {
                out.append('@').append(lit.getLanguage().get());
            } else if (lit.getDatatype() != null) {
                // Skip xsd:string (SPARQL default) to keep the wire small,
                // but emit every other datatype so the remote sees the
                // exact type the caller had in scope.
                final String dtIri = lit.getDatatype().stringValue();
                if (!"http://www.w3.org/2001/XMLSchema#string".equals(dtIri)) {
                    out.append("^^<").append(dtIri).append('>');
                }
            }
        } else if (value instanceof BNode bn) {
            // Blank nodes inside a shipped SERVICE body would be a
            // caller-side authorship error — the rewrite pass never
            // produces them. Fail loudly rather than emitting `_:x`
            // that could accidentally join at the remote.
            throw new UnsupportedOperationException(
                    "BgpSparqlRenderer: bnode in shipped SERVICE body: " + bn.stringValue());
        } else {
            throw new UnsupportedOperationException(
                    "BgpSparqlRenderer: unsupported Value type: "
                            + value.getClass().getName());
        }
    }

    private static void escapeLiteral(final String lex, final StringBuilder out) {
        for (int i = 0; i < lex.length(); i++) {
            final char c = lex.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"'  -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default   -> out.append(c);
            }
        }
    }

    private static void renderCondition(final ValueExpr expr, final StringBuilder out) {
        if (expr instanceof And a) {
            out.append('(');
            renderCondition(a.getLeftArg(), out);
            out.append(") && (");
            renderCondition(a.getRightArg(), out);
            out.append(')');
        } else if (expr instanceof Or o) {
            out.append('(');
            renderCondition(o.getLeftArg(), out);
            out.append(") || (");
            renderCondition(o.getRightArg(), out);
            out.append(')');
        } else if (expr instanceof Compare c) {
            renderCondition(c.getLeftArg(), out);
            out.append(' ').append(compareOp(c)).append(' ');
            renderCondition(c.getRightArg(), out);
        } else if (expr instanceof Var v) {
            renderVar(v, out);
        } else if (expr instanceof ValueConstant vc) {
            renderValue(vc.getValue(), out);
        } else {
            throw new UnsupportedOperationException(
                    "BgpSparqlRenderer: unsupported ValueExpr in filter: "
                            + expr.getClass().getName());
        }
    }

    private static String compareOp(final Compare c) {
        return switch (c.getOperator()) {
            case EQ -> "=";
            case NE -> "!=";
            case LT -> "<";
            case LE -> "<=";
            case GT -> ">";
            case GE -> ">=";
        };
    }
}

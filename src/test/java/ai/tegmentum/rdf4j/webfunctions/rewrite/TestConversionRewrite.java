package ai.tegmentum.rdf4j.webfunctions.rewrite;

import org.eclipse.rdf4j.query.algebra.Extension;
import org.eclipse.rdf4j.query.algebra.QueryModelNode;
import org.eclipse.rdf4j.query.algebra.StatementPattern;
import org.eclipse.rdf4j.query.algebra.TupleExpr;
import org.eclipse.rdf4j.query.algebra.Union;
import org.eclipse.rdf4j.query.algebra.helpers.AbstractQueryModelVisitor;
import org.eclipse.rdf4j.query.parser.ParsedQuery;
import org.eclipse.rdf4j.query.parser.sparql.SPARQLParser;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class TestConversionRewrite {

    private static final String TARGET = "http://example/weight_kg";
    private static final String SOURCE = "http://example/weight_lb";
    private static final String EXPR   = "?source * 0.453592";

    private static ConversionRegistry singleRule() {
        try {
            final List<String[]> rows = new ArrayList<>();
            rows.add(new String[]{TARGET, SOURCE, EXPR});
            return ConversionRegistry.of(rows);
        } catch (org.eclipse.rdf4j.query.MalformedQueryException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    public void specificGraphRewritesToExtension() {
        final String graphIri = ConversionRegistry.mintGraphIri(TARGET, SOURCE);
        final String query = "SELECT ?kg WHERE { GRAPH <" + graphIri + "> { ?item <" + TARGET + "> ?kg } }";
        final ParsedQuery parsed = new SPARQLParser().parseQuery(query, null);
        final ConversionRewrite rewrite = new ConversionRewrite(singleRule());
        rewrite.optimize(parsed.getTupleExpr(), null, null);

        assertThat(rewrite.rewriteCount()).isEqualTo(1);
        final List<StatementPattern> sps = collectSps(parsed.getTupleExpr());
        // The rewritten SP has the SOURCE predicate.
        boolean sourceFound = false;
        for (StatementPattern sp : sps) {
            if (sp.getPredicateVar() != null && sp.getPredicateVar().hasValue()
                    && SOURCE.equals(sp.getPredicateVar().getValue().stringValue())) {
                sourceFound = true;
            }
        }
        assertThat(sourceFound).isTrue();
        assertThat(collectExtensions(parsed.getTupleExpr())).isNotEmpty();
    }

    @Test
    public void variableGraphRewritesToUnion() {
        final String query = "SELECT ?g ?kg WHERE { GRAPH ?g { ?item <" + TARGET + "> ?kg } }";
        final ParsedQuery parsed = new SPARQLParser().parseQuery(query, null);
        final ConversionRewrite rewrite = new ConversionRewrite(singleRule());
        rewrite.optimize(parsed.getTupleExpr(), null, null);

        assertThat(rewrite.rewriteCount()).isEqualTo(1);
        // Single rule &rarr; Union has one branch collapsed into just the branch.
        // Confirm at least one Extension exists.
        assertThat(collectExtensions(parsed.getTupleExpr())).isNotEmpty();
    }

    @Test
    public void nonConversionGraphLeftAlone() {
        final String query = "SELECT ?p WHERE { GRAPH <http://example/regular> { ?item <" + TARGET + "> ?kg } }";
        final ParsedQuery parsed = new SPARQLParser().parseQuery(query, null);
        final ConversionRewrite rewrite = new ConversionRewrite(singleRule());
        rewrite.optimize(parsed.getTupleExpr(), null, null);
        assertThat(rewrite.rewriteCount()).isZero();
    }

    private static List<StatementPattern> collectSps(final TupleExpr expr) {
        final List<StatementPattern> out = new ArrayList<>();
        expr.visit(new AbstractQueryModelVisitor<RuntimeException>() {
            @Override public void meet(final StatementPattern sp) { out.add(sp); }
            @Override protected void meetNode(final QueryModelNode n) { n.visitChildren(this); }
        });
        return out;
    }

    private static List<Extension> collectExtensions(final TupleExpr expr) {
        final List<Extension> out = new ArrayList<>();
        expr.visit(new AbstractQueryModelVisitor<RuntimeException>() {
            @Override public void meet(final Extension ext) { out.add(ext); super.meet(ext); }
            @Override protected void meetNode(final QueryModelNode n) { n.visitChildren(this); }
        });
        return out;
    }

    static void _touch(final Union u) { u.getLeftArg(); }
}

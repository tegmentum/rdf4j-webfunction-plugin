package ai.tegmentum.rdf4j.webfunctions.rewrite;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.algebra.QueryModelNode;
import org.eclipse.rdf4j.query.algebra.StatementPattern;
import org.eclipse.rdf4j.query.algebra.Var;
import org.eclipse.rdf4j.query.algebra.helpers.AbstractQueryModelVisitor;
import org.eclipse.rdf4j.query.impl.MapBindingSet;
import org.eclipse.rdf4j.query.parser.ParsedQuery;
import org.eclipse.rdf4j.query.parser.sparql.SPARQLParser;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AliasRewrite}. Builds real {@link
 * org.eclipse.rdf4j.query.algebra.TupleExpr} trees via the SPARQL parser
 * &mdash; per the caller's constraint, no algebra mocking.
 */
public class TestAliasRewrite {

    private static final ValueFactory VF = SimpleValueFactory.getInstance();

    @Test
    public void rewritesAliasToCanonicalInSubjectPosition() {
        final String query = "SELECT ?p ?o WHERE { <http://example/alias> ?p ?o }";
        final ParsedQuery parsed = new SPARQLParser().parseQuery(query, null);

        final AliasMap map = AliasMapFactory.of("http://example/alias", "http://example/canonical");
        final AliasRewrite rewrite = new AliasRewrite(map);
        final AliasRewriteState state = rewrite.rewriteQuery(parsed.getTupleExpr());

        // Subject variable's Value should now be the canonical.
        final List<Var> vars = collectVars(parsed.getTupleExpr());
        boolean foundCanonical = false;
        boolean foundAlias = false;
        for (Var v : vars) {
            if (v.hasValue() && v.getValue() instanceof IRI iri) {
                if ("http://example/canonical".equals(iri.stringValue())) foundCanonical = true;
                if ("http://example/alias".equals(iri.stringValue()))     foundAlias = true;
            }
        }
        assertThat(foundCanonical).as("canonical present after rewrite").isTrue();
        assertThat(foundAlias).as("alias must be gone after rewrite").isFalse();
        assertThat(state.isActive()).isTrue();
        assertThat(state.recoverAlias("http://example/canonical"))
                .isEqualTo("http://example/alias");
    }

    @Test
    public void reverseMapDrivesBindingSetRewrite() {
        final AliasMap map = AliasMapFactory.of("http://example/alias", "http://example/canonical");
        final AliasRewrite rewrite = new AliasRewrite(map);
        final String query = "SELECT ?s WHERE { ?s <http://example/alias> ?o }";
        final ParsedQuery parsed = new SPARQLParser().parseQuery(query, null);
        final AliasRewriteState state = rewrite.rewriteQuery(parsed.getTupleExpr());

        final MapBindingSet input = new MapBindingSet();
        input.addBinding("s", VF.createIRI("http://example/canonical"));
        final IRI recovered = (IRI) state.rewriteBindingSet(input).getValue("s");
        assertThat(recovered.stringValue()).isEqualTo("http://example/alias");
    }

    @Test
    public void emptyAliasMapIsPassThrough() {
        final String query = "SELECT ?s WHERE { ?s <http://example/pred> ?o }";
        final ParsedQuery parsed = new SPARQLParser().parseQuery(query, null);
        final AliasRewrite rewrite = new AliasRewrite(AliasMap.empty());
        final AliasRewriteState state = rewrite.rewriteQuery(parsed.getTupleExpr());
        assertThat(state.isActive()).isFalse();
    }

    private static List<Var> collectVars(final org.eclipse.rdf4j.query.algebra.TupleExpr expr) {
        final List<Var> out = new ArrayList<>();
        expr.visit(new AbstractQueryModelVisitor<RuntimeException>() {
            @Override
            public void meet(final StatementPattern sp) {
                if (sp.getSubjectVar()   != null) out.add(sp.getSubjectVar());
                if (sp.getPredicateVar() != null) out.add(sp.getPredicateVar());
                if (sp.getObjectVar()    != null) out.add(sp.getObjectVar());
                if (sp.getContextVar()   != null) out.add(sp.getContextVar());
            }
            @Override protected void meetNode(final QueryModelNode n) { n.visitChildren(this); }
        });
        return out;
    }
}

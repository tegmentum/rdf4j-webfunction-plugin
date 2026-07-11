package ai.tegmentum.rdf4j.webfunctions.rewrite;

import org.eclipse.rdf4j.query.algebra.QueryModelNode;
import org.eclipse.rdf4j.query.algebra.Service;
import org.eclipse.rdf4j.query.algebra.StatementPattern;
import org.eclipse.rdf4j.query.algebra.helpers.AbstractQueryModelVisitor;
import org.eclipse.rdf4j.query.parser.ParsedQuery;
import org.eclipse.rdf4j.query.parser.sparql.SPARQLParser;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class TestShapeRewrite {

    private static ShapeEntry personShape() {
        final Map<String, String> cols = new LinkedHashMap<>();
        cols.put("http://example/name", "name");
        cols.put("http://example/age",  "age");
        return new ShapeEntry(
                "person",
                "{\"name\":\"person\",\"anchor\":{\"class\":\"http://example/Person\"},"
                        + "\"columns\":[{\"name\":\"id\",\"role\":\"subject_iri\"},"
                        + "{\"name\":\"name\",\"role\":\"column\",\"predicate\":\"http://example/name\"},"
                        + "{\"name\":\"age\",\"role\":\"column\",\"predicate\":\"http://example/age\"}]}",
                "http://example/Person",
                cols,
                "id");
    }

    @Test
    public void rewritesBgpMatchingRegisteredShape() {
        final String query = ""
                + "SELECT ?s ?n ?a WHERE {\n"
                + "  ?s <http://example/name> ?n .\n"
                + "  ?s <http://example/age>  ?a .\n"
                + "}";
        final ParsedQuery parsed = new SPARQLParser().parseQuery(query, null);
        final ShapeRegistry registry = ShapeRegistry.of(List.of(personShape()));
        final ShapeRewrite rewrite = new ShapeRewrite(registry, "http://wf/fetch.wasm");
        final int count = rewrite.rewritePattern(parsed.getTupleExpr());

        assertThat(count).isEqualTo(1);
        assertThat(collectServices(parsed.getTupleExpr())).hasSize(1);
    }

    @Test
    public void skipsBgpWithForeignPredicate() {
        final String query = ""
                + "SELECT ?s ?n ?x WHERE {\n"
                + "  ?s <http://example/name>       ?n .\n"
                + "  ?s <http://example/unrelated>  ?x .\n"
                + "}";
        final ParsedQuery parsed = new SPARQLParser().parseQuery(query, null);
        final ShapeRegistry registry = ShapeRegistry.of(List.of(personShape()));
        final ShapeRewrite rewrite = new ShapeRewrite(registry, "http://wf/fetch.wasm");
        rewrite.rewritePattern(parsed.getTupleExpr());
        assertThat(collectServices(parsed.getTupleExpr())).isEmpty();
    }

    private static List<Service> collectServices(final org.eclipse.rdf4j.query.algebra.TupleExpr expr) {
        final java.util.List<Service> out = new java.util.ArrayList<>();
        expr.visit(new AbstractQueryModelVisitor<RuntimeException>() {
            @Override public void meet(final Service s) { out.add(s); super.meet(s); }
            @Override protected void meetNode(final QueryModelNode n) { n.visitChildren(this); }
        });
        return out;
    }

    // Suppress unused-import lint on StatementPattern.
    static void _touch(final StatementPattern sp) { sp.getSubjectVar(); }
}

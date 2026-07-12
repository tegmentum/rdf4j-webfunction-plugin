package ai.tegmentum.rdf4j.webfunctions.rewrite;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.query.algebra.QueryModelNode;
import org.eclipse.rdf4j.query.algebra.Service;
import org.eclipse.rdf4j.query.algebra.StatementPattern;
import org.eclipse.rdf4j.query.algebra.TupleExpr;
import org.eclipse.rdf4j.query.algebra.Var;
import org.eclipse.rdf4j.query.algebra.helpers.AbstractQueryModelVisitor;
import org.eclipse.rdf4j.query.parser.ParsedQuery;
import org.eclipse.rdf4j.query.parser.sparql.SPARQLParser;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link RewritePipeline}. The pipeline is a thin
 * ordered composition of the four rewrite passes; these tests verify
 *
 * <ol>
 *   <li>Fully-empty registries yield an identity pipeline (passthrough).</li>
 *   <li>A pipeline with all four passes populated combines their
 *       transformations on a single hand-crafted query: an alias in
 *       subject position of a shape-covered BGP is rewritten to its
 *       canonical AND the BGP is folded to a {@link Service} block.</li>
 * </ol>
 */
public class RewritePipelineTest {

    @Test
    public void emptyPipelineIsIdentity() {
        final String sparql = "SELECT ?s ?p ?o WHERE { ?s ?p ?o }";
        final ParsedQuery parsed = new SPARQLParser().parseQuery(sparql, null);

        final String before = parsed.getTupleExpr().toString();
        final RewritePipeline empty = RewritePipeline.builder().build();
        final AliasRewriteState state = empty.apply(parsed.getTupleExpr(), null, null);
        assertThat(empty.isEmpty()).isTrue();
        assertThat(state.isActive()).isFalse();
        assertThat(parsed.getTupleExpr().toString()).isEqualTo(before);
        assertThat(collectServices(parsed.getTupleExpr())).isEmpty();
    }

    @Test
    public void combinedPassesFoldAliasAndShapeRewrite() {
        // Query touches the "person" shape through its column predicates,
        // AND names the anchor class via an alias. After the pipeline
        // runs:
        //   - alias  <ex:PersonAlias> -> canonical <ex:Person>
        //   - shape  covers the BGP -> a SERVICE <wf:call> replaces it
        final String sparql = ""
                + "SELECT ?s ?n ?a WHERE {\n"
                + "  ?s a <http://example/PersonAlias> .\n"
                + "  ?s <http://example/name> ?n .\n"
                + "  ?s <http://example/age>  ?a .\n"
                + "}";
        final ParsedQuery parsed = new SPARQLParser().parseQuery(sparql, null);

        final AliasMap aliases = AliasMap.of(Map.of(
                "http://example/PersonAlias", "http://example/Person"));

        final Map<String, String> cols = new LinkedHashMap<>();
        cols.put("http://example/name", "name");
        cols.put("http://example/age",  "age");
        final ShapeEntry person = new ShapeEntry(
                "person",
                "{\"name\":\"person\",\"anchor\":{\"class\":\"http://example/Person\"},"
                        + "\"columns\":[{\"name\":\"id\",\"role\":\"subject_iri\"},"
                        + "{\"name\":\"name\",\"role\":\"column\",\"predicate\":\"http://example/name\"},"
                        + "{\"name\":\"age\",\"role\":\"column\",\"predicate\":\"http://example/age\"}]}",
                "http://example/Person",
                cols,
                "id");
        final ShapeRegistry shapes = ShapeRegistry.of(List.of(person));

        final RewritePipeline pipeline = RewritePipeline.builder()
                .aliasMap(aliases)
                .shapeRegistry(shapes)
                .wfFetchUrl("http://wf/fetch.wasm")
                .build();

        final AliasRewriteState state = pipeline.apply(parsed.getTupleExpr(), null, null);

        // Alias substitution recorded and reverse map active.
        assertThat(state.isActive()).isTrue();
        assertThat(state.recoverAlias("http://example/Person"))
                .isEqualTo("http://example/PersonAlias");
        // The alias literal is gone from the tree.
        final List<Var> vars = collectVars(parsed.getTupleExpr());
        boolean sawAlias = false;
        for (Var v : vars) {
            if (v.hasValue() && v.getValue() instanceof IRI iri
                    && "http://example/PersonAlias".equals(iri.stringValue())) {
                sawAlias = true;
            }
        }
        assertThat(sawAlias).as("alias must be gone after rewrite").isFalse();

        // Shape rewrite installed the SERVICE <wf:call> block.
        assertThat(collectServices(parsed.getTupleExpr())).hasSize(1);
    }

    private static List<Service> collectServices(final TupleExpr expr) {
        final List<Service> out = new ArrayList<>();
        expr.visit(new AbstractQueryModelVisitor<RuntimeException>() {
            @Override public void meet(final Service s) { out.add(s); super.meet(s); }
            @Override protected void meetNode(final QueryModelNode n) { n.visitChildren(this); }
        });
        return out;
    }

    private static List<Var> collectVars(final TupleExpr expr) {
        final List<Var> out = new ArrayList<>();
        expr.visit(new AbstractQueryModelVisitor<RuntimeException>() {
            @Override
            public void meet(final StatementPattern sp) {
                if (sp.getSubjectVar()   != null) out.add(sp.getSubjectVar());
                if (sp.getPredicateVar() != null) out.add(sp.getPredicateVar());
                if (sp.getObjectVar()    != null) out.add(sp.getObjectVar());
                if (sp.getContextVar()   != null) out.add(sp.getContextVar());
            }
            @Override
            public void meet(final Var v) { out.add(v); super.meet(v); }
            @Override protected void meetNode(final QueryModelNode n) { n.visitChildren(this); }
        });
        return out;
    }
}

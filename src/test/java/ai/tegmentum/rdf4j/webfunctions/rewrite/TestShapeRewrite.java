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

    /**
     * Regression: after a prior federation pass lifts one triple pattern
     * into a {@link Service} and joins it as a sibling of the residual
     * shape-eligible SPs, the shape rewrite must still fire on those
     * residuals (wf-conformance {@code federation_wf_fetch.toml}).
     * Prior to this fix, {@code meet(Join)} required a "pure BGP"
     * subtree and refused to descend past the Service sibling.
     */
    @Test
    public void rewritesResidualBgpAlongsideSiblingService() {
        // Simulate a post-federation algebra: a wf-federation source
        // has already been lifted into SERVICE, sitting as a sibling of
        // the shape-eligible SPs. Injecting SERVICE via SPARQL text is
        // the simplest way to build that shape.
        final String query = ""
                + "SELECT ?s ?n ?a ?label WHERE {\n"
                + "  SERVICE <http://example/labels> { ?s <http://example/label> ?label . }\n"
                + "  ?s <http://example/name> ?n .\n"
                + "  ?s <http://example/age>  ?a .\n"
                + "}";
        final ParsedQuery parsed = new SPARQLParser().parseQuery(query, null);
        final ShapeRegistry registry = ShapeRegistry.of(List.of(personShape()));
        final ShapeRewrite rewrite = new ShapeRewrite(registry, "http://wf/fetch.wasm");
        final int count = rewrite.rewritePattern(parsed.getTupleExpr());

        assertThat(count).isEqualTo(1);
        // Two Services now: the pre-existing federation Service (the
        // one against http://example/labels) and the wf:call Service
        // the shape rewrite just emitted.
        final List<Service> services = collectServices(parsed.getTupleExpr());
        assertThat(services).hasSize(2);
        final List<String> refs = services.stream()
                .map(s -> s.getServiceRef().getValue().stringValue())
                .toList();
        assertThat(refs).contains("http://example/labels");
        assertThat(refs).anyMatch(r -> r.endsWith("/ns/webfunction/call"));
    }

    @Test
    public void graphVarWrapsServiceInExtensionBindingVirtualIri() {
        // `GRAPH ?g { ?s :name ?n; :age ?a }` — the rewrite emits a
        // SERVICE wrapped in an Extension that binds ?g to the
        // shape's virtual IRI.
        final String query = ""
                + "SELECT * WHERE {\n"
                + "  GRAPH ?g { ?s <http://example/name> ?n . ?s <http://example/age> ?a . }\n"
                + "}";
        final ParsedQuery parsed = new SPARQLParser().parseQuery(query, null);
        final ShapeRegistry registry = ShapeRegistry.of(List.of(personShape()));
        final ShapeRewrite rewrite = new ShapeRewrite(registry, "http://wf/fetch.wasm");
        final int count = rewrite.rewritePattern(parsed.getTupleExpr());
        assertThat(count).isEqualTo(1);
        // The SERVICE must be emitted, and there must be an Extension
        // that binds ?g to <urn:wf:shape:person>.
        assertThat(collectServices(parsed.getTupleExpr())).hasSize(1);
        assertThat(collectExtensions(parsed.getTupleExpr()))
                .anySatisfy(ext -> {
                    final boolean bindsG = ext.getElements().stream().anyMatch(el ->
                            "g".equals(el.getName())
                                    && el.getExpr() instanceof org.eclipse.rdf4j.query.algebra.ValueConstant vc
                                    && ShapeRewrite.shapeVirtualGraphIri("person")
                                            .equals(vc.getValue().stringValue()));
                    assertThat(bindsG).as("Extension binds ?g to virtual shape IRI").isTrue();
                });
    }

    @Test
    public void graphMatchingVirtualIriUnwrapsToPlainService() {
        // `GRAPH <urn:wf:shape:person> { ... }` — the outer IRI is
        // the shape's virtual IRI, so the rewrite fires and emits
        // a plain SERVICE with no Extension wrapper on ?g.
        final String virt = ShapeRewrite.shapeVirtualGraphIri("person");
        final String query = ""
                + "SELECT * WHERE {\n"
                + "  GRAPH <" + virt + "> { ?s <http://example/name> ?n . ?s <http://example/age> ?a . }\n"
                + "}";
        final ParsedQuery parsed = new SPARQLParser().parseQuery(query, null);
        final ShapeRegistry registry = ShapeRegistry.of(List.of(personShape()));
        final ShapeRewrite rewrite = new ShapeRewrite(registry, "http://wf/fetch.wasm");
        final int count = rewrite.rewritePattern(parsed.getTupleExpr());
        assertThat(count).isEqualTo(1);
        assertThat(collectServices(parsed.getTupleExpr())).hasSize(1);
        // No Extension binding a graph variable.
        assertThat(collectExtensions(parsed.getTupleExpr())).allSatisfy(ext ->
                assertThat(ext.getElements()).noneMatch(el -> "g".equals(el.getName())));
    }

    @Test
    public void graphWithForeignIriLeavesRewriteDisabled() {
        // `GRAPH <http://example/other> { ... }` — the outer IRI does
        // not match the shape's virtual IRI, so no rewrite fires; the
        // StatementPatterns retain their original contextVar.
        final String query = ""
                + "SELECT * WHERE {\n"
                + "  GRAPH <http://example/other> { ?s <http://example/name> ?n . ?s <http://example/age> ?a . }\n"
                + "}";
        final ParsedQuery parsed = new SPARQLParser().parseQuery(query, null);
        final ShapeRegistry registry = ShapeRegistry.of(List.of(personShape()));
        final ShapeRewrite rewrite = new ShapeRewrite(registry, "http://wf/fetch.wasm");
        final int count = rewrite.rewritePattern(parsed.getTupleExpr());
        assertThat(count).isZero();
        assertThat(collectServices(parsed.getTupleExpr())).isEmpty();
    }

    private static List<org.eclipse.rdf4j.query.algebra.Extension> collectExtensions(
            final org.eclipse.rdf4j.query.algebra.TupleExpr expr) {
        final List<org.eclipse.rdf4j.query.algebra.Extension> out = new java.util.ArrayList<>();
        expr.visit(new AbstractQueryModelVisitor<RuntimeException>() {
            @Override
            public void meet(final org.eclipse.rdf4j.query.algebra.Extension ext) {
                out.add(ext);
                super.meet(ext);
            }
            @Override protected void meetNode(final QueryModelNode n) { n.visitChildren(this); }
        });
        return out;
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

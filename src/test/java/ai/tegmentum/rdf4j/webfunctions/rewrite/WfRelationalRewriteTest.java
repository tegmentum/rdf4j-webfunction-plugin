package ai.tegmentum.rdf4j.webfunctions.rewrite;

import ai.tegmentum.rdf4j.webfunctions.rewrite.FederationRegistry.FederationSource;
import ai.tegmentum.rdf4j.webfunctions.rewrite.FederationRegistry.SourceType;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.query.algebra.QueryModelNode;
import org.eclipse.rdf4j.query.algebra.Service;
import org.eclipse.rdf4j.query.algebra.StatementPattern;
import org.eclipse.rdf4j.query.algebra.TupleExpr;
import org.eclipse.rdf4j.query.algebra.Var;
import org.eclipse.rdf4j.query.algebra.helpers.AbstractQueryModelVisitor;
import org.eclipse.rdf4j.query.parser.ParsedQuery;
import org.eclipse.rdf4j.query.parser.sparql.SPARQLParser;
import org.junit.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TupleExpr-level tests for {@link WfRelationalRewrite}. Mirrors the
 * scenarios in {@code oxigraph-wf/src/wf_relational_rewrite.rs::tests}
 * so all four engine ports converge on identical fold semantics against
 * the same fixture JSON.
 */
public class WfRelationalRewriteTest {

    private static final String WF_CALL_IRI = "http://tegmentum.ai/ns/webfunction/call";
    private static final String WF_FETCH_URL = "file:///opt/wf_fetch.wasm";

    private static ParsedQuery parse(final String sparql) {
        return new SPARQLParser().parseQuery(sparql, null);
    }

    private static final String CUSTOMERS_JSON = """
            {
              "sources": [{
                "name": "customers",
                "type": "wf-relational",
                "endpoint": "postgres://user@localhost/mydb",
                "predicates": ["http://example.com/name", "http://example.com/tier"],
                "relational": {
                  "sink_kind": "postgres",
                  "table": "customers",
                  "subject_column": "id",
                  "anchor": {"class": "http://example.com/Customer"},
                  "columns": [
                    {"name": "id",   "role": "subject_iri", "type": "iri"},
                    {"name": "name", "role": "column", "type": "string",
                     "predicate": "http://example.com/name"},
                    {"name": "tier", "role": "column", "type": "string",
                     "predicate": "http://example.com/tier"}
                  ],
                  "emit_provenance": true,
                  "iri_template": "{id}",
                  "schema_version": "1"
                }
              }]
            }
            """;

    private static WfRelationalRegistry customersRegistry() {
        try {
            final JsonNode root = JsonMapper.builder().build().readTree(CUSTOMERS_JSON);
            return WfRelationalRegistry.fromJson(root);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static FederationRegistry customersFederationRegistry() {
        return FederationRegistry.of(List.of(
                new FederationSource("customers", SourceType.WF_RELATIONAL,
                        "postgres://user@localhost/mydb",
                        List.of("http://example.com/name", "http://example.com/tier"),
                        OptionalInt.empty(), Optional.empty(),
                        OptionalLong.empty(), java.util.Map.of())));
    }

    private static List<Service> collectServices(final TupleExpr expr) {
        final List<Service> out = new ArrayList<>();
        expr.visit(new AbstractQueryModelVisitor<RuntimeException>() {
            @Override public void meet(final Service s) { out.add(s); super.meet(s); }
            @Override protected void meetNode(final QueryModelNode n) { n.visitChildren(this); }
        });
        return out;
    }

    private static String descriptorArgJson(final TupleExpr expr) {
        final String wfArg = "http://tegmentum.ai/ns/webfunction/arg";
        final List<String> found = new ArrayList<>();
        expr.visit(new AbstractQueryModelVisitor<RuntimeException>() {
            @Override
            public void meet(final StatementPattern sp) {
                final Var p = sp.getPredicateVar();
                final Var o = sp.getObjectVar();
                if (p != null && p.hasValue() && p.getValue() instanceof IRI iri
                        && wfArg.equals(iri.stringValue())
                        && o != null && o.hasValue() && o.getValue() instanceof Literal lit) {
                    found.add(lit.stringValue());
                }
                super.meet(sp);
            }
            @Override protected void meetNode(final QueryModelNode n) { n.visitChildren(this); }
        });
        assertThat(found).as("expected exactly one wf:arg literal").hasSize(1);
        return found.get(0);
    }

    private static JsonNode parseJson(final String json) {
        try {
            return JsonMapper.builder().build().readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ---------------------------------------------------------------------
    // Positive fold
    // ---------------------------------------------------------------------

    @Test
    public void foldsWfRelationalServiceIntoWfCallEnvelope() {
        final ParsedQuery pq = parse(""
                + "PREFIX ex: <http://example.com/>\n"
                + "SELECT ?c ?name ?tier WHERE {\n"
                + "  SERVICE <wf-relational:customers> {\n"
                + "    ?c ex:name ?name . ?c ex:tier ?tier .\n"
                + "  }\n"
                + "}");
        final WfRelationalRewrite rw = new WfRelationalRewrite(
                customersFederationRegistry(), customersRegistry(), WF_FETCH_URL);
        assertThat(rw.rewritePattern(pq.getTupleExpr())).isEqualTo(1);
        final List<Service> services = collectServices(pq.getTupleExpr());
        assertThat(services).hasSize(1);
        final Var ref = services.get(0).getServiceRef();
        assertThat(ref.hasValue()).isTrue();
        assertThat(((IRI) ref.getValue()).stringValue()).isEqualTo(WF_CALL_IRI);
    }

    @Test
    public void foldBakesPostgresSinkKindAndUrlInDescriptor() {
        final ParsedQuery pq = parse(""
                + "PREFIX ex: <http://example.com/>\n"
                + "SELECT ?c ?name WHERE {\n"
                + "  SERVICE <wf-relational:customers> { ?c ex:name ?name }\n"
                + "}");
        new WfRelationalRewrite(customersFederationRegistry(), customersRegistry(), WF_FETCH_URL)
                .rewritePattern(pq.getTupleExpr());
        final JsonNode d = parseJson(descriptorArgJson(pq.getTupleExpr()));
        assertThat(d.get("sink_kind").asString()).isEqualTo("postgres");
        assertThat(d.get("sink").asString())
                .isEqualTo("postgres://user@localhost/mydb#customers");
        assertThat(d.get("include_graph").asBoolean()).isFalse();
        assertThat(d.get("table").asString()).isEqualTo("customers");
        assertThat(d.get("subject_column").asString()).isEqualTo("id");
        assertThat(d.get("anchor").get("class").asString())
                .isEqualTo("http://example.com/Customer");
    }

    /**
     * Descriptor with {@code emit_provenance = true} + {@code schema_version}
     * set carries both through so the guest can attach
     * {@code ?_shape_version} bindings per row (memo &sect;07 provenance
     * sidecar).
     */
    @Test
    public void foldCarriesShapeVersionProvenanceThrough() {
        final ParsedQuery pq = parse(""
                + "PREFIX ex: <http://example.com/>\n"
                + "SELECT ?c ?name WHERE {\n"
                + "  SERVICE <wf-relational:customers> { ?c ex:name ?name }\n"
                + "}");
        new WfRelationalRewrite(customersFederationRegistry(), customersRegistry(), WF_FETCH_URL)
                .rewritePattern(pq.getTupleExpr());
        final JsonNode d = parseJson(descriptorArgJson(pq.getTupleExpr()));
        assertThat(d.get("emit_provenance").asBoolean()).isTrue();
        assertThat(d.get("schema_version").asString()).isEqualTo("1");
    }

    // ---------------------------------------------------------------------
    // Short-circuits & guards
    // ---------------------------------------------------------------------

    @Test
    public void emptyRelationalRegistryShortCircuits() {
        final ParsedQuery pq = parse(""
                + "PREFIX ex: <http://example.com/>\n"
                + "SELECT ?c ?name WHERE {\n"
                + "  SERVICE <wf-relational:customers> { ?c ex:name ?name }\n"
                + "}");
        final WfRelationalRewrite rw = new WfRelationalRewrite(
                customersFederationRegistry(), WfRelationalRegistry.empty(), WF_FETCH_URL);
        assertThat(rw.rewritePattern(pq.getTupleExpr())).isZero();
        final List<Service> services = collectServices(pq.getTupleExpr());
        assertThat(services).hasSize(1);
        assertThat(((IRI) services.get(0).getServiceRef().getValue()).stringValue())
                .isEqualTo("wf-relational:customers");
    }

    @Test
    public void emptyWfFetchUrlShortCircuits() {
        final ParsedQuery pq = parse(""
                + "PREFIX ex: <http://example.com/>\n"
                + "SELECT ?c ?name WHERE {\n"
                + "  SERVICE <wf-relational:customers> { ?c ex:name ?name }\n"
                + "}");
        final WfRelationalRewrite rw = new WfRelationalRewrite(
                customersFederationRegistry(), customersRegistry(), "");
        assertThat(rw.rewritePattern(pq.getTupleExpr())).isZero();
        assertThat(collectServices(pq.getTupleExpr())).hasSize(1);
    }

    @Test
    public void unknownSourceNameLeftAlone() {
        final ParsedQuery pq = parse(""
                + "PREFIX ex: <http://example.com/>\n"
                + "SELECT ?c ?name WHERE {\n"
                + "  SERVICE <wf-relational:unknown> { ?c ex:name ?name }\n"
                + "}");
        // Federation registry is empty here; passthrough on unknown name
        // is the "explicit SERVICE for a name we don't recognise" path.
        final WfRelationalRewrite rw = new WfRelationalRewrite(
                FederationRegistry.empty(), customersRegistry(), WF_FETCH_URL);
        assertThat(rw.rewritePattern(pq.getTupleExpr())).isZero();
        final List<Service> services = collectServices(pq.getTupleExpr());
        assertThat(services).hasSize(1);
        assertThat(((IRI) services.get(0).getServiceRef().getValue()).stringValue())
                .isEqualTo("wf-relational:unknown");
    }

    /**
     * Federation registry declares a source with the same name but a
     * different type. The defensive check refuses to fold. Synthetic
     * setup &mdash; real deployments keep the two aligned.
     */
    @Test
    public void wrongSourceTypeLeftAlone() {
        final FederationRegistry misaligned = FederationRegistry.of(List.of(
                new FederationSource("customers", SourceType.SPARQL,
                        "http://example/query", List.of(), OptionalInt.empty())));
        final ParsedQuery pq = parse(""
                + "PREFIX ex: <http://example.com/>\n"
                + "SELECT ?c ?name WHERE {\n"
                + "  SERVICE <wf-relational:customers> { ?c ex:name ?name }\n"
                + "}");
        final WfRelationalRewrite rw = new WfRelationalRewrite(
                misaligned, customersRegistry(), WF_FETCH_URL);
        assertThat(rw.rewritePattern(pq.getTupleExpr())).isZero();
    }

    // ---------------------------------------------------------------------
    // Pipeline wiring
    // ---------------------------------------------------------------------

    @Test
    public void pipelineWiresRewritePassWhenRegistryPopulated() {
        final RewritePipeline pipeline = RewritePipeline.builder()
                .federationRegistry(customersFederationRegistry())
                .wfRelationalRegistry(customersRegistry())
                .wfFetchUrl(WF_FETCH_URL)
                .build();
        final ParsedQuery pq = parse(""
                + "PREFIX ex: <http://example.com/>\n"
                + "SELECT ?c ?name WHERE {\n"
                + "  SERVICE <wf-relational:customers> { ?c ex:name ?name }\n"
                + "}");
        pipeline.apply(pq.getTupleExpr(), null, null);
        final List<Service> services = collectServices(pq.getTupleExpr());
        assertThat(services).hasSize(1);
        assertThat(((IRI) services.get(0).getServiceRef().getValue()).stringValue())
                .isEqualTo(WF_CALL_IRI);
    }

    @Test
    public void pipelineEmptyRelationalRegistryStaysInert() {
        final RewritePipeline pipeline = RewritePipeline.builder()
                .federationRegistry(customersFederationRegistry())
                .wfFetchUrl(WF_FETCH_URL)
                .build();
        final ParsedQuery pq = parse(""
                + "PREFIX ex: <http://example.com/>\n"
                + "SELECT ?c ?name WHERE {\n"
                + "  SERVICE <wf-relational:customers> { ?c ex:name ?name }\n"
                + "}");
        pipeline.apply(pq.getTupleExpr(), null, null);
        final List<Service> services = collectServices(pq.getTupleExpr());
        assertThat(services).hasSize(1);
        assertThat(((IRI) services.get(0).getServiceRef().getValue()).stringValue())
                .isEqualTo("wf-relational:customers");
    }

    // ---------------------------------------------------------------------
    // Registry parse — smoke check that the sidecar sees `relational`.
    // ---------------------------------------------------------------------

    @Test
    public void registrySkipsNonRelationalSources() {
        final String json = """
                {"sources": [
                  {"name": "products", "type": "sparql", "endpoint": "http://ex/query"},
                  {"name": "manuals", "type": "wf-search", "endpoint": "wf-search:manuals"}
                ]}""";
        try {
            final WfRelationalRegistry reg = WfRelationalRegistry.fromJsonText(json);
            assertThat(reg.isEmpty()).isTrue();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void registrySkipsRelationalWithoutDescriptorBlock() {
        final String json = """
                {"sources": [
                  {"name": "orphan", "type": "wf-relational", "endpoint": "postgres://ex/db"}
                ]}""";
        try {
            final WfRelationalRegistry reg = WfRelationalRegistry.fromJsonText(json);
            assertThat(reg.isEmpty()).isTrue();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void registryParsesCustomerEntry() {
        final WfRelationalRegistry reg = customersRegistry();
        assertThat(reg.size()).isEqualTo(1);
        final WfRelationalRegistry.RelationalEntry e = reg.byName("customers");
        assertThat(e).isNotNull();
        assertThat(e.endpoint()).isEqualTo("postgres://user@localhost/mydb");
        assertThat(e.descriptor().sinkKind()).isEqualTo("postgres");
        assertThat(e.descriptor().table()).isEqualTo("customers");
        assertThat(e.descriptor().subjectColumn()).isEqualTo("id");
        assertThat(e.descriptor().anchor().anchorClass())
                .isEqualTo("http://example.com/Customer");
        assertThat(e.descriptor().emitProvenance()).isTrue();
        assertThat(e.descriptor().schemaVersion()).isEqualTo("1");
        assertThat(e.descriptor().columnsByPredicate())
                .containsEntry("http://example.com/name", "name")
                .containsEntry("http://example.com/tier", "tier");
    }
}

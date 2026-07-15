package ai.tegmentum.rdf4j.webfunctions.rewrite;

import ai.tegmentum.rdf4j.webfunctions.rewrite.FederationRegistry.FederationSource;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TupleExpr-level tests for {@link WfRelationalRewrite}. Mirrors the
 * scenarios in {@code oxigraph-wf/src/wf_relational_rewrite.rs::tests}
 * so all four engine ports converge on identical fold semantics against
 * the same fixture JSON.
 *
 * <p>v0.3 &mdash; the sidecar {@code WfRelationalRegistry} was folded
 * into {@link FederationSource#relationalConfig()}. All fixtures below
 * build the federation registry from JSON so the {@code relational}
 * block on each {@code wf-relational} source travels with the source
 * entry the rewrite pass consults.
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

    /**
     * FederationRegistry parsed from {@link #CUSTOMERS_JSON}. The
     * top-level {@code relational} block on the source is captured on
     * {@link FederationSource#relationalConfig()} &mdash; the rewrite
     * pass reads it from there.
     */
    private static FederationRegistry customersFederationRegistry() {
        try {
            final JsonNode root = JsonMapper.builder().build().readTree(CUSTOMERS_JSON);
            return FederationRegistry.fromJson(root);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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
                customersFederationRegistry(), WF_FETCH_URL);
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
        new WfRelationalRewrite(customersFederationRegistry(), WF_FETCH_URL)
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
        new WfRelationalRewrite(customersFederationRegistry(), WF_FETCH_URL)
                .rewritePattern(pq.getTupleExpr());
        final JsonNode d = parseJson(descriptorArgJson(pq.getTupleExpr()));
        assertThat(d.get("emit_provenance").asBoolean()).isTrue();
        assertThat(d.get("schema_version").asString()).isEqualTo("1");
    }

    // ---------------------------------------------------------------------
    // Short-circuits & guards
    // ---------------------------------------------------------------------

    /**
     * Empty federation registry short-circuits &mdash; nothing to
     * fold. Matches the pre-v0.3 empty-sidecar short-circuit.
     */
    @Test
    public void emptyRegistryShortCircuits() {
        final ParsedQuery pq = parse(""
                + "PREFIX ex: <http://example.com/>\n"
                + "SELECT ?c ?name WHERE {\n"
                + "  SERVICE <wf-relational:customers> { ?c ex:name ?name }\n"
                + "}");
        final WfRelationalRewrite rw = new WfRelationalRewrite(
                FederationRegistry.empty(), WF_FETCH_URL);
        assertThat(rw.rewritePattern(pq.getTupleExpr())).isZero();
        final List<Service> services = collectServices(pq.getTupleExpr());
        assertThat(services).hasSize(1);
        assertThat(((IRI) services.get(0).getServiceRef().getValue()).stringValue())
                .isEqualTo("wf-relational:customers");
    }

    /**
     * v0.3 unification &mdash; a {@code wf-relational} source
     * registered with no {@code relational} block means
     * {@link FederationSource#relationalConfig()} is empty, and the
     * rewrite pass leaves the SERVICE alone. Matches the old sidecar's
     * "descriptor-missing &rarr; skip" behavior.
     */
    @Test
    public void wfRelationalWithoutConfigLeftAlone() {
        final String json = """
                {"sources": [{
                    "name": "customers",
                    "type": "wf-relational",
                    "endpoint": "postgres://ex/db"
                }]}""";
        final FederationRegistry fed;
        try {
            fed = FederationRegistry.fromJson(JsonMapper.builder().build().readTree(json));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        final ParsedQuery pq = parse(""
                + "PREFIX ex: <http://example.com/>\n"
                + "SELECT ?c ?name WHERE {\n"
                + "  SERVICE <wf-relational:customers> { ?c ex:name ?name }\n"
                + "}");
        final WfRelationalRewrite rw = new WfRelationalRewrite(fed, WF_FETCH_URL);
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
                customersFederationRegistry(), "");
        assertThat(rw.rewritePattern(pq.getTupleExpr())).isZero();
        assertThat(collectServices(pq.getTupleExpr())).hasSize(1);
    }

    /**
     * Unknown source name &mdash; federation registry has {@code
     * customers} but the query references {@code unknown}. The rewrite
     * pass must leave the SERVICE alone.
     */
    @Test
    public void unknownSourceNameLeftAlone() {
        final ParsedQuery pq = parse(""
                + "PREFIX ex: <http://example.com/>\n"
                + "SELECT ?c ?name WHERE {\n"
                + "  SERVICE <wf-relational:unknown> { ?c ex:name ?name }\n"
                + "}");
        final WfRelationalRewrite rw = new WfRelationalRewrite(
                customersFederationRegistry(), WF_FETCH_URL);
        assertThat(rw.rewritePattern(pq.getTupleExpr())).isZero();
        final List<Service> services = collectServices(pq.getTupleExpr());
        assertThat(services).hasSize(1);
        assertThat(((IRI) services.get(0).getServiceRef().getValue()).stringValue())
                .isEqualTo("wf-relational:unknown");
    }

    /**
     * Federation registry has {@code customers} typed {@code sparql}
     * &mdash; the source-type check refuses to fold even though a
     * {@code relational} block is present (synthetic
     * misconfiguration). Real deployments always align the type + the
     * relational block.
     */
    @Test
    public void wrongSourceTypeLeftAlone() {
        final String json = """
                {"sources": [{
                    "name": "customers",
                    "type": "sparql",
                    "endpoint": "http://example/query",
                    "relational": {
                        "sink_kind": "postgres",
                        "table": "customers",
                        "subject_column": "id",
                        "columns": [
                            {"name": "id", "role": "subject_iri", "type": "iri"}
                        ]
                    }
                }]}""";
        final FederationRegistry misaligned;
        try {
            misaligned = FederationRegistry.fromJson(JsonMapper.builder().build().readTree(json));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        final ParsedQuery pq = parse(""
                + "PREFIX ex: <http://example.com/>\n"
                + "SELECT ?c ?name WHERE {\n"
                + "  SERVICE <wf-relational:customers> { ?c ex:name ?name }\n"
                + "}");
        final WfRelationalRewrite rw = new WfRelationalRewrite(misaligned, WF_FETCH_URL);
        assertThat(rw.rewritePattern(pq.getTupleExpr())).isZero();
    }

    // ---------------------------------------------------------------------
    // Pipeline wiring
    // ---------------------------------------------------------------------

    @Test
    public void pipelineWiresRewritePassWhenRegistryPopulated() {
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
                .isEqualTo(WF_CALL_IRI);
    }

    /**
     * A wf-relational source with no {@code relational} block on the
     * federation entry keeps the pipeline inert &mdash; the fold pass
     * refuses because {@code relationalConfig()} is empty.
     */
    @Test
    public void pipelineWfRelationalWithoutConfigStaysInert() {
        final String json = """
                {"sources": [{
                    "name": "customers",
                    "type": "wf-relational",
                    "endpoint": "postgres://ex/db"
                }]}""";
        final FederationRegistry fed;
        try {
            fed = FederationRegistry.fromJson(JsonMapper.builder().build().readTree(json));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        final RewritePipeline pipeline = RewritePipeline.builder()
                .federationRegistry(fed)
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
}

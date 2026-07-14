package ai.tegmentum.rdf4j.webfunctions.rewrite;

import ai.tegmentum.rdf4j.webfunctions.rewrite.FederationRegistry.FederationSource;
import ai.tegmentum.rdf4j.webfunctions.rewrite.FederationRegistry.SourceType;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.query.algebra.Filter;
import org.eclipse.rdf4j.query.algebra.QueryModelNode;
import org.eclipse.rdf4j.query.algebra.Service;
import org.eclipse.rdf4j.query.algebra.StatementPattern;
import org.eclipse.rdf4j.query.algebra.TupleExpr;
import org.eclipse.rdf4j.query.algebra.Union;
import org.eclipse.rdf4j.query.algebra.Var;
import org.eclipse.rdf4j.query.algebra.helpers.AbstractQueryModelVisitor;
import org.eclipse.rdf4j.query.algebra.helpers.collectors.StatementPatternCollector;
import org.eclipse.rdf4j.query.parser.ParsedQuery;
import org.eclipse.rdf4j.query.parser.sparql.SPARQLParser;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TupleExpr-level tests for {@link WfFederationRewrite}. Covers the
 * static-mode assignment rules from §04 of the design memo
 * ({@code wf-conformance/docs/design/wf-federation.md}) plus filter
 * pushdown from §05 and the wf-*: serviceRef emission from §06.
 */
public class TestWfFederationRewrite {

    private static ParsedQuery parse(final String sparql) {
        return new SPARQLParser().parseQuery(sparql, null);
    }

    private static FederationSource sparql(final String name, final String endpoint, final String... preds) {
        return new FederationSource(name, SourceType.SPARQL, endpoint,
                List.of(preds), OptionalInt.empty());
    }

    private static FederationSource wfSearch(final String name, final String... preds) {
        return new FederationSource(name, SourceType.WF_SEARCH, "wf-search:" + name,
                List.of(preds), OptionalInt.empty());
    }

    private static FederationSource wfFetch(final String name, final String... preds) {
        return new FederationSource(name, SourceType.WF_FETCH, "wf-fetch:" + name,
                List.of(preds), OptionalInt.empty());
    }

    private static FederationSource wfDoc(final String name, final String... preds) {
        return new FederationSource(name, SourceType.WF_DOCUMENT, "wf-document:" + name,
                List.of(preds), OptionalInt.empty());
    }

    private static List<Service> collectServices(final TupleExpr expr) {
        final List<Service> out = new ArrayList<>();
        expr.visit(new AbstractQueryModelVisitor<RuntimeException>() {
            @Override public void meet(final Service s) { out.add(s); super.meet(s); }
            @Override protected void meetNode(final QueryModelNode n) { n.visitChildren(this); }
        });
        return out;
    }

    private static List<Union> collectUnions(final TupleExpr expr) {
        final List<Union> out = new ArrayList<>();
        expr.visit(new AbstractQueryModelVisitor<RuntimeException>() {
            @Override public void meet(final Union u) { out.add(u); super.meet(u); }
            @Override protected void meetNode(final QueryModelNode n) { n.visitChildren(this); }
        });
        return out;
    }

    private static String serviceRefUrl(final Service s) {
        final Var ref = s.getServiceRef();
        assertThat(ref).isNotNull();
        assertThat(ref.hasValue()).isTrue();
        assertThat(ref.getValue()).isInstanceOf(IRI.class);
        return ((IRI) ref.getValue()).stringValue();
    }

    // ---------------------------------------------------------------------
    // No-op / passthrough
    // ---------------------------------------------------------------------

    @Test
    public void emptyRegistryNoop() {
        final FederationRegistry reg = FederationRegistry.empty();
        final InvokeRegistry inv = new InvokeRegistry();
        final ParsedQuery pq = parse(
                "SELECT ?s WHERE { ?s <http://ex/p> ?o }");
        final WfFederationRewrite rw = new WfFederationRewrite(reg, inv);
        assertThat(rw.rewritePattern(pq.getTupleExpr())).isZero();
        assertThat(collectServices(pq.getTupleExpr())).isEmpty();
    }

    @Test
    public void unregisteredPredicateLeftAlone() {
        // Registry knows p1; query hits p2.
        final FederationRegistry reg = FederationRegistry.of(List.of(
                sparql("a", "http://a/query", "http://ex/p1")));
        final InvokeRegistry inv = new InvokeRegistry();
        final ParsedQuery pq = parse(
                "SELECT ?s ?o WHERE { ?s <http://ex/p2> ?o }");
        final WfFederationRewrite rw = new WfFederationRewrite(reg, inv);
        assertThat(rw.rewritePattern(pq.getTupleExpr())).isZero();
        assertThat(collectServices(pq.getTupleExpr())).isEmpty();
    }

    // ---------------------------------------------------------------------
    // Unambiguous single-source
    // ---------------------------------------------------------------------

    @Test
    public void singlePatternWrappedInService() {
        final FederationRegistry reg = FederationRegistry.of(List.of(
                sparql("products", "http://prod/query", "http://ex/sku")));
        final InvokeRegistry inv = new InvokeRegistry();
        final ParsedQuery pq = parse(
                "SELECT ?s ?sku WHERE { ?s <http://ex/sku> ?sku }");
        final WfFederationRewrite rw = new WfFederationRewrite(reg, inv);
        assertThat(rw.rewritePattern(pq.getTupleExpr())).isEqualTo(1);
        final List<Service> services = collectServices(pq.getTupleExpr());
        assertThat(services).hasSize(1);
        assertThat(serviceRefUrl(services.get(0))).isEqualTo("http://prod/query");
        // The SP moved inside the Service body.
        assertThat(StatementPatternCollector.process(services.get(0).getServiceExpr()))
                .hasSize(1);
    }

    @Test
    public void sameSourceSharedSubjectSingleService() {
        // Two SPs, same source, shared subject variable → one Service
        // with a Join inside (the same-source grouping win from §04 step 3).
        final FederationRegistry reg = FederationRegistry.of(List.of(
                sparql("prod", "http://prod/query",
                        "http://ex/sku", "http://ex/price")));
        final InvokeRegistry inv = new InvokeRegistry();
        final ParsedQuery pq = parse(
                "SELECT ?s ?sku ?price WHERE {\n"
                        + "  ?s <http://ex/sku> ?sku .\n"
                        + "  ?s <http://ex/price> ?price .\n"
                        + "}");
        final WfFederationRewrite rw = new WfFederationRewrite(reg, inv);
        assertThat(rw.rewritePattern(pq.getTupleExpr())).isEqualTo(1);
        final List<Service> services = collectServices(pq.getTupleExpr());
        assertThat(services).hasSize(1);
        // Both SPs live under the single Service.
        assertThat(StatementPatternCollector.process(services.get(0).getServiceExpr()))
                .hasSize(2);
    }

    @Test
    public void sameSourceDisjointVariablesSplit() {
        // Two SPs, same source, but NO shared variables → two Services
        // (don't force a cross product at the source).
        final FederationRegistry reg = FederationRegistry.of(List.of(
                sparql("prod", "http://prod/query",
                        "http://ex/sku", "http://ex/price")));
        final InvokeRegistry inv = new InvokeRegistry();
        final ParsedQuery pq = parse(
                "SELECT ?a ?b ?c ?d WHERE {\n"
                        + "  ?a <http://ex/sku>   ?b .\n"
                        + "  ?c <http://ex/price> ?d .\n"
                        + "}");
        final WfFederationRewrite rw = new WfFederationRewrite(reg, inv);
        assertThat(rw.rewritePattern(pq.getTupleExpr())).isEqualTo(1);
        final List<Service> services = collectServices(pq.getTupleExpr());
        assertThat(services).hasSize(2);
    }

    // ---------------------------------------------------------------------
    // Different sources / cross-source join
    // ---------------------------------------------------------------------

    @Test
    public void twoSourcesTwoServices() {
        final FederationRegistry reg = FederationRegistry.of(List.of(
                sparql("prod",    "http://prod/query",    "http://ex/label"),
                sparql("reviews", "http://reviews/query", "http://ex/rating")));
        final InvokeRegistry inv = new InvokeRegistry();
        final ParsedQuery pq = parse(
                "SELECT ?p ?l ?r WHERE {\n"
                        + "  ?p <http://ex/label>  ?l .\n"
                        + "  ?p <http://ex/rating> ?r .\n"
                        + "}");
        final WfFederationRewrite rw = new WfFederationRewrite(reg, inv);
        assertThat(rw.rewritePattern(pq.getTupleExpr())).isEqualTo(1);
        final List<Service> services = collectServices(pq.getTupleExpr());
        assertThat(services).hasSize(2);
        assertThat(services).extracting(TestWfFederationRewrite::serviceRefUrl)
                .containsExactlyInAnyOrder(
                        "http://prod/query", "http://reviews/query");
    }

    // ---------------------------------------------------------------------
    // Multi-source predicate → UNION
    // ---------------------------------------------------------------------

    @Test
    public void multiSourcePredicateEmitsUnion() {
        final FederationRegistry reg = FederationRegistry.of(List.of(
                sparql("a", "http://a/query", "http://ex/shared"),
                sparql("b", "http://b/query", "http://ex/shared")));
        final InvokeRegistry inv = new InvokeRegistry();
        final ParsedQuery pq = parse(
                "SELECT ?s ?o WHERE { ?s <http://ex/shared> ?o }");
        final WfFederationRewrite rw = new WfFederationRewrite(reg, inv);
        assertThat(rw.rewritePattern(pq.getTupleExpr())).isEqualTo(1);
        // A Union covers the two per-source Services.
        assertThat(collectUnions(pq.getTupleExpr())).hasSize(1);
        final List<Service> services = collectServices(pq.getTupleExpr());
        assertThat(services).hasSize(2);
        assertThat(services).extracting(TestWfFederationRewrite::serviceRefUrl)
                .containsExactlyInAnyOrder("http://a/query", "http://b/query");
    }

    // ---------------------------------------------------------------------
    // Explicit SERVICE in the input — skip
    // ---------------------------------------------------------------------

    @Test
    public void explicitServiceLeftAlone() {
        // Even though ex:p is in the registry, an already-wrapped SERVICE
        // block means the caller chose the endpoint — respect that
        // (memo §04 step 1).
        final FederationRegistry reg = FederationRegistry.of(List.of(
                sparql("a", "http://a/query", "http://ex/p")));
        final InvokeRegistry inv = new InvokeRegistry();
        final ParsedQuery pq = parse(
                "SELECT ?s ?o WHERE {\n"
                        + "  SERVICE <http://other/query> { ?s <http://ex/p> ?o }\n"
                        + "}");
        final WfFederationRewrite rw = new WfFederationRewrite(reg, inv);
        assertThat(rw.rewritePattern(pq.getTupleExpr())).isZero();
        // Still just one Service, pointing at the original URL.
        final List<Service> services = collectServices(pq.getTupleExpr());
        assertThat(services).hasSize(1);
        assertThat(serviceRefUrl(services.get(0))).isEqualTo("http://other/query");
    }

    // ---------------------------------------------------------------------
    // Filter pushdown
    // ---------------------------------------------------------------------

    @Test
    public void filterPushedIntoSingleSourceService() {
        // Filter references only ?price which is bound in the products
        // source — the outer Filter should disappear, replaced by a
        // Filter inside the products Service body.
        final FederationRegistry reg = FederationRegistry.of(List.of(
                sparql("prod",    "http://prod/query",    "http://ex/label", "http://ex/price"),
                sparql("reviews", "http://reviews/query", "http://ex/rating")));
        final InvokeRegistry inv = new InvokeRegistry();
        final ParsedQuery pq = parse(
                "SELECT ?p ?l ?r WHERE {\n"
                        + "  ?p <http://ex/label>  ?l .\n"
                        + "  ?p <http://ex/price>  ?price .\n"
                        + "  ?p <http://ex/rating> ?r .\n"
                        + "  FILTER(?price < 50)\n"
                        + "}");
        final WfFederationRewrite rw = new WfFederationRewrite(reg, inv);
        rw.rewritePattern(pq.getTupleExpr());

        // Outer Filter is gone; the filter now lives inside a Service body.
        final List<Filter> outerFilters = new ArrayList<>();
        pq.getTupleExpr().visit(new AbstractQueryModelVisitor<RuntimeException>() {
            @Override public void meet(final Filter f) {
                // Any Filter under a Service is a pushed-down filter;
                // any Filter outside means the pushdown didn't fire.
                if (!isUnderService(f)) outerFilters.add(f);
                super.meet(f);
            }
            @Override protected void meetNode(final QueryModelNode n) { n.visitChildren(this); }
        });
        assertThat(outerFilters).as("outer FILTER should have pushed into a Service").isEmpty();
        // And the products Service now wraps a Filter.
        final List<Service> services = collectServices(pq.getTupleExpr());
        assertThat(services).hasSize(2);
    }

    private static boolean isUnderService(final QueryModelNode node) {
        QueryModelNode p = node.getParentNode();
        while (p != null) {
            if (p instanceof Service) return true;
            p = p.getParentNode();
        }
        return false;
    }

    @Test
    public void filterCrossSourceStaysAtOuterLevel() {
        // ?price lives in prod, ?rating in reviews → filter can't push down.
        final FederationRegistry reg = FederationRegistry.of(List.of(
                sparql("prod",    "http://prod/query",    "http://ex/price"),
                sparql("reviews", "http://reviews/query", "http://ex/rating")));
        final InvokeRegistry inv = new InvokeRegistry();
        final ParsedQuery pq = parse(
                "SELECT ?p ?price ?r WHERE {\n"
                        + "  ?p <http://ex/price>  ?price .\n"
                        + "  ?p <http://ex/rating> ?r .\n"
                        + "  FILTER(?price < ?r)\n"
                        + "}");
        final WfFederationRewrite rw = new WfFederationRewrite(reg, inv);
        rw.rewritePattern(pq.getTupleExpr());

        final List<Filter> outerFilters = new ArrayList<>();
        pq.getTupleExpr().visit(new AbstractQueryModelVisitor<RuntimeException>() {
            @Override public void meet(final Filter f) {
                if (!isUnderService(f)) outerFilters.add(f);
                super.meet(f);
            }
            @Override protected void meetNode(final QueryModelNode n) { n.visitChildren(this); }
        });
        assertThat(outerFilters)
                .as("cross-source filter must stay at outer level").hasSize(1);
    }

    // ---------------------------------------------------------------------
    // wf-* URL emission
    // ---------------------------------------------------------------------

    @Test
    public void wfSearchSourceEmitsWfSearchUrl() {
        final FederationRegistry reg = FederationRegistry.of(List.of(
                wfSearch("manuals", "http://ex/mentions")));
        final InvokeRegistry inv = new InvokeRegistry();
        final ParsedQuery pq = parse(
                "SELECT ?d ?m WHERE { ?d <http://ex/mentions> ?m }");
        final WfFederationRewrite rw = new WfFederationRewrite(reg, inv);
        assertThat(rw.rewritePattern(pq.getTupleExpr())).isEqualTo(1);
        final List<Service> services = collectServices(pq.getTupleExpr());
        assertThat(services).hasSize(1);
        assertThat(serviceRefUrl(services.get(0))).isEqualTo("wf-search:manuals");
    }

    @Test
    public void wfFetchSourceEmitsWfFetchUrl() {
        final FederationRegistry reg = FederationRegistry.of(List.of(
                wfFetch("cache", "http://ex/cached")));
        final InvokeRegistry inv = new InvokeRegistry();
        final ParsedQuery pq = parse(
                "SELECT ?k ?v WHERE { ?k <http://ex/cached> ?v }");
        final WfFederationRewrite rw = new WfFederationRewrite(reg, inv);
        assertThat(rw.rewritePattern(pq.getTupleExpr())).isEqualTo(1);
        final List<Service> services = collectServices(pq.getTupleExpr());
        assertThat(services).hasSize(1);
        assertThat(serviceRefUrl(services.get(0))).isEqualTo("wf-fetch:cache");
    }

    @Test
    public void wfDocumentSourceEmitsWfDocumentUrl() {
        final FederationRegistry reg = FederationRegistry.of(List.of(
                wfDoc("archive", "http://ex/archived")));
        final InvokeRegistry inv = new InvokeRegistry();
        final ParsedQuery pq = parse(
                "SELECT ?id ?body WHERE { ?id <http://ex/archived> ?body }");
        final WfFederationRewrite rw = new WfFederationRewrite(reg, inv);
        assertThat(rw.rewritePattern(pq.getTupleExpr())).isEqualTo(1);
        final List<Service> services = collectServices(pq.getTupleExpr());
        assertThat(services).hasSize(1);
        assertThat(serviceRefUrl(services.get(0))).isEqualTo("wf-document:archive");
    }

    @Test
    public void httpSparqlSourceUsesRawEndpoint() {
        // http-sparql is a plain SPARQL endpoint over HTTP — the raw
        // endpoint URL flows through unchanged.
        final FederationSource ext = new FederationSource(
                "external", SourceType.HTTP_SPARQL, "https://ext.example/query",
                List.of("http://ex/legacy"), OptionalInt.empty());
        final FederationRegistry reg = FederationRegistry.of(List.of(ext));
        final InvokeRegistry inv = new InvokeRegistry();
        final ParsedQuery pq = parse(
                "SELECT ?s ?o WHERE { ?s <http://ex/legacy> ?o }");
        final WfFederationRewrite rw = new WfFederationRewrite(reg, inv);
        assertThat(rw.rewritePattern(pq.getTupleExpr())).isEqualTo(1);
        final List<Service> services = collectServices(pq.getTupleExpr());
        assertThat(services).hasSize(1);
        assertThat(serviceRefUrl(services.get(0)))
                .isEqualTo("https://ext.example/query");
    }

    // ---------------------------------------------------------------------
    // Unregistered predicate mixed with registered — partial rewrite
    // ---------------------------------------------------------------------

    @Test
    public void mixedRegisteredAndUnregisteredPredicates() {
        // ?p is federated but ?u is a locally-stored predicate — the
        // federation pass wraps ?p's SP but leaves ?u's SP in the BGP.
        final FederationRegistry reg = FederationRegistry.of(List.of(
                sparql("prod", "http://prod/query", "http://ex/price")));
        final InvokeRegistry inv = new InvokeRegistry();
        final ParsedQuery pq = parse(
                "SELECT ?s ?p ?u WHERE {\n"
                        + "  ?s <http://ex/price> ?p .\n"
                        + "  ?s <http://ex/local> ?u .\n"
                        + "}");
        final WfFederationRewrite rw = new WfFederationRewrite(reg, inv);
        assertThat(rw.rewritePattern(pq.getTupleExpr())).isEqualTo(1);
        // Exactly one Service (for ?price); the local SP remains outside.
        assertThat(collectServices(pq.getTupleExpr())).hasSize(1);
        // Standalone StatementPatterns that are NOT part of a Service.
        final List<StatementPattern> outerSps = new ArrayList<>();
        pq.getTupleExpr().visit(new AbstractQueryModelVisitor<RuntimeException>() {
            @Override public void meet(final StatementPattern sp) {
                if (!isUnderService(sp)) outerSps.add(sp);
            }
            @Override public void meet(final Service s) { /* skip children */ }
            @Override protected void meetNode(final QueryModelNode n) { n.visitChildren(this); }
        });
        assertThat(outerSps).as("local SP stays in the outer BGP").hasSize(1);
    }

    // ---------------------------------------------------------------------
    // SILENT resolution (memo §08)
    // ---------------------------------------------------------------------

    private static FederationSource sparqlWithSilent(final String name,
                                                     final String endpoint,
                                                     final Optional<Boolean> silent,
                                                     final String... preds) {
        return new FederationSource(name, SourceType.SPARQL, endpoint,
                List.of(preds), OptionalInt.empty(), silent);
    }

    private static FederationSource wfSearchWithSilent(final String name,
                                                       final Optional<Boolean> silent,
                                                       final String... preds) {
        return new FederationSource(name, SourceType.WF_SEARCH,
                "wf-search:" + name, List.of(preds), OptionalInt.empty(),
                silent);
    }

    private static Service serviceForUrl(final TupleExpr expr, final String url) {
        for (Service s : collectServices(expr)) {
            if (url.equals(serviceRefUrl(s))) return s;
        }
        return null;
    }

    /**
     * Explicit {@code silent: true} on a SPARQL entry produces a
     * {@code Service} with {@code silent=true}.
     */
    @Test
    public void federationSourceSilentTrueEmitsSilentService() {
        final FederationRegistry reg = FederationRegistry.of(List.of(
                sparqlWithSilent("silent_products",
                        "http://silent-products/query",
                        Optional.of(true),
                        "http://ex/label")));
        final InvokeRegistry inv = new InvokeRegistry();
        final ParsedQuery pq = parse(
                "SELECT ?p ?l WHERE { ?p <http://ex/label> ?l }");
        final WfFederationRewrite rw = new WfFederationRewrite(reg, inv);
        assertThat(rw.rewritePattern(pq.getTupleExpr())).isEqualTo(1);

        final Service svc = serviceForUrl(pq.getTupleExpr(), "http://silent-products/query");
        assertThat(svc).as("products SERVICE present").isNotNull();
        assertThat(svc.isSilent())
                .as("explicit silent:true must emit SERVICE SILENT")
                .isTrue();
    }

    /**
     * Explicit {@code silent: false} on a SPARQL entry overrides the
     * per-type default (which would otherwise be {@code true}).
     */
    @Test
    public void federationSourceSilentFalseEmitsNonSilentService() {
        final FederationRegistry reg = FederationRegistry.of(List.of(
                sparqlWithSilent("loud_products",
                        "http://loud-products/query",
                        Optional.of(false),
                        "http://ex/label")));
        final InvokeRegistry inv = new InvokeRegistry();
        final ParsedQuery pq = parse(
                "SELECT ?p ?l WHERE { ?p <http://ex/label> ?l }");
        final WfFederationRewrite rw = new WfFederationRewrite(reg, inv);
        assertThat(rw.rewritePattern(pq.getTupleExpr())).isEqualTo(1);

        final Service svc = serviceForUrl(pq.getTupleExpr(), "http://loud-products/query");
        assertThat(svc).as("products SERVICE present").isNotNull();
        assertThat(svc.isSilent())
                .as("explicit silent:false must suppress SERVICE SILENT")
                .isFalse();
    }

    /**
     * Omitted {@code silent} on a SPARQL source falls back to the
     * type-based default (true — network endpoint, no probing in static
     * mode).
     */
    @Test
    public void federationSourceSilentDefaultsTrueForSparql() {
        final FederationRegistry reg = FederationRegistry.of(List.of(
                sparqlWithSilent("default_products",
                        "http://default-products/query",
                        Optional.empty(),
                        "http://ex/label")));
        final InvokeRegistry inv = new InvokeRegistry();
        final ParsedQuery pq = parse(
                "SELECT ?p ?l WHERE { ?p <http://ex/label> ?l }");
        final WfFederationRewrite rw = new WfFederationRewrite(reg, inv);
        assertThat(rw.rewritePattern(pq.getTupleExpr())).isEqualTo(1);

        final Service svc = serviceForUrl(pq.getTupleExpr(), "http://default-products/query");
        assertThat(svc).as("products SERVICE present").isNotNull();
        assertThat(svc.isSilent())
                .as("SPARQL sources default to SILENT when `silent` is omitted")
                .isTrue();
    }

    // ---------------------------------------------------------------------
     // serviceExpressionString — the on-wire SPARQL body
     //
     // RDF4J's `SPARQLFederatedService` reads the raw expression STRING
     // (not the algebra tree) when it renders the query it POSTs to the
     // remote endpoint (see `Service.initPreparedQueryString`). Emitting
     // "" here ships `SELECT ... WHERE {}` and every source returns
     // zero rows — the whole federation-empty-bindings symptom. These
     // tests keep the renderer honest and lock the regression down.
     // ---------------------------------------------------------------------

    /** BGP body populated on the Service.serviceExpressionString. */
    @Test
    public void serviceExpressionStringCarriesBgpBody() {
        final FederationRegistry reg = FederationRegistry.of(List.of(
                sparql("prod", "http://prod/query",
                        "http://ex/sku", "http://ex/price")));
        final InvokeRegistry inv = new InvokeRegistry();
        final ParsedQuery pq = parse(
                "SELECT ?s ?sku ?price WHERE {\n"
                        + "  ?s <http://ex/sku> ?sku .\n"
                        + "  ?s <http://ex/price> ?price .\n"
                        + "}");
        final WfFederationRewrite rw = new WfFederationRewrite(reg, inv);
        rw.rewritePattern(pq.getTupleExpr());

        final Service svc = serviceForUrl(pq.getTupleExpr(), "http://prod/query");
        assertThat(svc).isNotNull();
        final String body = svc.getServiceExpressionString();
        assertThat(body)
                .as("SERVICE body must be a non-empty SPARQL BGP so"
                        + " SPARQLFederatedService ships the pushed-down triples")
                .isNotEmpty()
                .contains("?s")
                .contains("<http://ex/sku>")
                .contains("<http://ex/price>");
    }

    /** Pushed-down FILTER survives to the wire, not just to the algebra. */
    @Test
    public void serviceExpressionStringIncludesPushedFilter() {
        final FederationRegistry reg = FederationRegistry.of(List.of(
                sparql("prod", "http://prod/query",
                        "http://ex/label", "http://ex/price")));
        final InvokeRegistry inv = new InvokeRegistry();
        final ParsedQuery pq = parse(
                "SELECT ?p ?l WHERE {\n"
                        + "  ?p <http://ex/label> ?l .\n"
                        + "  ?p <http://ex/price> ?price .\n"
                        + "  FILTER(?price < 50)\n"
                        + "}");
        final WfFederationRewrite rw = new WfFederationRewrite(reg, inv);
        rw.rewritePattern(pq.getTupleExpr());

        final Service svc = serviceForUrl(pq.getTupleExpr(), "http://prod/query");
        assertThat(svc).isNotNull();
        final String body = svc.getServiceExpressionString();
        // Filter pushdown must land in the shipped SPARQL, else the
        // remote receives a bare BGP and ships back every row.
        assertThat(body)
                .as("pushed-down FILTER must land in the on-wire body")
                .contains("FILTER")
                .contains("?price")
                .contains("50");
    }

    // ---------------------------------------------------------------------
    // buildService — invalid endpoint IRI guard
    //
    // A SPARQL source whose `endpoint` string is empty or non-absolute
    // (e.g. the `{{OXIGRAPH_URL}}` placeholder didn't get substituted
    // because the case declined to spin up a mock endpoint) used to trip
    // `VF.createIRI("")` with a cryptic `IllegalArgumentException: Not a
    // valid (absolute) IRI:` at `buildService`. Guard it with a specific
    // error that names the source so an operator can see which entry is
    // at fault. Regression driver: `cases/federation_heterogeneous.toml`.
    // ---------------------------------------------------------------------
    @Test
    public void emptySparqlEndpointThrowsWithSourceName() {
        final FederationRegistry reg = FederationRegistry.of(List.of(
                sparql("products", "", "http://ex/label")));
        final InvokeRegistry inv = new InvokeRegistry();
        final ParsedQuery pq = parse(
                "SELECT ?p ?l WHERE { ?p <http://ex/label> ?l }");
        final WfFederationRewrite rw = new WfFederationRewrite(reg, inv);
        try {
            rw.rewritePattern(pq.getTupleExpr());
            org.junit.Assert.fail("expected IllegalStateException for empty endpoint");
        } catch (IllegalStateException e) {
            assertThat(e.getMessage())
                    .as("error message must name the offending source")
                    .contains("products")
                    .contains("SPARQL");
        }
    }

    /**
     * Omitted {@code silent} on a wf-search source falls back to the
     * type-based default (false — substrate-local dispatch; failures
     * are bugs the operator needs to see, not network flaps to mask).
     */
    @Test
    public void federationSourceSilentDefaultsFalseForWfSearch() {
        final FederationRegistry reg = FederationRegistry.of(List.of(
                wfSearchWithSilent("manuals",
                        Optional.empty(),
                        "http://ex/has_manual")));
        final InvokeRegistry inv = new InvokeRegistry();
        final ParsedQuery pq = parse(
                "SELECT ?p ?m WHERE { ?p <http://ex/has_manual> ?m }");
        final WfFederationRewrite rw = new WfFederationRewrite(reg, inv);
        assertThat(rw.rewritePattern(pq.getTupleExpr())).isEqualTo(1);

        final Service svc = serviceForUrl(pq.getTupleExpr(), "wf-search:manuals");
        assertThat(svc).as("wf-search SERVICE present").isNotNull();
        assertThat(svc.isSilent())
                .as("wf-search sources default to non-SILENT when `silent` is omitted")
                .isFalse();
    }

    // ---------------------------------------------------------------------
    // v0.2 cost model — cardinality-based reorder
    // ---------------------------------------------------------------------

    private static FederationSource sparqlWithCard(final String name,
                                                   final String endpoint,
                                                   final OptionalLong cardHint,
                                                   final Map<String, Long> perPredHints,
                                                   final String... preds) {
        return new FederationSource(name, SourceType.SPARQL, endpoint,
                List.of(preds), OptionalInt.empty(), Optional.empty(),
                cardHint, perPredHints);
    }

    /** Return the URLs of every Service in visit (depth-first) order. */
    private static List<String> serviceRefUrlsInOrder(final TupleExpr expr) {
        final List<String> out = new ArrayList<>();
        for (Service s : collectServices(expr)) out.add(serviceRefUrl(s));
        return out;
    }

    /**
     * With cardinality hints, sources sort smallest-first regardless of
     * alphabetical order. {@code zebra} (100) beats {@code alpha} (5000).
     */
    @Test
    public void cardinalityReordersSmallerFirst() {
        final FederationRegistry reg = FederationRegistry.of(List.of(
                sparqlWithCard("alpha", "http://alpha/q",
                        OptionalLong.of(5000), Map.of(), "http://ex/a"),
                sparqlWithCard("zebra", "http://zebra/q",
                        OptionalLong.of(100), Map.of(), "http://ex/z")));
        final InvokeRegistry inv = new InvokeRegistry();
        final ParsedQuery pq = parse(
                "SELECT ?s ?a ?z WHERE {\n"
                        + "  ?s <http://ex/a> ?a .\n"
                        + "  ?s <http://ex/z> ?z .\n"
                        + "}");
        new WfFederationRewrite(reg, inv).rewritePattern(pq.getTupleExpr());
        final List<String> iris = serviceRefUrlsInOrder(pq.getTupleExpr());
        final int zebraPos = indexOfContaining(iris, "zebra");
        final int alphaPos = indexOfContaining(iris, "alpha");
        assertThat(zebraPos)
                .as("zebra (100 rows) must precede alpha (5000); iris=" + iris)
                .isLessThan(alphaPos);
    }

    /** Unknown-cardinality sources sort last (Long.MAX_VALUE default). */
    @Test
    public void unknownCardinalitySortsLast() {
        final FederationRegistry reg = FederationRegistry.of(List.of(
                sparqlWithCard("known", "http://known/q",
                        OptionalLong.of(200), Map.of(), "http://ex/k"),
                sparqlWithCard("unknown", "http://unknown/q",
                        OptionalLong.empty(), Map.of(), "http://ex/u")));
        final InvokeRegistry inv = new InvokeRegistry();
        final ParsedQuery pq = parse(
                "SELECT ?s ?k ?u WHERE {\n"
                        + "  ?s <http://ex/k> ?k .\n"
                        + "  ?s <http://ex/u> ?u .\n"
                        + "}");
        new WfFederationRewrite(reg, inv).rewritePattern(pq.getTupleExpr());
        final List<String> iris = serviceRefUrlsInOrder(pq.getTupleExpr());
        final int knownPos = indexOfContaining(iris, "known");
        final int unknownPos = indexOfContaining(iris, "unknown");
        assertThat(knownPos)
                .as("unknown-card source must sort last; iris=" + iris)
                .isLessThan(unknownPos);
    }

    /**
     * Per-predicate hints override source-wide hints for the matched
     * predicate. Source {@code a} has source-wide 5000 but per-predicate
     * override 10 for ex:cheap; source {@code b} has 100 flat. {@code a}
     * should win on ex:cheap.
     */
    @Test
    public void perPredicateCardinalityWins() {
        final FederationRegistry reg = FederationRegistry.of(List.of(
                sparqlWithCard("a", "http://a/q",
                        OptionalLong.of(5000),
                        Map.of("http://ex/cheap", 10L),
                        "http://ex/cheap"),
                sparqlWithCard("b", "http://b/q",
                        OptionalLong.of(100), Map.of(), "http://ex/mid")));
        final InvokeRegistry inv = new InvokeRegistry();
        final ParsedQuery pq = parse(
                "SELECT ?s ?c ?m WHERE {\n"
                        + "  ?s <http://ex/cheap> ?c .\n"
                        + "  ?s <http://ex/mid>   ?m .\n"
                        + "}");
        new WfFederationRewrite(reg, inv).rewritePattern(pq.getTupleExpr());
        final List<String> iris = serviceRefUrlsInOrder(pq.getTupleExpr());
        final int aPos = indexOfContaining(iris, "http://a");
        final int bPos = indexOfContaining(iris, "http://b");
        assertThat(aPos)
                .as("a (per-pred 10) must precede b (100); iris=" + iris)
                .isLessThan(bPos);
    }

    private static int indexOfContaining(final List<String> list, final String needle) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).contains(needle)) return i;
        }
        return -1;
    }
}

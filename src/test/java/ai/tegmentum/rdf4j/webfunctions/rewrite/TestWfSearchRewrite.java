package ai.tegmentum.rdf4j.webfunctions.rewrite;

import ai.tegmentum.rdf4j.webfunctions.rewrite.DocumentRegistry.DocumentIndex;
import ai.tegmentum.rdf4j.webfunctions.rewrite.DocumentRegistry.DocumentMode;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.query.algebra.QueryModelNode;
import org.eclipse.rdf4j.query.algebra.Service;
import org.eclipse.rdf4j.query.algebra.TupleExpr;
import org.eclipse.rdf4j.query.algebra.Var;
import org.eclipse.rdf4j.query.algebra.helpers.AbstractQueryModelVisitor;
import org.eclipse.rdf4j.query.parser.ParsedQuery;
import org.eclipse.rdf4j.query.parser.sparql.SPARQLParser;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Java port of the wf-search: URL-sugar rewrite tests. Mirrors the
 * cross-engine parity harness for
 * {@code oxigraph-wf/src/wf_search_rewrite.rs::tests}. Covers the
 * grammar-side unit tests (URL parser) plus the end-to-end
 * TupleExpr rewrite path.
 */
public class TestWfSearchRewrite {

    // ---------------------------------------------------------------------
    // Registry factories
    // ---------------------------------------------------------------------

    private static DocumentRegistry manualsRegistry() {
        final DocumentIndex ix = new DocumentIndex(
                "manuals",
                DocumentMode.MANAGED,
                "file:///opt/wf_document.wasm",
                "http://localhost:9308",
                "http://localhost:8080",
                "manuals",
                "docs",
                "manuals",
                "{}",
                OptionalInt.empty(),
                "all");
        return DocumentRegistry.of(List.of(ix));
    }

    // ---------------------------------------------------------------------
    // Small helpers
    // ---------------------------------------------------------------------

    private static ParsedQuery parse(final String sparql) {
        return new SPARQLParser().parseQuery(sparql, null);
    }

    private static boolean hasWfInvokeService(final TupleExpr expr) {
        final boolean[] hit = new boolean[1];
        expr.visit(new AbstractQueryModelVisitor<RuntimeException>() {
            @Override
            public void meet(final Service s) {
                final Var ref = s.getServiceRef();
                if (ref != null && ref.hasValue() && ref.getValue() instanceof IRI iri
                        && iri.stringValue().startsWith(InvokeRegistry.WF_INVOKE_SCHEME)) {
                    hit[0] = true;
                }
                super.meet(s);
            }
            @Override protected void meetNode(final QueryModelNode n) { n.visitChildren(this); }
        });
        return hit[0];
    }

    /** Was the original {@code wf-search:} service ref left in place? */
    private static boolean hasWfSearchService(final TupleExpr expr) {
        final boolean[] hit = new boolean[1];
        expr.visit(new AbstractQueryModelVisitor<RuntimeException>() {
            @Override
            public void meet(final Service s) {
                final Var ref = s.getServiceRef();
                if (ref != null && ref.hasValue() && ref.getValue() instanceof IRI iri
                        && iri.stringValue().startsWith(WfSearchRewrite.WF_SEARCH_SCHEME)) {
                    hit[0] = true;
                }
                super.meet(s);
            }
            @Override protected void meetNode(final QueryModelNode n) { n.visitChildren(this); }
        });
        return hit[0];
    }

    private static List<Service> collectServices(final TupleExpr expr) {
        final List<Service> out = new ArrayList<>();
        expr.visit(new AbstractQueryModelVisitor<RuntimeException>() {
            @Override public void meet(final Service s) { out.add(s); super.meet(s); }
            @Override protected void meetNode(final QueryModelNode n) { n.visitChildren(this); }
        });
        return out;
    }

    private static InvokeSpec takeFirstInvoke(final InvokeRegistry inv) {
        final InvokeSpec s = inv.take(0L);
        assertThat(s).as("expected an InvokeSpec at id 0").isNotNull();
        return s;
    }

    private static String stringLit(final InvokeSpec spec, final int i) {
        return ((Literal) spec.args().get(i)).getLabel();
    }
    private static int intLit(final InvokeSpec spec, final int i) {
        return ((Literal) spec.args().get(i)).intValue();
    }
    private static String queryArg(final InvokeSpec spec) { return stringLit(spec, 3); }
    // args = [searchBackend, storageBackend, index, query, optsJson] per
    // the wf_document `search` export WIT signature. `limit` lives inside
    // optsJson (search-opts.limit: option<u32>).
    private static String optsArg(final InvokeSpec spec) { return stringLit(spec, 4); }

    // ---------------------------------------------------------------------
    // URL parser tests
    // ---------------------------------------------------------------------

    @Test
    public void parsesBareName() {
        final WfSearchRewrite.ParsedUrl p = WfSearchRewrite.parseUrl("wf-search:manuals");
        assertThat(p).isNotNull();
        assertThat(p.name).isEqualTo("manuals");
        assertThat(p.timeSpec).isNull();
        assertThat(p.opts).isEmpty();
    }

    @Test
    public void parsesTimeIso() {
        final WfSearchRewrite.ParsedUrl p = WfSearchRewrite.parseUrl(
                "wf-search:manuals@2026-01-01T00:00:00Z");
        assertThat(p).isNotNull();
        assertThat(p.name).isEqualTo("manuals");
        assertThat(p.timeSpec).isEqualTo("2026-01-01T00:00:00Z");
        assertThat(p.opts).isEmpty();
    }

    @Test
    public void parsesTimeRev() {
        final WfSearchRewrite.ParsedUrl p = WfSearchRewrite.parseUrl("wf-search:manuals@rev17");
        assertThat(p).isNotNull();
        assertThat(p.name).isEqualTo("manuals");
        assertThat(p.timeSpec).isEqualTo("rev17");
        assertThat(p.opts).isEmpty();
    }

    @Test
    public void parsesOpts() {
        final WfSearchRewrite.ParsedUrl p = WfSearchRewrite.parseUrl(
                "wf-search:manuals?highlight=true&lang=en&limit=50");
        assertThat(p).isNotNull();
        assertThat(p.name).isEqualTo("manuals");
        assertThat(p.timeSpec).isNull();
        assertThat(p.opts)
                .containsEntry("highlight", "true")
                .containsEntry("lang", "en")
                .containsEntry("limit", "50");
    }

    @Test
    public void parsesTimeAndOpts() {
        final WfSearchRewrite.ParsedUrl p = WfSearchRewrite.parseUrl(
                "wf-search:manuals@2026-01-01?highlight=true&limit=5");
        assertThat(p).isNotNull();
        assertThat(p.name).isEqualTo("manuals");
        assertThat(p.timeSpec).isEqualTo("2026-01-01");
        assertThat(p.opts)
                .containsEntry("highlight", "true")
                .containsEntry("limit", "5");
    }

    @Test
    public void rejectsMissingName() {
        // Scheme-only URL: no name segment at all.
        assertThat(WfSearchRewrite.parseUrl("wf-search:")).isNull();
        // Time-spec but no name.
        assertThat(WfSearchRewrite.parseUrl("wf-search:@2026-01-01")).isNull();
        // Opts but no name.
        assertThat(WfSearchRewrite.parseUrl("wf-search:?highlight=true")).isNull();
    }

    @Test
    public void parsesRangeOpts() {
        // v1.3: after / before pass through as verbatim opt strings when
        // no @<time-spec> is present.
        final WfSearchRewrite.ParsedUrl p = WfSearchRewrite.parseUrl(
                "wf-search:manuals?after=2026-01-01&before=2026-06-01");
        assertThat(p).isNotNull();
        assertThat(p.name).isEqualTo("manuals");
        assertThat(p.timeSpec).isNull();
        assertThat(p.opts)
                .containsEntry("after", "2026-01-01")
                .containsEntry("before", "2026-06-01");
    }

    @Test
    public void rejectsAtTimeWithRange() {
        // @<time-spec> and after/before are mutually exclusive at parse
        // time — the parser returns the null sentinel so the outer
        // rewrite pass leaves the SERVICE untouched.
        assertThat(WfSearchRewrite.parseUrl(
                "wf-search:manuals@2026-01-01?after=2025-01-01"))
                .isNull();
        assertThat(WfSearchRewrite.parseUrl(
                "wf-search:manuals@rev17?before=2026-06-01"))
                .isNull();
        assertThat(WfSearchRewrite.parseUrl(
                "wf-search:manuals@2026-01-01?after=2025-01-01&before=2026-06-01"))
                .isNull();
    }

    // ---------------------------------------------------------------------
    // End-to-end rewrite tests
    // ---------------------------------------------------------------------

    @Test
    public void rewritesBareService() {
        final DocumentRegistry reg = manualsRegistry();
        final InvokeRegistry inv = new InvokeRegistry();
        final ParsedQuery pq = parse(""
                + "PREFIX wf: <http://tegmentum.ai/ns/webfunction/>\n"
                + "SELECT ?doc WHERE {\n"
                + "  SERVICE <wf-search:manuals> {\n"
                + "    ?_ wf:query \"waterproof\" ; wf:doc ?doc\n"
                + "  }\n"
                + "}");
        final WfSearchRewrite rw = new WfSearchRewrite(reg, inv);
        assertThat(rw.rewritePattern(pq.getTupleExpr())).isEqualTo(1);
        assertThat(hasWfInvokeService(pq.getTupleExpr())).isTrue();
        assertThat(hasWfSearchService(pq.getTupleExpr())).isFalse();

        final InvokeSpec spec = takeFirstInvoke(inv);
        // arg order: searchBackend, storageBackend, index, query, optsJson.
        // limit lives inside optsJson per the WIT record shape.
        assertThat(stringLit(spec, 0)).isEqualTo("http://localhost:9308");
        assertThat(stringLit(spec, 1)).isEqualTo("http://localhost:8080");
        assertThat(stringLit(spec, 2)).isEqualTo("manuals");
        assertThat(queryArg(spec)).isEqualTo("waterproof");
        // Bare URL — no user opts — opts_json carries the WIT-required
        // `fields:[]`, `highlight:false`, and `include-body:false`
        // defaults plus the injected `limit` fallback. StringBuilder
        // emission order is `fields`, `highlight`, `limit`,
        // `include-body`, so the exact JSON is stable.
        assertThat(optsArg(spec))
                .isEqualTo("{\"fields\":[],\"highlight\":false,\"limit\":10,\"include-body\":false}");
        assertThat(spec.entryPoint()).isEqualTo("search");
        assertThat(spec.wasmUrl()).isEqualTo("file:///opt/wf_document.wasm");
    }

    @Test
    public void rewritesWithTime() {
        final DocumentRegistry reg = manualsRegistry();
        final InvokeRegistry inv = new InvokeRegistry();
        final ParsedQuery pq = parse(""
                + "PREFIX wf: <http://tegmentum.ai/ns/webfunction/>\n"
                + "SELECT ?doc WHERE {\n"
                + "  SERVICE <wf-search:manuals@2026-01-01T00:00:00Z?include_body=true> {\n"
                + "    ?_ wf:query \"waterproof\" ; wf:doc ?doc\n"
                + "  }\n"
                + "}");
        final WfSearchRewrite rw = new WfSearchRewrite(reg, inv);
        assertThat(rw.rewritePattern(pq.getTupleExpr())).isEqualTo(1);
        assertThat(hasWfInvokeService(pq.getTupleExpr())).isTrue();

        final InvokeSpec spec = takeFirstInvoke(inv);
        assertThat(optsArg(spec))
                .contains("\"at_time\":\"2026-01-01T00:00:00Z\"")
                // URL `?include_body=true` (snake) must land in
                // opts_json as the WIT record's kebab-case field
                // name `include-body` — otherwise the marshaller
                // errors with "record missing required field
                // `include-body`" before the guest is invoked.
                .contains("\"include-body\":true")
                .doesNotContain("\"include_body\":");
    }

    @Test
    public void rewritesWithRangeService() {
        // v1.3: after / before land in opts_json as string pass-throughs
        // and no at_time / at_rev is baked in.
        final DocumentRegistry reg = manualsRegistry();
        final InvokeRegistry inv = new InvokeRegistry();
        final ParsedQuery pq = parse(""
                + "PREFIX wf: <http://tegmentum.ai/ns/webfunction/>\n"
                + "SELECT ?doc WHERE {\n"
                + "  SERVICE <wf-search:manuals?after=2026-01-01&before=2026-06-01> {\n"
                + "    ?_ wf:query \"waterproof\" ; wf:doc ?doc\n"
                + "  }\n"
                + "}");
        final WfSearchRewrite rw = new WfSearchRewrite(reg, inv);
        assertThat(rw.rewritePattern(pq.getTupleExpr())).isEqualTo(1);
        assertThat(hasWfInvokeService(pq.getTupleExpr())).isTrue();

        final InvokeSpec spec = takeFirstInvoke(inv);
        assertThat(optsArg(spec))
                .contains("\"after\":\"2026-01-01\"")
                .contains("\"before\":\"2026-06-01\"")
                .doesNotContain("at_time")
                .doesNotContain("at_rev");
    }

    @Test
    public void rewritesWithRev() {
        final DocumentRegistry reg = manualsRegistry();
        final InvokeRegistry inv = new InvokeRegistry();
        final ParsedQuery pq = parse(""
                + "PREFIX wf: <http://tegmentum.ai/ns/webfunction/>\n"
                + "SELECT ?doc WHERE {\n"
                + "  SERVICE <wf-search:manuals@rev17> {\n"
                + "    ?_ wf:query \"waterproof\" ; wf:doc ?doc\n"
                + "  }\n"
                + "}");
        final WfSearchRewrite rw = new WfSearchRewrite(reg, inv);
        assertThat(rw.rewritePattern(pq.getTupleExpr())).isEqualTo(1);
        assertThat(hasWfInvokeService(pq.getTupleExpr())).isTrue();

        final InvokeSpec spec = takeFirstInvoke(inv);
        // Emission order matches buildOptsJson's insertion: `at_rev`
        // first (from the timeSpec branch), then the WIT-required
        // defaults `fields:[]` / `highlight:false`, then the injected
        // `limit` fallback, then the WIT-required
        // `include-body:false` default (wave-6 addition).
        assertThat(optsArg(spec))
                .isEqualTo("{\"at_rev\":17,\"fields\":[],\"highlight\":false,\"limit\":10,\"include-body\":false}");
    }

    // ---------------------------------------------------------------------
    // Skip / no-op cases
    // ---------------------------------------------------------------------

    @Test
    public void unregisteredNameSkips() {
        // Registry knows "manuals"; query names "unknown".
        final DocumentRegistry reg = manualsRegistry();
        final InvokeRegistry inv = new InvokeRegistry();
        final ParsedQuery pq = parse(""
                + "PREFIX wf: <http://tegmentum.ai/ns/webfunction/>\n"
                + "SELECT ?doc WHERE {\n"
                + "  SERVICE <wf-search:unknown> {\n"
                + "    ?_ wf:query \"waterproof\" ; wf:doc ?doc\n"
                + "  }\n"
                + "}");
        final WfSearchRewrite rw = new WfSearchRewrite(reg, inv);
        assertThat(rw.rewritePattern(pq.getTupleExpr())).isZero();
        assertThat(hasWfInvokeService(pq.getTupleExpr())).isFalse();
        assertThat(hasWfSearchService(pq.getTupleExpr())).isTrue();
    }

    @Test
    public void missingWfQuerySkips() {
        // Registered name, but no wf:query triple in the SERVICE body.
        final DocumentRegistry reg = manualsRegistry();
        final InvokeRegistry inv = new InvokeRegistry();
        final ParsedQuery pq = parse(""
                + "PREFIX wf: <http://tegmentum.ai/ns/webfunction/>\n"
                + "SELECT ?doc WHERE {\n"
                + "  SERVICE <wf-search:manuals> {\n"
                + "    ?_ wf:doc ?doc\n"
                + "  }\n"
                + "}");
        final WfSearchRewrite rw = new WfSearchRewrite(reg, inv);
        assertThat(rw.rewritePattern(pq.getTupleExpr())).isZero();
        assertThat(hasWfInvokeService(pq.getTupleExpr())).isFalse();
        assertThat(hasWfSearchService(pq.getTupleExpr())).isTrue();
    }

    // ---------------------------------------------------------------------
    // Memo §10 smart-set: wf:snippet → highlight=true
    // ---------------------------------------------------------------------

    @Test
    public void smartSetsHighlightWhenBodyProjectsSnippet() {
        // SERVICE body binds `wf:snippet ?snippet`; URL doesn't touch
        // highlight → memo §10 smart-set kicks in and the emitted opts
        // JSON carries `"highlight":true`.
        final DocumentRegistry reg = manualsRegistry();
        final InvokeRegistry inv = new InvokeRegistry();
        final ParsedQuery pq = parse(""
                + "PREFIX wf: <http://tegmentum.ai/ns/webfunction/>\n"
                + "SELECT ?doc ?snippet WHERE {\n"
                + "  SERVICE <wf-search:manuals> {\n"
                + "    ?_ wf:query \"waterproof\" ; wf:doc ?doc ; wf:snippet ?snippet\n"
                + "  }\n"
                + "}");
        final WfSearchRewrite rw = new WfSearchRewrite(reg, inv);
        assertThat(rw.rewritePattern(pq.getTupleExpr())).isEqualTo(1);
        final InvokeSpec spec = takeFirstInvoke(inv);
        assertThat(optsArg(spec))
                .as("wf:snippet in body must smart-set highlight=true")
                .contains("\"highlight\":true");
    }

    @Test
    public void noSmartSetWhenBodyOmitsSnippet() {
        // No wf:snippet in the body → substrate emits the WIT-required
        // `highlight:false` default. Pre-v1.3 behavior was to omit the
        // key entirely and let the guest pick a default; the WIT now
        // declares `highlight: bool` as non-option, so the substrate
        // must always emit it.
        final DocumentRegistry reg = manualsRegistry();
        final InvokeRegistry inv = new InvokeRegistry();
        final ParsedQuery pq = parse(""
                + "PREFIX wf: <http://tegmentum.ai/ns/webfunction/>\n"
                + "SELECT ?doc WHERE {\n"
                + "  SERVICE <wf-search:manuals> {\n"
                + "    ?_ wf:query \"waterproof\" ; wf:doc ?doc\n"
                + "  }\n"
                + "}");
        final WfSearchRewrite rw = new WfSearchRewrite(reg, inv);
        assertThat(rw.rewritePattern(pq.getTupleExpr())).isEqualTo(1);
        final InvokeSpec spec = takeFirstInvoke(inv);
        assertThat(optsArg(spec))
                .as("no wf:snippet must emit WIT-required highlight=false default")
                .contains("\"highlight\":false")
                .doesNotContain("\"highlight\":true");
    }

    @Test
    public void optsJsonIncludesRequiredWitFields() {
        // The wf_document WIT `search-opts` record declares three
        // non-optional bool/list fields — `fields: list<string>`,
        // `highlight: bool`, and `include-body: bool` (memo §04,
        // `wf-document.wit` v1.3). The substrate coercer errors out on
        // any missing required field before the dispatch reaches the
        // guest ("arg 4 of `search`: record missing required field
        // `<name>`"), so every wf_document opts_json emission must
        // include all three. Parallel guard to the wf_fulltext test of
        // the same name.
        final DocumentRegistry reg = manualsRegistry();
        final InvokeRegistry inv = new InvokeRegistry();
        final ParsedQuery pq = parse(""
                + "PREFIX wf: <http://tegmentum.ai/ns/webfunction/>\n"
                + "SELECT ?doc WHERE {\n"
                + "  SERVICE <wf-search:manuals> {\n"
                + "    ?_ wf:query \"waterproof\" ; wf:doc ?doc\n"
                + "  }\n"
                + "}");
        final WfSearchRewrite rw = new WfSearchRewrite(reg, inv);
        assertThat(rw.rewritePattern(pq.getTupleExpr())).isEqualTo(1);
        final InvokeSpec spec = takeFirstInvoke(inv);
        assertThat(optsArg(spec))
                .as("opts_json must include the WIT-required `fields`, `highlight`, and `include-body` defaults")
                .contains("\"fields\":[]")
                .contains("\"highlight\":false")
                .contains("\"include-body\":false");
    }

    @Test
    public void optsJsonSnakeIncludeBodyUrlBecomesKebabWitKey() {
        // URL query params are conventionally snake_case
        // (`?include_body=true`) but the WIT `search-opts` record
        // declares fields kebab-case (`include-body`). The emitter
        // must translate on the way in — otherwise the marshaller
        // fails with "record missing required field `include-body`".
        final DocumentRegistry reg = manualsRegistry();
        final InvokeRegistry inv = new InvokeRegistry();
        final ParsedQuery pq = parse(""
                + "PREFIX wf: <http://tegmentum.ai/ns/webfunction/>\n"
                + "SELECT ?doc WHERE {\n"
                + "  SERVICE <wf-search:manuals?include_body=true> {\n"
                + "    ?_ wf:query \"waterproof\" ; wf:doc ?doc\n"
                + "  }\n"
                + "}");
        final WfSearchRewrite rw = new WfSearchRewrite(reg, inv);
        assertThat(rw.rewritePattern(pq.getTupleExpr())).isEqualTo(1);
        final InvokeSpec spec = takeFirstInvoke(inv);
        assertThat(optsArg(spec))
                .as("URL ?include_body=true must emit kebab-case JSON key")
                .contains("\"include-body\":true")
                .doesNotContain("\"include_body\":");
    }

    @Test
    public void optsJsonUrlFieldsPlaceholder() {
        // Guard on existing behavior: `?fields=` isn't a whitelisted
        // URL opt, so the emitted opts_json falls back to the
        // WIT-required default `fields:[]`. Documents parity with
        // the sibling Oxigraph / QLever / Jena tests.
        final DocumentRegistry reg = manualsRegistry();
        final InvokeRegistry inv = new InvokeRegistry();
        final ParsedQuery pq = parse(""
                + "PREFIX wf: <http://tegmentum.ai/ns/webfunction/>\n"
                + "SELECT ?doc WHERE {\n"
                + "  SERVICE <wf-search:manuals> {\n"
                + "    ?_ wf:query \"waterproof\" ; wf:doc ?doc\n"
                + "  }\n"
                + "}");
        final WfSearchRewrite rw = new WfSearchRewrite(reg, inv);
        assertThat(rw.rewritePattern(pq.getTupleExpr())).isEqualTo(1);
        final InvokeSpec spec = takeFirstInvoke(inv);
        assertThat(optsArg(spec)).contains("\"fields\":[]");
    }

    // ---------------------------------------------------------------------
    // URL-parameter query sugar (federation_heterogeneous shape)
    // ---------------------------------------------------------------------

    @Test
    public void foldsViaUrlQueryOptWhenBodyMissingWfQuery() {
        // federation_heterogeneous.toml shape: SERVICE body has no
        // `wf:query "…"` triple; the search string travels in the URL
        // as `?query=<term>`. The rewrite pass must fall back to the
        // URL opt so the SERVICE folds into `wf-invoke:<hex>` instead
        // of surviving to the default dispatcher. Mirrors Jena's
        // `urlQueryParamFoldsWithoutBodyTriple`.
        final DocumentRegistry reg = manualsRegistry();
        final InvokeRegistry inv = new InvokeRegistry();
        final ParsedQuery pq = parse(""
                + "PREFIX wf: <http://tegmentum.ai/ns/webfunction/>\n"
                + "SELECT ?doc WHERE {\n"
                + "  SERVICE <wf-search:manuals?query=waterproof> {\n"
                + "    ?_ wf:doc ?doc\n"
                + "  }\n"
                + "}");
        final WfSearchRewrite rw = new WfSearchRewrite(reg, inv);
        assertThat(rw.rewritePattern(pq.getTupleExpr()))
                .as("URL ?query= must fold without a wf:query body triple")
                .isEqualTo(1);
        assertThat(hasWfInvokeService(pq.getTupleExpr())).isTrue();

        final InvokeSpec spec = takeFirstInvoke(inv);
        assertThat(queryArg(spec))
                .as("positional arg 3 is the URL query opt")
                .isEqualTo("waterproof");
        // `query` must NOT leak into opts_json — the guest reads the
        // query from the positional arg, not from the opts record.
        assertThat(optsArg(spec))
                .as("query opt must be consumed positionally, not leak into opts_json")
                .doesNotContain("\"query\"");
    }

    @Test
    public void bodyWfQueryWinsOverUrlQueryOpt() {
        // When both a body `wf:query "…"` triple AND a URL `?query=<term>`
        // opt are present, the body-triple form wins — it is closer to
        // the caller's intent than a URL string decorated by federation
        // config. Mirrors Jena's `bodyWfQueryWinsOverUrlQueryParam`.
        final DocumentRegistry reg = manualsRegistry();
        final InvokeRegistry inv = new InvokeRegistry();
        final ParsedQuery pq = parse(""
                + "PREFIX wf: <http://tegmentum.ai/ns/webfunction/>\n"
                + "SELECT ?doc WHERE {\n"
                + "  SERVICE <wf-search:manuals?query=urlterm> {\n"
                + "    ?_ wf:query \"bodyterm\" ; wf:doc ?doc\n"
                + "  }\n"
                + "}");
        final WfSearchRewrite rw = new WfSearchRewrite(reg, inv);
        assertThat(rw.rewritePattern(pq.getTupleExpr())).isEqualTo(1);

        final InvokeSpec spec = takeFirstInvoke(inv);
        assertThat(queryArg(spec))
                .as("body wf:query literal must win over URL ?query= opt")
                .isEqualTo("bodyterm");
    }

    @Test
    public void urlHighlightFalseWinsOverSnippetSmartSet() {
        // URL explicitly says highlight=false; body still projects
        // wf:snippet. URL wins per memo §10.
        final DocumentRegistry reg = manualsRegistry();
        final InvokeRegistry inv = new InvokeRegistry();
        final ParsedQuery pq = parse(""
                + "PREFIX wf: <http://tegmentum.ai/ns/webfunction/>\n"
                + "SELECT ?doc ?snippet WHERE {\n"
                + "  SERVICE <wf-search:manuals?highlight=false> {\n"
                + "    ?_ wf:query \"waterproof\" ; wf:doc ?doc ; wf:snippet ?snippet\n"
                + "  }\n"
                + "}");
        final WfSearchRewrite rw = new WfSearchRewrite(reg, inv);
        assertThat(rw.rewritePattern(pq.getTupleExpr())).isEqualTo(1);
        final InvokeSpec spec = takeFirstInvoke(inv);
        assertThat(optsArg(spec))
                .as("URL ?highlight=false must win over smart-set")
                .contains("\"highlight\":false")
                .doesNotContain("\"highlight\":true");
    }

    @Test
    public void emptyRegistryNoop() {
        final DocumentRegistry reg = DocumentRegistry.empty();
        final InvokeRegistry inv = new InvokeRegistry();
        final ParsedQuery pq = parse(""
                + "PREFIX wf: <http://tegmentum.ai/ns/webfunction/>\n"
                + "SELECT ?doc WHERE {\n"
                + "  SERVICE <wf-search:manuals> {\n"
                + "    ?_ wf:query \"waterproof\" ; wf:doc ?doc\n"
                + "  }\n"
                + "}");
        final WfSearchRewrite rw = new WfSearchRewrite(reg, inv);
        assertThat(rw.rewritePattern(pq.getTupleExpr())).isZero();
        assertThat(hasWfInvokeService(pq.getTupleExpr())).isFalse();
        assertThat(hasWfSearchService(pq.getTupleExpr())).isTrue();
        // Sanity: the SERVICE clause survives untouched.
        assertThat(collectServices(pq.getTupleExpr())).hasSize(1);
    }

    // ---------------------------------------------------------------------
    // FederationRegistry fallback (wave-3 federation_heterogeneous)
    // ---------------------------------------------------------------------

    /**
     * Federation registry with a single wf-search source named
     * `manuals-search` and a self-referential endpoint — mirrors the
     * shape {@code federation_heterogeneous.toml} seeds via
     * {@code --federation-config}.
     */
    private static FederationRegistry manualsFederationRegistry() {
        return FederationRegistry.of(List.of(new FederationRegistry.FederationSource(
                "manuals-search",
                FederationRegistry.SourceType.WF_SEARCH,
                "wf-search:manuals-search",
                List.of(),
                OptionalInt.empty())));
    }

    /**
     * Federation registry whose wf-search source declares a real HTTP
     * endpoint — exercises the passthrough branch of
     * {@code federationBackendEndpoint}.
     */
    private static FederationRegistry manualsFederationRegistryHttp() {
        return FederationRegistry.of(List.of(new FederationRegistry.FederationSource(
                "manuals-search",
                FederationRegistry.SourceType.WF_SEARCH,
                "http://manticore.internal:9309",
                List.of(),
                OptionalInt.empty())));
    }

    @Test
    public void foldsViaFederationRegistryWhenOthersMiss() {
        // Neither DocumentRegistry nor FulltextRegistry knows the name;
        // only FederationRegistry has a wf-search entry. The pass must
        // still fold using a synthesized wf_fulltext InvokeSpec.
        final DocumentRegistry docReg = DocumentRegistry.empty();
        final FulltextRegistry ftReg = FulltextRegistry.empty();
        final FederationRegistry fedReg = manualsFederationRegistry();
        final InvokeRegistry inv = new InvokeRegistry();
        final ParsedQuery pq = parse(""
                + "PREFIX wf: <http://tegmentum.ai/ns/webfunction/>\n"
                + "SELECT ?m ?snippet WHERE {\n"
                + "  SERVICE <wf-search:manuals-search> {\n"
                + "    ?_ wf:query \"waterproof\" ; wf:doc ?m ; wf:snippet ?snippet\n"
                + "  }\n"
                + "}");
        final WfSearchRewrite rw = new WfSearchRewrite(docReg, ftReg, fedReg, inv);
        assertThat(rw.rewritePattern(pq.getTupleExpr()))
                .as("federation fallback must fold the SERVICE")
                .isEqualTo(1);
        assertThat(hasWfInvokeService(pq.getTupleExpr())).isTrue();
        assertThat(hasWfSearchService(pq.getTupleExpr())).isFalse();

        final InvokeSpec spec = takeFirstInvoke(inv);
        assertThat(spec.entryPoint()).isEqualTo("search");
        // wf_fulltext ABI: [backend_endpoint, index, query, opts_json]
        assertThat(spec.args()).hasSize(4);
        assertThat(((Literal) spec.args().get(1)).getLabel())
                .as("index should be entry name")
                .isEqualTo("manuals-search");
        assertThat(((Literal) spec.args().get(2)).getLabel()).isEqualTo("waterproof");
        assertThat(((Literal) spec.args().get(3)).getLabel())
                .as("wf:snippet in body must smart-set highlight=true")
                .contains("\"highlight\":true");
    }

    @Test
    public void foldsViaFederationRegistryHonoursHttpEndpoint() {
        // A federation wf-search entry with a real HTTP endpoint must
        // pass through as the wf_fulltext backend_endpoint arg
        // verbatim, not fall through to the Manticore default.
        final FederationRegistry fedReg = manualsFederationRegistryHttp();
        final InvokeRegistry inv = new InvokeRegistry();
        final ParsedQuery pq = parse(""
                + "PREFIX wf: <http://tegmentum.ai/ns/webfunction/>\n"
                + "SELECT ?m WHERE {\n"
                + "  SERVICE <wf-search:manuals-search> {\n"
                + "    ?_ wf:query \"waterproof\" ; wf:doc ?m\n"
                + "  }\n"
                + "}");
        final WfSearchRewrite rw = new WfSearchRewrite(
                DocumentRegistry.empty(), FulltextRegistry.empty(), fedReg, inv);
        assertThat(rw.rewritePattern(pq.getTupleExpr())).isEqualTo(1);

        final InvokeSpec spec = takeFirstInvoke(inv);
        assertThat(((Literal) spec.args().get(0)).getLabel())
                .as("HTTP-shaped federation endpoint must pass through verbatim")
                .isEqualTo("http://manticore.internal:9309");
    }

    @Test
    public void documentRegistryWinsOverFederation() {
        // Precedence: Document > Fulltext > Federation. The document
        // registry's wf_document ABI wins over a federation
        // fallback's synthesized wf_fulltext shape.
        final DocumentRegistry docReg = manualsRegistry();
        final FederationRegistry fedReg = FederationRegistry.of(List.of(
                new FederationRegistry.FederationSource(
                        "manuals",
                        FederationRegistry.SourceType.WF_SEARCH,
                        "http://manticore.internal:9309",
                        List.of(),
                        OptionalInt.empty())));
        final InvokeRegistry inv = new InvokeRegistry();
        final ParsedQuery pq = parse(""
                + "PREFIX wf: <http://tegmentum.ai/ns/webfunction/>\n"
                + "SELECT ?doc WHERE {\n"
                + "  SERVICE <wf-search:manuals> {\n"
                + "    ?_ wf:query \"waterproof\" ; wf:doc ?doc\n"
                + "  }\n"
                + "}");
        final WfSearchRewrite rw = new WfSearchRewrite(
                docReg, FulltextRegistry.empty(), fedReg, inv);
        assertThat(rw.rewritePattern(pq.getTupleExpr())).isEqualTo(1);

        final InvokeSpec spec = takeFirstInvoke(inv);
        assertThat(spec.wasmUrl())
                .as("DocumentRegistry entry wins over the federation fallback")
                .isEqualTo("file:///opt/wf_document.wasm");
        assertThat(spec.args())
                .as("wf_document ABI expected, not wf_fulltext")
                .hasSize(5);
    }

    @Test
    public void federationWrongTypeIsSkipped() {
        // A non-wf-search federation entry must not fold as a search
        // source. The SERVICE is left alone.
        final FederationRegistry fedReg = FederationRegistry.of(List.of(
                new FederationRegistry.FederationSource(
                        "manuals-search",
                        FederationRegistry.SourceType.WF_FETCH,
                        "wf-fetch:manuals-search",
                        List.of(),
                        OptionalInt.empty())));
        final InvokeRegistry inv = new InvokeRegistry();
        final ParsedQuery pq = parse(""
                + "PREFIX wf: <http://tegmentum.ai/ns/webfunction/>\n"
                + "SELECT ?m WHERE {\n"
                + "  SERVICE <wf-search:manuals-search> {\n"
                + "    ?_ wf:query \"waterproof\" ; wf:doc ?m\n"
                + "  }\n"
                + "}");
        final WfSearchRewrite rw = new WfSearchRewrite(
                DocumentRegistry.empty(), FulltextRegistry.empty(), fedReg, inv);
        assertThat(rw.rewritePattern(pq.getTupleExpr()))
                .as("wf-fetch federation source must not fold as wf-search")
                .isZero();
        assertThat(hasWfSearchService(pq.getTupleExpr())).isTrue();
    }
}

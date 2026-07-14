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
    private static String optsArg(final InvokeSpec spec) { return stringLit(spec, 5); }

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
        // arg order: searchBackend, storageBackend, index, query, limit, optsJson
        assertThat(stringLit(spec, 0)).isEqualTo("http://localhost:9308");
        assertThat(stringLit(spec, 1)).isEqualTo("http://localhost:8080");
        assertThat(stringLit(spec, 2)).isEqualTo("manuals");
        assertThat(queryArg(spec)).isEqualTo("waterproof");
        assertThat(intLit(spec, 4)).isEqualTo(10); // DEFAULT_LIMIT
        assertThat(optsArg(spec)).isEqualTo("{}");
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
                .contains("\"include_body\":true");
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
        assertThat(optsArg(spec)).isEqualTo("{\"at_rev\":17}");
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
        // No wf:snippet in the body → highlight key stays absent.
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
                .as("no wf:snippet must leave highlight unset")
                .doesNotContain("\"highlight\"");
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
}

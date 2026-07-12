package ai.tegmentum.rdf4j.webfunctions.rewrite;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.query.algebra.Filter;
import org.eclipse.rdf4j.query.algebra.FunctionCall;
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
 * Java port of the fulltext-rewrite unit tests. Mirrors
 * {@code oxigraph-wf/src/fulltext_rewrite.rs::tests}. Every fold-safe /
 * fold-unsafe shape from memo §06 has a matching Rust test; each one is
 * ported verbatim below.
 */
public class TestFulltextRewrite {

    // ---------------------------------------------------------------------
    // Registry factories
    // ---------------------------------------------------------------------

    private static FulltextRegistry productsRegistryWord() {
        final FulltextRegistry.FulltextIndex ix = new FulltextRegistry.FulltextIndex(
                "products",
                FulltextRegistry.FulltextMode.LITERAL_INDEX,
                "file:///opt/wf_fulltext.wasm",
                List.of("http://ex/label"),
                "{\"index\":\"products\",\"backend_endpoint\":\"http://localhost:9308\"}",
                List.of("en"),
                OptionalInt.empty());
        return FulltextRegistry.of(List.of(ix));
    }

    private static FulltextRegistry documentCorpusRegistry() {
        final FulltextRegistry.FulltextIndex ix = new FulltextRegistry.FulltextIndex(
                "manuals",
                FulltextRegistry.FulltextMode.DOCUMENT_CORPUS,
                "file:///opt/wf_fulltext.wasm",
                List.of(),
                "{\"index\":\"manuals\"}",
                List.of(),
                OptionalInt.empty());
        return FulltextRegistry.of(List.of(ix));
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

    /** Does a Filter node whose condition is a FunctionCall of {@code uri} survive? */
    private static boolean hasFilterWithFunctionCall(final TupleExpr expr, final String uri) {
        final boolean[] hit = new boolean[1];
        expr.visit(new AbstractQueryModelVisitor<RuntimeException>() {
            @Override
            public void meet(final Filter f) {
                if (f.getCondition() instanceof FunctionCall fc && uri.equals(fc.getURI())) {
                    hit[0] = true;
                }
                super.meet(f);
            }
            @Override protected void meetNode(final QueryModelNode n) { n.visitChildren(this); }
        });
        return hit[0];
    }

    /** Grab the InvokeSpec at id 0 (each test starts with a fresh registry). */
    private static InvokeSpec takeFirstInvoke(final InvokeRegistry inv) {
        final InvokeSpec s = inv.take(0L);
        assertThat(s).as("expected an InvokeSpec at id 0").isNotNull();
        return s;
    }

    private static String queryArg(final InvokeSpec spec) {
        return ((Literal) spec.args().get(2)).getLabel();
    }
    private static String optsArg(final InvokeSpec spec) {
        return ((Literal) spec.args().get(3)).getLabel();
    }

    private static List<Service> collectServices(final TupleExpr expr) {
        final List<Service> out = new ArrayList<>();
        expr.visit(new AbstractQueryModelVisitor<RuntimeException>() {
            @Override public void meet(final Service s) { out.add(s); super.meet(s); }
            @Override protected void meetNode(final QueryModelNode n) { n.visitChildren(this); }
        });
        return out;
    }

    // ---------------------------------------------------------------------
    // Tests — port from fulltext_rewrite.rs::tests
    // ---------------------------------------------------------------------

    @Test
    public void regexSafePatternFoldsToService() {
        final FulltextRegistry reg = productsRegistryWord();
        final InvokeRegistry inv = new InvokeRegistry();
        final ParsedQuery pq = parse(""
                + "PREFIX ex: <http://ex/>\n"
                + "SELECT ?p ?label WHERE {\n"
                + "  ?p ex:label ?label .\n"
                + "  FILTER(REGEX(?label, \"widget\"))\n"
                + "}");
        final FulltextRewrite rw = new FulltextRewrite(reg, inv);
        final int n = rw.rewritePattern(pq.getTupleExpr());
        assertThat(n).isEqualTo(1);
        assertThat(hasWfInvokeService(pq.getTupleExpr())).isTrue();
    }

    @Test
    public void regexUnsafePatternSkips() {
        final FulltextRegistry reg = productsRegistryWord();
        final InvokeRegistry inv = new InvokeRegistry();
        final ParsedQuery pq = parse(""
                + "PREFIX ex: <http://ex/>\n"
                + "SELECT ?p ?label WHERE {\n"
                + "  ?p ex:label ?label .\n"
                + "  FILTER(REGEX(?label, \"^widget\"))\n"
                + "}");
        final FulltextRewrite rw = new FulltextRewrite(reg, inv);
        assertThat(rw.rewritePattern(pq.getTupleExpr())).isZero();
        assertThat(hasWfInvokeService(pq.getTupleExpr())).isFalse();
    }

    @Test
    public void regexBackrefSkips() {
        final FulltextRegistry reg = productsRegistryWord();
        final InvokeRegistry inv = new InvokeRegistry();
        final ParsedQuery pq = parse(""
                + "PREFIX ex: <http://ex/>\n"
                + "SELECT ?p ?label WHERE {\n"
                + "  ?p ex:label ?label .\n"
                + "  FILTER(REGEX(?label, \"(foo)\\\\1\"))\n"
                + "}");
        final FulltextRewrite rw = new FulltextRewrite(reg, inv);
        assertThat(rw.rewritePattern(pq.getTupleExpr())).isZero();
        assertThat(hasWfInvokeService(pq.getTupleExpr())).isFalse();
    }

    @Test
    public void containsWordFolds() {
        final FulltextRegistry reg = productsRegistryWord();
        final InvokeRegistry inv = new InvokeRegistry();
        final ParsedQuery pq = parse(""
                + "PREFIX ex: <http://ex/>\n"
                + "SELECT ?p ?label WHERE {\n"
                + "  ?p ex:label ?label .\n"
                + "  FILTER(CONTAINS(?label, \"widget\"))\n"
                + "}");
        final FulltextRewrite rw = new FulltextRewrite(reg, inv);
        assertThat(rw.rewritePattern(pq.getTupleExpr())).isEqualTo(1);
        assertThat(hasWfInvokeService(pq.getTupleExpr())).isTrue();
    }

    @Test
    public void containsPartialWordSkips() {
        final FulltextRegistry reg = productsRegistryWord();
        final InvokeRegistry inv = new InvokeRegistry();
        final ParsedQuery pq = parse(""
                + "PREFIX ex: <http://ex/>\n"
                + "SELECT ?p ?label WHERE {\n"
                + "  ?p ex:label ?label .\n"
                + "  FILTER(CONTAINS(?label, \"wid\"))\n"
                + "}");
        final FulltextRewrite rw = new FulltextRewrite(reg, inv);
        assertThat(rw.rewritePattern(pq.getTupleExpr())).isZero();
        assertThat(hasWfInvokeService(pq.getTupleExpr())).isFalse();
    }

    @Test
    public void strstartsFolds() {
        final FulltextRegistry reg = productsRegistryWord();
        final InvokeRegistry inv = new InvokeRegistry();
        final ParsedQuery pq = parse(""
                + "PREFIX ex: <http://ex/>\n"
                + "SELECT ?p ?label WHERE {\n"
                + "  ?p ex:label ?label .\n"
                + "  FILTER(STRSTARTS(?label, \"wid\"))\n"
                + "}");
        final FulltextRewrite rw = new FulltextRewrite(reg, inv);
        assertThat(rw.rewritePattern(pq.getTupleExpr())).isEqualTo(1);
        assertThat(hasWfInvokeService(pq.getTupleExpr())).isTrue();
    }

    @Test
    public void langMatchesFoldsIfCovered() {
        final FulltextRegistry reg = productsRegistryWord();
        final InvokeRegistry inv = new InvokeRegistry();
        final ParsedQuery pq = parse(""
                + "PREFIX ex: <http://ex/>\n"
                + "SELECT ?p ?label WHERE {\n"
                + "  ?p ex:label ?label .\n"
                + "  FILTER(LANG(?label) = \"en\")\n"
                + "}");
        final FulltextRewrite rw = new FulltextRewrite(reg, inv);
        assertThat(rw.rewritePattern(pq.getTupleExpr())).isEqualTo(1);
        assertThat(hasWfInvokeService(pq.getTupleExpr())).isTrue();
    }

    @Test
    public void langMatchesSkipsIfNotCovered() {
        final FulltextRegistry reg = productsRegistryWord();
        final InvokeRegistry inv = new InvokeRegistry();
        // Registry only claims "en"; query asks for "fr".
        final ParsedQuery pq = parse(""
                + "PREFIX ex: <http://ex/>\n"
                + "SELECT ?p ?label WHERE {\n"
                + "  ?p ex:label ?label .\n"
                + "  FILTER(LANG(?label) = \"fr\")\n"
                + "}");
        final FulltextRewrite rw = new FulltextRewrite(reg, inv);
        assertThat(rw.rewritePattern(pq.getTupleExpr())).isZero();
        assertThat(hasWfInvokeService(pq.getTupleExpr())).isFalse();
    }

    @Test
    public void documentCorpusPredicateNeverFolds() {
        final FulltextRegistry reg = documentCorpusRegistry();
        final InvokeRegistry inv = new InvokeRegistry();
        final ParsedQuery pq = parse(""
                + "PREFIX ex: <http://ex/>\n"
                + "SELECT ?p ?label WHERE {\n"
                + "  ?p ex:label ?label .\n"
                + "  FILTER(CONTAINS(?label, \"widget\"))\n"
                + "}");
        final FulltextRewrite rw = new FulltextRewrite(reg, inv);
        // ex:label isn't under any literal-index entry.
        assertThat(rw.rewritePattern(pq.getTupleExpr())).isZero();
        assertThat(hasWfInvokeService(pq.getTupleExpr())).isFalse();
    }

    @Test
    public void filterOverConcatSkips() {
        final FulltextRegistry reg = productsRegistryWord();
        final InvokeRegistry inv = new InvokeRegistry();
        final ParsedQuery pq = parse(""
                + "PREFIX ex: <http://ex/>\n"
                + "SELECT ?p ?a ?b WHERE {\n"
                + "  ?p ex:label ?a ; ex:label ?b .\n"
                + "  FILTER(CONTAINS(CONCAT(?a, ?b), \"widget\"))\n"
                + "}");
        final FulltextRewrite rw = new FulltextRewrite(reg, inv);
        assertThat(rw.rewritePattern(pq.getTupleExpr())).isZero();
        assertThat(hasWfInvokeService(pq.getTupleExpr())).isFalse();
    }

    @Test
    public void originalFilterPreserved() {
        final FulltextRegistry reg = productsRegistryWord();
        final InvokeRegistry inv = new InvokeRegistry();
        final ParsedQuery pq = parse(""
                + "PREFIX ex: <http://ex/>\n"
                + "SELECT ?p ?label WHERE {\n"
                + "  ?p ex:label ?label .\n"
                + "  FILTER(CONTAINS(?label, \"widget\"))\n"
                + "}");
        final FulltextRewrite rw = new FulltextRewrite(reg, inv);
        assertThat(rw.rewritePattern(pq.getTupleExpr())).isEqualTo(1);
        // Safety invariant (memo §06): outer FILTER(CONTAINS(...)) must
        // remain around the joined result.
        assertThat(hasFilterWithFunctionCall(pq.getTupleExpr(),
                "http://www.w3.org/2005/xpath-functions#contains"))
                .as("outer FILTER(CONTAINS(...)) must survive the rewrite")
                .isTrue();
        assertThat(hasWfInvokeService(pq.getTupleExpr())).isTrue();
    }

    @Test
    public void lcaseContainsFolds() {
        final FulltextRegistry reg = productsRegistryWord();
        final InvokeRegistry inv = new InvokeRegistry();
        final ParsedQuery pq = parse(""
                + "PREFIX ex: <http://ex/>\n"
                + "SELECT ?p ?label WHERE {\n"
                + "  ?p ex:label ?label .\n"
                + "  FILTER(CONTAINS(LCASE(?label), \"Waterproof\"))\n"
                + "}");
        final FulltextRewrite rw = new FulltextRewrite(reg, inv);
        assertThat(rw.rewritePattern(pq.getTupleExpr())).isEqualTo(1);
        assertThat(hasWfInvokeService(pq.getTupleExpr())).isTrue();

        final InvokeSpec spec = takeFirstInvoke(inv);
        assertThat(queryArg(spec)).isEqualTo("waterproof");
        assertThat(optsArg(spec)).contains("\"case_insensitive\":true");
    }

    @Test
    public void ucaseContainsFolds() {
        final FulltextRegistry reg = productsRegistryWord();
        final InvokeRegistry inv = new InvokeRegistry();
        final ParsedQuery pq = parse(""
                + "PREFIX ex: <http://ex/>\n"
                + "SELECT ?p ?label WHERE {\n"
                + "  ?p ex:label ?label .\n"
                + "  FILTER(CONTAINS(UCASE(?label), \"WATERPROOF\"))\n"
                + "}");
        final FulltextRewrite rw = new FulltextRewrite(reg, inv);
        assertThat(rw.rewritePattern(pq.getTupleExpr())).isEqualTo(1);
        assertThat(hasWfInvokeService(pq.getTupleExpr())).isTrue();

        final InvokeSpec spec = takeFirstInvoke(inv);
        // UCASE — the rewrite normalizes to lowercase regardless (backend
        // analyzers case-fold).
        assertThat(queryArg(spec)).isEqualTo("waterproof");
        assertThat(optsArg(spec)).contains("\"case_insensitive\":true");
    }

    @Test
    public void strWrapperFolds() {
        final FulltextRegistry reg = productsRegistryWord();
        final InvokeRegistry inv = new InvokeRegistry();
        final ParsedQuery pq = parse(""
                + "PREFIX ex: <http://ex/>\n"
                + "SELECT ?p ?label WHERE {\n"
                + "  ?p ex:label ?label .\n"
                + "  FILTER(CONTAINS(STR(?label), \"widget\"))\n"
                + "}");
        final FulltextRewrite rw = new FulltextRewrite(reg, inv);
        assertThat(rw.rewritePattern(pq.getTupleExpr())).isEqualTo(1);
        assertThat(hasWfInvokeService(pq.getTupleExpr())).isTrue();

        final InvokeSpec spec = takeFirstInvoke(inv);
        assertThat(queryArg(spec)).isEqualTo("widget");
        assertThat(optsArg(spec)).doesNotContain("case_insensitive");
    }

    @Test
    public void nestedLcaseStrFolds() {
        final FulltextRegistry reg = productsRegistryWord();
        final InvokeRegistry inv = new InvokeRegistry();
        final ParsedQuery pq = parse(""
                + "PREFIX ex: <http://ex/>\n"
                + "SELECT ?p ?label WHERE {\n"
                + "  ?p ex:label ?label .\n"
                + "  FILTER(CONTAINS(LCASE(STR(?label)), \"widget\"))\n"
                + "}");
        final FulltextRewrite rw = new FulltextRewrite(reg, inv);
        assertThat(rw.rewritePattern(pq.getTupleExpr())).isEqualTo(1);
        assertThat(hasWfInvokeService(pq.getTupleExpr())).isTrue();

        final InvokeSpec spec = takeFirstInvoke(inv);
        assertThat(optsArg(spec)).contains("\"case_insensitive\":true");
    }

    @Test
    public void concatArgSkips() {
        final FulltextRegistry reg = productsRegistryWord();
        final InvokeRegistry inv = new InvokeRegistry();
        final ParsedQuery pq = parse(""
                + "PREFIX ex: <http://ex/>\n"
                + "SELECT ?p ?a ?b WHERE {\n"
                + "  ?p ex:label ?a ; ex:label ?b .\n"
                + "  FILTER(CONTAINS(CONCAT(?a, ?b), \"widget\"))\n"
                + "}");
        final FulltextRewrite rw = new FulltextRewrite(reg, inv);
        assertThat(rw.rewritePattern(pq.getTupleExpr())).isZero();
        assertThat(hasWfInvokeService(pq.getTupleExpr())).isFalse();
    }

    @Test
    public void substrArgSkips() {
        final FulltextRegistry reg = productsRegistryWord();
        final InvokeRegistry inv = new InvokeRegistry();
        final ParsedQuery pq = parse(""
                + "PREFIX ex: <http://ex/>\n"
                + "SELECT ?p ?label WHERE {\n"
                + "  ?p ex:label ?label .\n"
                + "  FILTER(CONTAINS(SUBSTR(?label, 1, 3), \"widget\"))\n"
                + "}");
        final FulltextRewrite rw = new FulltextRewrite(reg, inv);
        assertThat(rw.rewritePattern(pq.getTupleExpr())).isZero();
        assertThat(hasWfInvokeService(pq.getTupleExpr())).isFalse();
    }

    @Test
    public void deeplyNestedWrappersCapsOut() {
        final FulltextRegistry reg = productsRegistryWord();
        final InvokeRegistry inv = new InvokeRegistry();
        // Four LCASE layers — one more than the cap.
        final ParsedQuery pq = parse(""
                + "PREFIX ex: <http://ex/>\n"
                + "SELECT ?p ?label WHERE {\n"
                + "  ?p ex:label ?label .\n"
                + "  FILTER(CONTAINS(LCASE(LCASE(LCASE(LCASE(?label)))), \"widget\"))\n"
                + "}");
        final FulltextRewrite rw = new FulltextRewrite(reg, inv);
        assertThat(rw.rewritePattern(pq.getTupleExpr())).isZero();
        assertThat(hasWfInvokeService(pq.getTupleExpr())).isFalse();
    }

    @Test
    public void emptyRegistryNoop() {
        final FulltextRegistry reg = FulltextRegistry.empty();
        final InvokeRegistry inv = new InvokeRegistry();
        final ParsedQuery pq = parse(""
                + "PREFIX ex: <http://ex/>\n"
                + "SELECT ?p ?label WHERE {\n"
                + "  ?p ex:label ?label .\n"
                + "  FILTER(CONTAINS(?label, \"widget\"))\n"
                + "}");
        final FulltextRewrite rw = new FulltextRewrite(reg, inv);
        assertThat(rw.rewritePattern(pq.getTupleExpr())).isZero();
        assertThat(hasWfInvokeService(pq.getTupleExpr())).isFalse();
        assertThat(collectServices(pq.getTupleExpr())).isEmpty();
    }
}

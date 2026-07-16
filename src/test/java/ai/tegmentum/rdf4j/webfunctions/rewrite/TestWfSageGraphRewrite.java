package ai.tegmentum.rdf4j.webfunctions.rewrite;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.query.algebra.QueryModelNode;
import org.eclipse.rdf4j.query.algebra.Service;
import org.eclipse.rdf4j.query.algebra.TupleExpr;
import org.eclipse.rdf4j.query.algebra.Var;
import org.eclipse.rdf4j.query.algebra.helpers.AbstractQueryModelVisitor;
import org.eclipse.rdf4j.query.parser.ParsedQuery;
import org.eclipse.rdf4j.query.parser.sparql.SPARQLParser;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link WfSageGraphRewrite} — cross-engine parity with
 * {@code oxigraph-wf/src/wf_sagegraph_rewrite.rs::rewrite_query_guest_dispatch},
 * {@code qlever-wf-runtime/src/wf_sagegraph_rewrite.rs}, and
 * {@code jena-webfunction-plugin::WfSageGraphRewrite}.
 */
public class TestWfSageGraphRewrite {

    private static final String WASM_URL = "file:///opt/wf_sagegraph.wasm";

    // --- URL parser tests ------------------------------------------------

    @Test
    public void parsesNodeAndK() {
        final WfSageGraphRewrite.ParsedUrl p = WfSageGraphRewrite.parseUrl(
                "wf-sagegraph:people?node=http%3A%2F%2Fex%2Falice&k=2");
        assertThat(p).isNotNull();
        assertThat(p.name).isEqualTo("people");
        assertThat(p.nodeIri).isEqualTo("http://ex/alice");
        assertThat(p.k).isEqualTo(2);
        assertThat(p.model).isNull();
        assertThat(p.pool).isNull();
    }

    @Test
    public void parsesModelAndPoolOpts() {
        final WfSageGraphRewrite.ParsedUrl p = WfSageGraphRewrite.parseUrl(
                "wf-sagegraph:people?node=http%3A%2F%2Fex%2Falice&k=2"
                        + "&model=file%3A%2F%2F%2Fopt%2Fsage.onnx&pool=max");
        assertThat(p).isNotNull();
        assertThat(p.model).isEqualTo("file:///opt/sage.onnx");
        assertThat(p.pool).isEqualTo("max");
    }

    @Test
    public void defaultsKAbsent() {
        final WfSageGraphRewrite.ParsedUrl p = WfSageGraphRewrite.parseUrl(
                "wf-sagegraph:people?node=http%3A%2F%2Fex%2Falice");
        assertThat(p).isNotNull();
        assertThat(p.k).isNull();
    }

    @Test
    public void rejectsMissingNode() {
        assertThat(WfSageGraphRewrite.parseUrl("wf-sagegraph:people?k=2")).isNull();
    }

    @Test
    public void rejectsBareScheme() {
        assertThat(WfSageGraphRewrite.parseUrl("wf-sagegraph:")).isNull();
    }

    // --- Rewrite tests --------------------------------------------------

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

    private static boolean hasWfSagegraphService(final TupleExpr expr) {
        final boolean[] hit = new boolean[1];
        expr.visit(new AbstractQueryModelVisitor<RuntimeException>() {
            @Override
            public void meet(final Service s) {
                final Var ref = s.getServiceRef();
                if (ref != null && ref.hasValue() && ref.getValue() instanceof IRI iri
                        && iri.stringValue().startsWith(WfSageGraphRewrite.WF_SAGEGRAPH_SCHEME)) {
                    hit[0] = true;
                }
                super.meet(s);
            }
            @Override protected void meetNode(final QueryModelNode n) { n.visitChildren(this); }
        });
        return hit[0];
    }

    @Test
    public void foldsServiceIntoWfInvoke() {
        final InvokeRegistry invokes = new InvokeRegistry();
        final String sparql = ""
                + "PREFIX wf: <http://tegmentum.ai/ns/webfunction/>\n"
                + "SELECT ?node ?embedding WHERE {\n"
                + "  SERVICE <wf-sagegraph:people?node=http%3A%2F%2Fex%2Falice&k=2> {\n"
                + "    ?_ wf:node ?node ; wf:embedding ?embedding\n"
                + "  }\n"
                + "}";
        final ParsedQuery pq = parse(sparql);
        final WfSageGraphRewrite pass = new WfSageGraphRewrite(invokes, WASM_URL);
        final int n = pass.rewritePattern(pq.getTupleExpr());

        assertThat(n).isEqualTo(1);
        assertThat(hasWfInvokeService(pq.getTupleExpr())).isTrue();
        assertThat(hasWfSagegraphService(pq.getTupleExpr())).isFalse();
        final InvokeSpec spec = invokes.take(0L);
        assertThat(spec).as("expected an InvokeSpec at id 0").isNotNull();
        assertThat(spec.wasmUrl()).isEqualTo(WASM_URL);
        assertThat(spec.entryPoint()).isEqualTo("embed");
        assertThat(spec.args()).hasSize(4);
        assertThat(spec.args().get(0).stringValue()).isEqualTo("http://ex/alice");
        assertThat(spec.args().get(1).stringValue()).isEqualTo("wf-sagegraph:stubbed-model");
        assertThat(spec.args().get(2).stringValue()).isEqualTo("2");
        final String opts = spec.args().get(3).stringValue();
        assertThat(opts).contains("\"dimensions\":8");
        assertThat(opts).contains("\"pool\":\"mean\"");

        assertThat(spec.projection()).containsEntry("node", "node");
        assertThat(spec.projection()).containsEntry("embedding", "embedding");
    }

    @Test
    public void urlModelAndPoolLandInArgs() {
        final InvokeRegistry invokes = new InvokeRegistry();
        final String sparql = ""
                + "PREFIX wf: <http://tegmentum.ai/ns/webfunction/>\n"
                + "SELECT ?embedding WHERE {\n"
                + "  SERVICE <wf-sagegraph:people?node=http%3A%2F%2Fex%2Falice"
                + "&k=1&model=file%3A%2F%2F%2Fopt%2Fsage.onnx&pool=sum> {\n"
                + "    ?_ wf:embedding ?embedding\n"
                + "  }\n"
                + "}";
        final ParsedQuery pq = parse(sparql);
        new WfSageGraphRewrite(invokes, WASM_URL).rewritePattern(pq.getTupleExpr());

        final InvokeSpec spec = invokes.take(0L);
        assertThat(spec).as("expected an InvokeSpec at id 0").isNotNull();
        assertThat(spec.args().get(1).stringValue()).isEqualTo("file:///opt/sage.onnx");
        assertThat(spec.args().get(3).stringValue()).contains("\"pool\":\"sum\"");
    }

    @Test
    public void emptyWasmUrlShortCircuits() {
        final InvokeRegistry invokes = new InvokeRegistry();
        final String sparql = ""
                + "PREFIX wf: <http://tegmentum.ai/ns/webfunction/>\n"
                + "SELECT ?embedding WHERE {\n"
                + "  SERVICE <wf-sagegraph:people?node=http%3A%2F%2Fex%2Falice&k=1> {\n"
                + "    ?_ wf:embedding ?embedding\n"
                + "  }\n"
                + "}";
        final ParsedQuery pq = parse(sparql);
        final int n = new WfSageGraphRewrite(invokes, "").rewritePattern(pq.getTupleExpr());
        assertThat(n).isEqualTo(0);
        assertThat(invokes.peek(0L)).isNull();
    }

    @Test
    public void nullInvokesShortCircuits() {
        final String sparql = ""
                + "PREFIX wf: <http://tegmentum.ai/ns/webfunction/>\n"
                + "SELECT ?embedding WHERE {\n"
                + "  SERVICE <wf-sagegraph:people?node=http%3A%2F%2Fex%2Falice&k=1> {\n"
                + "    ?_ wf:embedding ?embedding\n"
                + "  }\n"
                + "}";
        final ParsedQuery pq = parse(sparql);
        // Doesn't throw; pass short-circuits without side effect.
        new WfSageGraphRewrite(null, WASM_URL).rewritePattern(pq.getTupleExpr());
        assertThat(hasWfSagegraphService(pq.getTupleExpr())).isTrue();
    }

    @Test
    public void bodyWithoutEmbeddingLeavesServiceAlone() {
        final InvokeRegistry invokes = new InvokeRegistry();
        final String sparql = ""
                + "PREFIX wf: <http://tegmentum.ai/ns/webfunction/>\n"
                + "SELECT ?node WHERE {\n"
                + "  SERVICE <wf-sagegraph:people?node=http%3A%2F%2Fex%2Fa&k=1> {\n"
                + "    ?_ wf:node ?node\n"
                + "  }\n"
                + "}";
        final ParsedQuery pq = parse(sparql);
        final int n = new WfSageGraphRewrite(invokes, WASM_URL).rewritePattern(pq.getTupleExpr());
        assertThat(n).isEqualTo(0);
        assertThat(invokes.peek(0L)).isNull();
        assertThat(hasWfSagegraphService(pq.getTupleExpr())).isTrue();
    }

    @Test
    public void malformedUrlLeavesServiceAlone() {
        final InvokeRegistry invokes = new InvokeRegistry();
        final String sparql = ""
                + "PREFIX wf: <http://tegmentum.ai/ns/webfunction/>\n"
                + "SELECT ?embedding WHERE {\n"
                + "  SERVICE <wf-sagegraph:people?k=1> {\n"
                + "    ?_ wf:embedding ?embedding\n"
                + "  }\n"
                + "}";
        final ParsedQuery pq = parse(sparql);
        final int n = new WfSageGraphRewrite(invokes, WASM_URL).rewritePattern(pq.getTupleExpr());
        assertThat(n).isEqualTo(0);
        assertThat(hasWfSagegraphService(pq.getTupleExpr())).isTrue();
    }

    @Test
    public void nonSagegraphServiceLeftAlone() {
        final InvokeRegistry invokes = new InvokeRegistry();
        final String sparql = ""
                + "PREFIX wf: <http://tegmentum.ai/ns/webfunction/>\n"
                + "SELECT ?doc WHERE {\n"
                + "  SERVICE <wf-search:manuals?query=hi> {\n"
                + "    ?_ wf:embedding ?doc\n"
                + "  }\n"
                + "}";
        final ParsedQuery pq = parse(sparql);
        final int n = new WfSageGraphRewrite(invokes, WASM_URL).rewritePattern(pq.getTupleExpr());
        assertThat(n).isEqualTo(0);
        assertThat(hasWfInvokeService(pq.getTupleExpr())).isFalse();
    }
}

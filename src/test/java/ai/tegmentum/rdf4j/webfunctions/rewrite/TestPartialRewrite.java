package ai.tegmentum.rdf4j.webfunctions.rewrite;

import org.eclipse.rdf4j.query.algebra.Extension;
import org.eclipse.rdf4j.query.algebra.QueryModelNode;
import org.eclipse.rdf4j.query.algebra.Service;
import org.eclipse.rdf4j.query.algebra.TupleExpr;
import org.eclipse.rdf4j.query.algebra.helpers.AbstractQueryModelVisitor;
import org.eclipse.rdf4j.query.parser.ParsedQuery;
import org.eclipse.rdf4j.query.parser.sparql.SPARQLParser;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class TestPartialRewrite {

    @Test
    public void foldsPartialAndRewritesServiceRef() {
        final String query = ""
                + "PREFIX wf: <http://tegmentum.ai/ns/webfunction/>\n"
                + "SELECT ?v WHERE {\n"
                + "  BIND(wf:partial(<http://example/apply.wasm>, \"hello\") AS ?svc)\n"
                + "  SERVICE ?svc { BIND(\"\" AS ?v) }\n"
                + "}";
        final ParsedQuery parsed = new SPARQLParser().parseQuery(query, null);

        final InvokeRegistry registry = new InvokeRegistry();
        final PartialRewrite rewrite = new PartialRewrite(registry);
        rewrite.optimize(parsed.getTupleExpr(), null, null);

        assertThat(rewrite.foldCount()).isEqualTo(1);

        // The dissolved Extension should no longer bind ?svc via wf:partial
        // &mdash; the entire Extension for ?svc is gone because it only
        // contained the fold. The Service node's ref should now be a
        // constant Var pointing at a wf-invoke: IRI.
        final List<Service> services = collectServices(parsed.getTupleExpr());
        assertThat(services).hasSize(1);
        final Service svc = services.get(0);
        assertThat(svc.getServiceRef().hasValue()).isTrue();
        assertThat(svc.getServiceRef().getValue().stringValue()).startsWith(InvokeRegistry.WF_INVOKE_SCHEME);

        // The registry has the InvokeSpec.
        final Long id = InvokeRegistry.idFromIri(svc.getServiceRef().getValue().stringValue());
        assertThat(id).isNotNull();
        final InvokeSpec spec = registry.peek(id);
        assertThat(spec).isNotNull();
        assertThat(spec.wasmUrl()).isEqualTo("http://example/apply.wasm");
        assertThat(spec.args()).hasSize(1);
        assertThat(spec.args().get(0).stringValue()).isEqualTo("hello");
    }

    @Test
    public void doesNotFoldNonConstantArgs() {
        final String query = ""
                + "PREFIX wf: <http://tegmentum.ai/ns/webfunction/>\n"
                + "SELECT ?v WHERE {\n"
                + "  BIND(\"x\" AS ?arg)\n"
                + "  BIND(wf:partial(<http://example/apply.wasm>, ?arg) AS ?svc)\n"
                + "  SERVICE ?svc { BIND(\"\" AS ?v) }\n"
                + "}";
        final ParsedQuery parsed = new SPARQLParser().parseQuery(query, null);
        final InvokeRegistry registry = new InvokeRegistry();
        final PartialRewrite rewrite = new PartialRewrite(registry);
        rewrite.optimize(parsed.getTupleExpr(), null, null);
        assertThat(rewrite.foldCount()).isZero();
    }

    private static List<Service> collectServices(final TupleExpr expr) {
        final List<Service> out = new ArrayList<>();
        expr.visit(new AbstractQueryModelVisitor<RuntimeException>() {
            @Override public void meet(final Service s) { out.add(s); super.meet(s); }
            @Override protected void meetNode(final QueryModelNode n) { n.visitChildren(this); }
        });
        return out;
    }

    static void _touch(final Extension e) { e.getArg(); }
}

package ai.tegmentum.rdf4j.webfunctions.rewrite;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.Dataset;
import org.eclipse.rdf4j.query.algebra.Extension;
import org.eclipse.rdf4j.query.algebra.ExtensionElem;
import org.eclipse.rdf4j.query.algebra.FunctionCall;
import org.eclipse.rdf4j.query.algebra.QueryModelNode;
import org.eclipse.rdf4j.query.algebra.Service;
import org.eclipse.rdf4j.query.algebra.TupleExpr;
import org.eclipse.rdf4j.query.algebra.ValueConstant;
import org.eclipse.rdf4j.query.algebra.ValueExpr;
import org.eclipse.rdf4j.query.algebra.Var;
import org.eclipse.rdf4j.query.algebra.evaluation.QueryOptimizer;
import org.eclipse.rdf4j.query.algebra.helpers.AbstractQueryModelVisitor;

import java.util.ArrayList;
import java.util.List;

/**
 * Constant-fold {@code wf:partial(...)} into a {@code wf-invoke:<id>} IRI
 * before the query hits the SPARQL evaluator.
 *
 * <p>Two passes:
 * <ol>
 *   <li><b>Collect &amp; dissolve</b> &mdash; walk the tree; every
 *       {@link ExtensionElem} whose expression is {@code wf:partial(<wasm>, args...)}
 *       with all-constant args gets a spec allocated and its element
 *       removed. The mapping (variable name &rarr; wf-invoke IRI) is
 *       recorded for phase two.</li>
 *   <li><b>Substitute</b> &mdash; walk again replacing any
 *       {@link Service} whose serviceRef Var name matches a collected
 *       variable with a constant Var pointing at the {@code wf-invoke:}
 *       IRI.</li>
 * </ol>
 *
 * <p>Java port of {@code oxigraph-wf/src/partial_rewrite.rs}. Two passes
 * because the Extend and its Service usage are typically sibling
 * subtrees in a Join, not parent/child.
 *
 * <p>Semantic gap note: in RDF4J an {@link Extension} carries multiple
 * {@link ExtensionElem}s; the "dissolve" step removes only the
 * partial-fold element rather than replacing the whole Extension. When
 * every element folds we drop the Extension entirely, matching the
 * Rust semantics.
 */
public final class PartialRewrite implements QueryOptimizer {

    private static final ValueFactory VF = SimpleValueFactory.getInstance();

    private final InvokeRegistry registry;
    private int folds;

    public PartialRewrite(final InvokeRegistry registry) {
        this.registry = registry;
    }

    public int foldCount() { return folds; }

    @Override
    public void optimize(final TupleExpr tupleExpr, final Dataset dataset, final BindingSet bindings) {
        final List<Fold> folds = new ArrayList<>();
        tupleExpr.visit(new CollectAndDissolve(folds));
        for (Fold f : folds) {
            tupleExpr.visit(new SubstituteServiceName(f.varName, f.iri));
        }
        this.folds = folds.size();
    }

    private record Fold(String varName, String iri) {}

    private final class CollectAndDissolve extends AbstractQueryModelVisitor<RuntimeException> {
        private final List<Fold> folds;

        CollectAndDissolve(final List<Fold> folds) {
            this.folds = folds;
        }

        @Override
        public void meet(final Extension ext) {
            // Recurse first so nested Extensions fold before we decide
            // whether to drop this one.
            super.meet(ext);

            final List<ExtensionElem> keep = new ArrayList<>();
            for (ExtensionElem elem : ext.getElements()) {
                final String iri = tryFoldPartial(elem.getExpr());
                if (iri != null) {
                    folds.add(new Fold(elem.getName(), iri));
                } else {
                    keep.add(elem);
                }
            }
            if (keep.size() == ext.getElements().size()) return; // no folds
            if (keep.isEmpty()) {
                // Dissolve: replace the Extension with its inner.
                ext.replaceWith(ext.getArg());
            } else {
                ext.setElements(keep);
            }
        }

        @Override
        protected void meetNode(final QueryModelNode n) {
            n.visitChildren(this);
        }
    }

    /**
     * If {@code e} is a {@code wf:partial(<wasm>, args...)} call whose
     * args are all constants, allocate a spec and return the
     * {@code wf-invoke:<id>} IRI; otherwise {@code null}.
     */
    private String tryFoldPartial(final ValueExpr e) {
        if (!(e instanceof FunctionCall fc)) return null;
        if (!InvokeRegistry.WF_PARTIAL_IRI.equals(fc.getURI())) return null;
        final List<ValueExpr> args = fc.getArgs();
        if (args == null || args.isEmpty()) return null;

        final Value wasmValue = evalConstant(args.get(0));
        if (wasmValue == null) return null;
        final String wasmUrl;
        if (wasmValue instanceof IRI iri) {
            wasmUrl = iri.stringValue();
        } else if (wasmValue instanceof Literal lit) {
            wasmUrl = lit.getLabel();
        } else {
            return null;
        }

        final List<Value> constArgs = new ArrayList<>(args.size() - 1);
        for (int i = 1; i < args.size(); i++) {
            final Value v = evalConstant(args.get(i));
            if (v == null) return null;
            constArgs.add(v);
        }
        final long id = registry.insert(new InvokeSpec(wasmUrl, constArgs));
        return InvokeRegistry.iriFor(id);
    }

    private static Value evalConstant(final ValueExpr e) {
        if (e instanceof ValueConstant vc) return vc.getValue();
        if (e instanceof Var v && v.hasValue()) return v.getValue();
        return null;
    }

    private final class SubstituteServiceName extends AbstractQueryModelVisitor<RuntimeException> {
        private final String varName;
        private final String iri;

        SubstituteServiceName(final String varName, final String iri) {
            this.varName = varName;
            this.iri = iri;
        }

        @Override
        public void meet(final Service service) {
            final Var ref = service.getServiceRef();
            if (ref != null && !ref.hasValue() && varName.equals(ref.getName())) {
                final Var replacement = Var.of(ref.getName(),
                        VF.createIRI(iri),
                        ref.isAnonymous(),
                        true);
                service.setServiceRef(replacement);
            }
            super.meet(service);
        }

        @Override
        protected void meetNode(final QueryModelNode n) {
            n.visitChildren(this);
        }
    }
}

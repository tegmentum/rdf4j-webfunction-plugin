package ai.tegmentum.rdf4j.webfunctions;

import ai.tegmentum.rdf4j.webfunctions.rewrite.InvokeRegistry;
import ai.tegmentum.rdf4j.webfunctions.rewrite.InvokeSpec;

import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.common.iteration.CloseableIteratorIteration;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.QueryEvaluationException;
import org.eclipse.rdf4j.query.algebra.QueryModelNode;
import org.eclipse.rdf4j.query.algebra.Service;
import org.eclipse.rdf4j.query.algebra.StatementPattern;
import org.eclipse.rdf4j.query.algebra.TupleExpr;
import org.eclipse.rdf4j.query.algebra.Var;
import org.eclipse.rdf4j.query.algebra.evaluation.federation.FederatedService;
import org.eclipse.rdf4j.query.algebra.helpers.AbstractQueryModelVisitor;
import org.eclipse.rdf4j.query.impl.MapBindingSet;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SERVICE handler for the {@code wf-invoke:<hex>} scheme emitted by
 * {@link ai.tegmentum.rdf4j.webfunctions.rewrite.PartialRewrite} after it
 * constant-folds a {@code wf:partial(<wasm>, args...)} call.
 *
 * <p>The wasm URL and args are already sitting in the plugin's
 * {@link InvokeRegistry} keyed by the hex id — this handler pops the
 * spec out, invokes the referenced wasm, and projects the returned rows
 * onto caller-facing variables using {@code wf:<column>} triples in the
 * SERVICE body (same output-mapping shape as
 * {@link WfCallFederatedService}).
 *
 * <p>Java analogue of
 * {@code oxigraph-wf/src/partial.rs::WfPartialDispatchHandler}.
 */
public final class WfInvokeService implements FederatedService {

    private static final String WF_NS = "http://tegmentum.ai/ns/webfunction/";

    private static final ValueFactory VF = SimpleValueFactory.getInstance();

    private final InvokeRegistry registry;
    private final long id;
    private volatile boolean initialized;

    public WfInvokeService(final InvokeRegistry registry, final long id) {
        this.registry = registry;
        this.id = id;
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    @Override
    public void initialize() {
        initialized = true;
    }

    @Override
    public void shutdown() {
        initialized = false;
    }

    @Override
    public boolean ask(final Service service, final BindingSet bindings, final String baseUri) {
        try (CloseableIteration<BindingSet> it =
                     select(service, Collections.emptySet(), bindings, baseUri)) {
            return it.hasNext();
        }
    }

    @Override
    public CloseableIteration<BindingSet> select(
            final Service service,
            final Set<String> projectionVars,
            final BindingSet parent,
            final String baseUri) {
        return evaluateOne(resolveOutputColumns(service), parent);
    }

    @Override
    public CloseableIteration<BindingSet> evaluate(
            final Service service,
            final CloseableIteration<BindingSet> bindings,
            final String baseUri) {
        final Map<String, String> outputVarByColumn = resolveOutputColumns(service);
        final List<BindingSet> out = new ArrayList<>();
        try {
            while (bindings.hasNext()) {
                final BindingSet b = bindings.next();
                try (CloseableIteration<BindingSet> rows = evaluateOne(outputVarByColumn, b)) {
                    while (rows.hasNext()) {
                        out.add(rows.next());
                    }
                }
            }
        } finally {
            bindings.close();
        }
        return new CloseableIteratorIteration<>(out.iterator());
    }

    /**
     * Prefer the rewrite-time projection map stashed on
     * {@link InvokeSpec#projection()} — that was captured BEFORE RDF4J
     * inlined the outer-visible {@code ?var} into the SERVICE body's
     * {@code wf:doc ?var} triples. Walking {@code service.getServiceExpr()}
     * at dispatch time silently loses those (now-constant) triples,
     * which collapses the outer join to a Cartesian product on the
     * shared variable (federation_wf_search regression). The fallback
     * still fires for legacy callers (e.g. wf:partial folds) whose
     * InvokeSpec has no projection.
     */
    private Map<String, String> resolveOutputColumns(final Service service) {
        final InvokeSpec probe = registry.peek(id);
        if (probe != null && !probe.projection().isEmpty()) {
            return probe.projection();
        }
        return parseOutputColumns(service.getServiceExpr());
    }

    private CloseableIteration<BindingSet> evaluateOne(final Map<String, String> outputVarByColumn,
                                                       final BindingSet parent) {
        final InvokeSpec spec = registry.peek(id);
        if (spec == null) {
            throw new QueryEvaluationException(
                    "wf-invoke: SERVICE: no invoke-spec registered for id " + Long.toHexString(id));
        }

        final URL wasmUrl;
        try {
            wasmUrl = new URL(spec.wasmUrl());
        } catch (MalformedURLException e) {
            throw new QueryEvaluationException(
                    "wf-invoke: SERVICE: bad wasm URL: " + spec.wasmUrl(), e);
        }

        final List<Value> args = spec.args();
        final Value[] argArr = args.toArray(new Value[0]);

        final List<WitValueMarshaller.Row> rows;
        try (Rdf4jWasmInstance instance = new Rdf4jWasmInstance(wasmUrl)) {
            // Honor a caller-supplied entry-point override (populated by
            // rewrite passes that know the target guest's WIT world
            // exports something other than `evaluate` — e.g.
            // wf_fulltext exports `search`). Null routes through
            // Rdf4jWasmInstance's auto-detect path.
            rows = instance.invokeEntry(spec.entryPoint(), VF, argArr);
        } catch (IOException e) {
            throw new QueryEvaluationException(
                    "wf-invoke: SERVICE failed: " + e.getMessage(), e);
        }

        final List<BindingSet> out = new ArrayList<>(rows.size());
        rows:
        for (WitValueMarshaller.Row row : rows) {
            final MapBindingSet mbs = new MapBindingSet();
            // Preserve outer bindings so downstream operators still see them.
            parent.forEach(b -> mbs.addBinding(b.getName(), b.getValue()));
            if (outputVarByColumn.isEmpty()) {
                // No wf:<column> projection triples in the body — expose
                // every wasm-returned column verbatim under its wasm name.
                for (int i = 0; i < row.vars.size(); i++) {
                    final Value v = row.values.get(i);
                    if (v == null) continue;
                    final String name = row.vars.get(i);
                    final Value existing = parent.getValue(name);
                    if (existing != null) {
                        // SPARQL join semantics: drop rows whose guest
                        // column disagrees with an outer binding that
                        // already claimed the same variable name. The
                        // wasm side never saw the outer binding, so we
                        // finish the join here.
                        if (!existing.equals(v)) continue rows;
                    } else {
                        mbs.addBinding(name, v);
                    }
                }
            } else {
                for (Map.Entry<String, String> e : outputVarByColumn.entrySet()) {
                    final int idx = row.vars.indexOf(e.getKey());
                    if (idx < 0) continue;
                    final Value v = row.values.get(idx);
                    if (v == null) continue;
                    final String outerVar = e.getValue();
                    final Value existing = parent.getValue(outerVar);
                    if (existing != null) {
                        // See note above — enforce the shared-variable
                        // compat merge here. The wasm-emitted column
                        // was renamed at rewrite time onto the outer
                        // variable; if the outer already bound it, the
                        // join filters mismatches.
                        if (!existing.equals(v)) continue rows;
                    } else {
                        mbs.addBinding(outerVar, v);
                    }
                }
            }
            out.add(mbs);
        }
        return new CloseableIteratorIteration<>(out.iterator());
    }

    /**
     * Walk the SERVICE body and collect the {@code wf:<colname> ?var}
     * output-projection triples. Any {@code wf:wasm} / {@code wf:arg}
     * triples that happen to appear are ignored — args come from the
     * pre-folded {@link InvokeSpec}, not the body.
     */
    private static Map<String, String> parseOutputColumns(final TupleExpr expr) {
        final ColumnCollector v = new ColumnCollector();
        expr.visit(v);
        return v.outputVarByColumn;
    }

    private static final class ColumnCollector extends AbstractQueryModelVisitor<RuntimeException> {
        // LinkedHashMap keeps declaration order stable for reproducible debug output.
        final Map<String, String> outputVarByColumn = new LinkedHashMap<>();

        @Override
        public void meet(final StatementPattern sp) {
            final Var predVar = sp.getPredicateVar();
            if (predVar == null || !predVar.hasValue()) return;
            final Value predVal = predVar.getValue();
            if (!(predVal instanceof IRI iri)) return;
            final String pUri = iri.stringValue();
            if (!pUri.startsWith(WF_NS)) return;
            final String colname = pUri.substring(WF_NS.length());
            // Skip the parameter-side predicates; the InvokeSpec supplies
            // the wasm URL and args directly.
            if ("wasm".equals(colname) || "arg".equals(colname)) return;
            final Var obj = sp.getObjectVar();
            if (obj == null || obj.hasValue()) return;
            outputVarByColumn.put(colname, obj.getName());
        }

        @Override
        protected void meetNode(final QueryModelNode node) {
            node.visitChildren(this);
        }
    }
}

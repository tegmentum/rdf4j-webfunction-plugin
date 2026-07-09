package ai.tegmentum.rdf4j.webfunctions;

import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.common.iteration.CloseableIteratorIteration;
import org.eclipse.rdf4j.common.iteration.EmptyIteration;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
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
 * BGP-envelope {@link FederatedService} for the {@code SERVICE <wf:call>} form
 * (or its expanded IRI {@code http://tegmentum.ai/ns/webfunction/call}). This
 * complements {@link WfCallService} — that one matches when the SERVICE URI
 * IS the wasm URL. This one keeps the wasm URL, positional args, and output-
 * column mapping inside the SERVICE body as triples, matching the shape
 * shipped by the oxigraph-wf and jena-webfunction-plugin siblings.
 *
 * <p>Recognised triples inside the SERVICE body (predicates in the
 * {@code http://tegmentum.ai/ns/webfunction/} namespace; subjects are ignored
 * — they only exist to satisfy Turtle grammar):
 * <ul>
 *   <li>{@code wf:wasm <url>} — the wasm component URL (constant IRI).</li>
 *   <li>{@code wf:arg X} (repeatable, in document order) — positional args
 *       to {@code evaluate(list<value>)}. {@code X} may be an outer-bound
 *       variable.</li>
 *   <li>{@code wf:<colname> ?var} — projects the wasm's binding-set column
 *       {@code <colname>} onto {@code ?var} per output row.</li>
 * </ul>
 *
 * <p>Semantics: per outer input binding, args are resolved against the
 * binding; unbound-variable arg short-circuits (no rows for that input); the
 * wasm is invoked once; each returned row is emitted as a fresh
 * {@link BindingSet} that extends the input binding with the output columns.
 * That's the correct join semantic — the outer join is a Cartesian product
 * per input row.
 */
public final class WfCallFederatedService implements FederatedService {

    /** Short-form service IRI. Registered on the resolver alongside the full IRI. */
    public static final String SHORT_URI = "wf:call";

    /** Fully-qualified service IRI matching the sibling implementations. */
    public static final String FULL_URI = "http://tegmentum.ai/ns/webfunction/call";

    private static final String WF_NS = "http://tegmentum.ai/ns/webfunction/";
    private static final String WF_WASM = WF_NS + "wasm";
    private static final String WF_ARG = WF_NS + "arg";

    private static final ValueFactory VF = SimpleValueFactory.getInstance();

    private volatile boolean initialized;

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
        // ASK is defined as "does any row come back for this outer binding".
        // Cheapest correct answer: run select and probe the iterator.
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
        final Envelope env = parseEnvelope(service.getServiceExpr());
        return evaluateOne(env, parent);
    }

    @Override
    public CloseableIteration<BindingSet> evaluate(
            final Service service,
            final CloseableIteration<BindingSet> bindings,
            final String baseUri) {
        // Parse the envelope once — the SERVICE body is constant across the
        // outer binding stream, and parsing walks the algebra which is not
        // trivial. Per-input we only resolve args + invoke wasm.
        final Envelope env = parseEnvelope(service.getServiceExpr());
        final List<BindingSet> out = new ArrayList<>();
        try {
            while (bindings.hasNext()) {
                final BindingSet b = bindings.next();
                try (CloseableIteration<BindingSet> rows = evaluateOne(env, b)) {
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

    private CloseableIteration<BindingSet> evaluateOne(final Envelope env, final BindingSet parent) {
        // Wasm URL is either a constant IRI/literal or an outer-bound variable
        // resolving to one. An unbound variable is a legitimate no-match for
        // this input — do not throw.
        final Value wasmValue = resolveValue(env.wasmVar, parent);
        if (wasmValue == null) return new EmptyIteration<>();
        final URL wasmUrl;
        try {
            wasmUrl = new URL(valueAsUrlString(wasmValue));
        } catch (MalformedURLException e) {
            throw new QueryEvaluationException("wf:call SERVICE: bad wasm URL: " + wasmValue, e);
        }

        // Resolve positional args in document order. Any unbound variable
        // short-circuits — a wasm call without a full arg list cannot
        // produce a meaningful row, and the outer join must contribute
        // nothing for this input.
        final Value[] args = new Value[env.argVars.size()];
        for (int i = 0; i < args.length; i++) {
            final Value resolved = resolveValue(env.argVars.get(i), parent);
            if (resolved == null) return new EmptyIteration<>();
            args[i] = resolved;
        }

        final List<WitValueMarshaller.Row> rows;
        try (Rdf4jWasmInstance instance = new Rdf4jWasmInstance(wasmUrl)) {
            rows = instance.evaluate(VF, args);
        } catch (IOException e) {
            throw new QueryEvaluationException("wf:call SERVICE failed: " + e.getMessage(), e);
        }

        final List<BindingSet> out = new ArrayList<>(rows.size());
        for (WitValueMarshaller.Row row : rows) {
            final MapBindingSet mbs = new MapBindingSet();
            // Preserve outer bindings so downstream operators (FILTER on
            // outer vars, projection, etc.) still see them post-SERVICE.
            parent.forEach(b -> mbs.addBinding(b.getName(), b.getValue()));
            for (Map.Entry<String, String> e : env.outputVarByColumn.entrySet()) {
                final int idx = row.vars.indexOf(e.getKey());
                if (idx < 0) continue;
                final Value v = row.values.get(idx);
                // Unbound column stays unbound — MapBindingSet omits it, and
                // downstream SPARQL sees UNDEF for that variable.
                if (v != null) mbs.addBinding(e.getValue(), v);
            }
            out.add(mbs);
        }
        return new CloseableIteratorIteration<>(out.iterator());
    }

    // ---- envelope parsing -------------------------------------------------

    /**
     * Walk the SERVICE body's algebra tree and extract wf: triples. RDF4J's
     * BGPs are typically nested Joins of StatementPatterns; a visitor over
     * every StatementPattern is the sturdy way to collect them regardless of
     * the join tree's shape.
     */
    private static Envelope parseEnvelope(final TupleExpr expr) {
        final EnvelopeCollector v = new EnvelopeCollector();
        expr.visit(v);
        if (v.wasmVar == null) {
            throw new QueryEvaluationException(
                    "wf:call SERVICE: no wf:wasm triple found in SERVICE body");
        }
        return new Envelope(v.wasmVar, v.argVars, v.outputVarByColumn);
    }

    private static final class EnvelopeCollector extends AbstractQueryModelVisitor<RuntimeException> {
        Var wasmVar;
        final List<Var> argVars = new ArrayList<>();
        // LinkedHashMap preserves declaration order so debug output reflects
        // the source Turtle; not load-bearing for correctness.
        final Map<String, String> outputVarByColumn = new LinkedHashMap<>();

        @Override
        public void meet(final StatementPattern sp) {
            final Var predVar = sp.getPredicateVar();
            if (predVar == null || !predVar.hasValue()) return;
            final Value predVal = predVar.getValue();
            if (!(predVal instanceof IRI iri)) return;
            final String pUri = iri.stringValue();

            if (WF_WASM.equals(pUri)) {
                final Var obj = sp.getObjectVar();
                if (wasmVar != null && varSame(wasmVar, obj)) {
                    // Duplicate but identical — SPARQL de-duplicates triples
                    // implicitly during parsing, so a real duplicate is fine.
                    return;
                }
                if (wasmVar != null) {
                    throw new QueryEvaluationException(
                            "wf:call SERVICE: multiple wf:wasm targets — only one is supported");
                }
                wasmVar = obj;
            } else if (WF_ARG.equals(pUri)) {
                argVars.add(sp.getObjectVar());
            } else if (pUri.startsWith(WF_NS)) {
                final String colname = pUri.substring(WF_NS.length());
                final Var obj = sp.getObjectVar();
                if (obj.hasValue()) {
                    throw new QueryEvaluationException(
                            "wf:call SERVICE: wf:" + colname + " object must be a variable");
                }
                outputVarByColumn.put(colname, obj.getName());
            }
            // Unknown predicates outside wf: — silently ignore. Users can
            // decorate the envelope with anchor triples (a rdf:type hint,
            // say) without confusing the executor.
        }

        @Override
        protected void meetNode(final QueryModelNode node) {
            node.visitChildren(this);
        }
    }

    private static boolean varSame(final Var a, final Var b) {
        if (a == null || b == null) return false;
        if (a.hasValue() && b.hasValue()) return a.getValue().equals(b.getValue());
        if (!a.hasValue() && !b.hasValue()) return a.getName().equals(b.getName());
        return false;
    }

    /** Resolve an object {@link Var} against the outer binding — null iff unbound. */
    private static Value resolveValue(final Var v, final BindingSet parent) {
        if (v.hasValue()) return v.getValue();
        return parent.getValue(v.getName());
    }

    private static String valueAsUrlString(final Value v) {
        if (v instanceof IRI iri) return iri.stringValue();
        if (v instanceof Literal literal) return literal.getLabel();
        throw new QueryEvaluationException(
                "wf:call SERVICE: wf:wasm must be an IRI or string literal, got: " + v);
    }

    /** Frozen envelope shared across outer input bindings. */
    private static final class Envelope {
        final Var wasmVar;
        final List<Var> argVars;
        final Map<String, String> outputVarByColumn;

        Envelope(final Var wasmVar,
                 final List<Var> argVars,
                 final Map<String, String> outputVarByColumn) {
            this.wasmVar = wasmVar;
            this.argVars = Collections.unmodifiableList(argVars);
            this.outputVarByColumn = Collections.unmodifiableMap(outputVarByColumn);
        }
    }
}

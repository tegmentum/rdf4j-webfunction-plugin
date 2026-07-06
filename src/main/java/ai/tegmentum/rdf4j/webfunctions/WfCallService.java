package ai.tegmentum.rdf4j.webfunctions;

import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.common.iteration.CloseableIteratorIteration;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.QueryEvaluationException;
import org.eclipse.rdf4j.query.algebra.Extension;
import org.eclipse.rdf4j.query.algebra.ExtensionElem;
import org.eclipse.rdf4j.query.algebra.QueryModelNode;
import org.eclipse.rdf4j.query.algebra.Service;
import org.eclipse.rdf4j.query.algebra.TupleExpr;
import org.eclipse.rdf4j.query.algebra.ValueConstant;
import org.eclipse.rdf4j.query.algebra.evaluation.federation.FederatedService;
import org.eclipse.rdf4j.query.impl.MapBindingSet;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/**
 * RDF4J {@link FederatedService} for {@code SERVICE <wasm-url> { BIND(...) }}.
 * Extracts positional args ({@code ?arg0}, {@code ?arg1}, …) from inner BIND
 * clauses, invokes the component's {@code evaluate} export, and yields each
 * result row joined with the parent binding.
 *
 * <p>RDF4J doesn't have an SPI-registered federated service — install this
 * via a {@link WfServiceResolver} wrapper on the repository's federated
 * service resolver. See {@code TestWfCallService} for the wiring.
 */
public final class WfCallService implements FederatedService {

    private static final ValueFactory VF = SimpleValueFactory.getInstance();

    private final URL wasmUrl;
    private volatile boolean initialized;

    public WfCallService(final URL wasmUrl) {
        this.wasmUrl = wasmUrl;
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
        return !select(service, Collections.emptySet(), bindings, baseUri).stream().findFirst().isEmpty();
    }

    @Override
    public CloseableIteration<BindingSet> select(
            final Service service,
            final Set<String> projectionVars,
            final BindingSet parent,
            final String baseUri) {
        final Value[] args = extractArgs(service.getServiceExpr(), parent);

        final List<WitValueMarshaller.Row> rows;
        try (Rdf4jWasmInstance instance = new Rdf4jWasmInstance(wasmUrl)) {
            rows = instance.evaluate(VF, args);
        } catch (IOException e) {
            throw new QueryEvaluationException("wf:call SERVICE failed: " + e.getMessage(), e);
        }

        final List<BindingSet> out = new ArrayList<>(rows.size());
        for (WitValueMarshaller.Row row : rows) {
            final MapBindingSet mbs = new MapBindingSet();
            parent.forEach(b -> mbs.addBinding(b.getName(), b.getValue()));
            for (int i = 0; i < row.vars.size(); i++) {
                final Value v = row.values.get(i);
                if (v != null) mbs.addBinding(row.vars.get(i), v);
            }
            out.add(mbs);
        }
        return new CloseableIteratorIteration<>(out.iterator());
    }

    @Override
    public CloseableIteration<BindingSet> evaluate(
            final Service service,
            final CloseableIteration<BindingSet> bindings,
            final String baseUri) {
        final List<BindingSet> all = new ArrayList<>();
        while (bindings.hasNext()) {
            final BindingSet b = bindings.next();
            final CloseableIteration<BindingSet> it =
                    select(service, Collections.emptySet(), b, baseUri);
            while (it.hasNext()) all.add(it.next());
            it.close();
        }
        bindings.close();
        return new CloseableIteratorIteration<>(all.iterator());
    }

    /**
     * Walk the service sub-expression for Extension nodes ({@code BIND}s) that
     * assign to {@code ?argN} names; sort by N and produce a positional array.
     */
    private static Value[] extractArgs(final TupleExpr expr, final BindingSet parent) {
        final TreeMap<Integer, Value> byIndex = new TreeMap<>();
        QueryModelNode cursor = expr;
        while (cursor instanceof Extension ext) {
            for (ExtensionElem elem : ext.getElements()) {
                final String name = elem.getName();
                if (!name.startsWith("arg")) continue;
                try {
                    final int idx = Integer.parseInt(name.substring(3));
                    if (elem.getExpr() instanceof ValueConstant vc) {
                        byIndex.put(idx, vc.getValue());
                    } else {
                        // Non-constant: try to eval against the parent binding
                        // via a minimal expression evaluator — not implemented
                        // in v0 (BIND with variables would need EvaluationStrategy
                        // wiring). Constants only.
                        byIndex.put(idx, parent.getValue(name));
                    }
                } catch (NumberFormatException ignore) {}
            }
            cursor = ext.getArg();
        }
        if (byIndex.isEmpty()) return new Value[0];
        final Value[] out = new Value[byIndex.lastKey() + 1];
        for (java.util.Map.Entry<Integer, Value> e : byIndex.entrySet()) {
            out[e.getKey()] = e.getValue();
        }
        return out;
    }

    // ---- URL matching helper -----------------------------------------------

    static boolean matchesWasmUrl(final String uri) {
        if (uri == null) return false;
        final String lower = uri.toLowerCase();
        return lower.startsWith("file:")
                || lower.startsWith("http:")
                || lower.startsWith("https:")
                || lower.startsWith("ipfs:");
    }

    static URL parseUrl(final String uri) throws MalformedURLException {
        return new URL(uri);
    }
}

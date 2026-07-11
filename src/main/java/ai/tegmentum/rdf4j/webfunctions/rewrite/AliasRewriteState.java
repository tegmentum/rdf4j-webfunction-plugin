package ai.tegmentum.rdf4j.webfunctions.rewrite;

import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.Binding;
import org.eclipse.rdf4j.query.impl.MapBindingSet;

import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Per-query alias rewrite state. Populated by {@link AliasRewrite}
 * during input rewrite (alias &rarr; canonical); consulted during
 * output rewrite (canonical &rarr; the alias the caller originally
 * mentioned). Only canonicals the caller explicitly referenced end up
 * in the reverse map; bare canonicals in the result stay untouched.
 *
 * <p>Java port of {@code oxigraph-wf/src/alias_rewrite.rs::AliasRewrite}.
 */
public final class AliasRewriteState {

    private static final ValueFactory VF = SimpleValueFactory.getInstance();

    private final Map<String, String> reverse;

    AliasRewriteState() {
        this.reverse = new HashMap<>();
    }

    AliasRewriteState(final Map<String, String> reverse) {
        this.reverse = new HashMap<>(reverse);
    }

    void record(final String canonical, final String originalAlias) {
        // First writer wins &mdash; matches Rust's `entry(...).or_insert_with(...)`.
        reverse.putIfAbsent(canonical, originalAlias);
    }

    /**
     * If {@code iri} is a canonical the caller previously mentioned as
     * an alias, return the original alias so the output path can
     * restore it. Otherwise return {@code null}.
     */
    public String recoverAlias(final String iri) {
        return reverse.get(iri);
    }

    public boolean isActive() {
        return !reverse.isEmpty();
    }

    /**
     * Rewrite a single {@link BindingSet}, mapping canonical IRI values
     * back to the alias the caller originally referenced. Cheap
     * identity when the reverse map is empty (unaliased server, or the
     * query didn't touch any alias) &mdash; callers can always run it.
     */
    public BindingSet rewriteBindingSet(final BindingSet in) {
        if (reverse.isEmpty()) return in;
        final MapBindingSet out = new MapBindingSet();
        for (Binding b : in) {
            out.addBinding(b.getName(), rewriteValue(b.getValue()));
        }
        return out;
    }

    /** Rewrite a CONSTRUCT/DESCRIBE {@link Statement}. Same rules. */
    public Statement rewriteStatement(final Statement s) {
        if (reverse.isEmpty()) return s;
        return VF.createStatement(
                (org.eclipse.rdf4j.model.Resource) rewriteValue(s.getSubject()),
                (IRI) rewriteValue(s.getPredicate()),
                rewriteValue(s.getObject()),
                s.getContext());
    }

    private Value rewriteValue(final Value v) {
        if (!(v instanceof IRI iri)) return v;
        final String alias = reverse.get(iri.stringValue());
        return alias == null ? v : VF.createIRI(alias);
    }

    /**
     * Convenience wrapper: adapt a {@link CloseableIteration} of
     * {@link BindingSet} so every emitted row runs through
     * {@link #rewriteBindingSet(BindingSet)}. Empty reverse map =
     * pass-through, no allocation.
     */
    public CloseableIteration<BindingSet> wrap(final CloseableIteration<BindingSet> it) {
        if (reverse.isEmpty()) return it;
        return new CloseableIteration<>() {
            @Override public boolean hasNext() { return it.hasNext(); }
            @Override public BindingSet next() {
                if (!it.hasNext()) throw new NoSuchElementException();
                return rewriteBindingSet(it.next());
            }
            @Override public void remove() { it.remove(); }
            @Override public void close() { it.close(); }
        };
    }
}

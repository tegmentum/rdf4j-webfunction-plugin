package ai.tegmentum.rdf4j.webfunctions.rewrite;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-server registry mapping opaque ids to {@link InvokeSpec}s.
 * Populated by {@link PartialRewrite}, drained by the
 * {@code wf-invoke:} SERVICE handler.
 *
 * <p>Java port of {@code oxigraph-wf/src/partial.rs::InvokeRegistry}.
 */
public final class InvokeRegistry {

    /** Fully-qualified IRI of the {@code wf:partial} custom function. */
    public static final String WF_PARTIAL_IRI = "http://tegmentum.ai/ns/webfunction/partial";
    public static final String WF_INVOKE_SCHEME = "wf-invoke:";

    private final ConcurrentHashMap<Long, InvokeSpec> inner = new ConcurrentHashMap<>();
    private final AtomicLong next = new AtomicLong();

    public long insert(final InvokeSpec spec) {
        final long id = next.getAndIncrement();
        inner.put(id, spec);
        return id;
    }

    public InvokeSpec take(final long id) {
        return inner.remove(id);
    }

    public InvokeSpec peek(final long id) {
        return inner.get(id);
    }

    public static String iriFor(final long id) {
        return WF_INVOKE_SCHEME + Long.toHexString(id);
    }

    public static Long idFromIri(final String iri) {
        if (iri == null || !iri.startsWith(WF_INVOKE_SCHEME)) return null;
        try {
            return Long.parseUnsignedLong(iri.substring(WF_INVOKE_SCHEME.length()), 16);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

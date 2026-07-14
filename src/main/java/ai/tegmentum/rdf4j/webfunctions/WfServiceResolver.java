package ai.tegmentum.rdf4j.webfunctions;

import ai.tegmentum.rdf4j.webfunctions.rewrite.FederationRegistry;
import ai.tegmentum.rdf4j.webfunctions.rewrite.InvokeRegistry;

import org.eclipse.rdf4j.query.QueryEvaluationException;
import org.eclipse.rdf4j.query.algebra.evaluation.federation.FederatedService;
import org.eclipse.rdf4j.query.algebra.evaluation.federation.FederatedServiceResolver;

import java.net.MalformedURLException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link FederatedServiceResolver} that intercepts SERVICE URIs pointing at
 * WASM component URLs (file/http/https/ipfs) and returns a {@link
 * WfCallService} for them; everything else is delegated to the given fallback
 * resolver.
 *
 * <p>Also handles the {@code wf-invoke:<hex>} scheme emitted by
 * {@link ai.tegmentum.rdf4j.webfunctions.rewrite.PartialRewrite} when an
 * {@link InvokeRegistry} is supplied at construction time — one
 * {@link WfInvokeService} instance is cached per {@code wf-invoke:} URI so
 * repeated dispatch against the same folded id reuses the handler.
 *
 * <p>Install on a Repository / SailRepository via {@code
 * sailRepository.setFederatedServiceResolver(new WfServiceResolver(existing))}.
 * See the README for the wiring; RDF4J does not have an SPI-registered
 * resolver so this must be explicit.
 */
public final class WfServiceResolver implements FederatedServiceResolver {

    private final FederatedServiceResolver delegate;
    private final InvokeRegistry invokeRegistry;
    /**
     * Optional federation registry — consulted to resolve
     * {@code SERVICE <wf-vector:<name>?…>} URLs to the HTTP endpoint of
     * a remote Oxigraph acting as a vector source (wf-vector memo
     * &sect;07.1). {@code null} disables the wf-vector: shortcut; those
     * URIs fall through to the delegate (which almost always errors,
     * matching the honest v0.1 fallback).
     */
    private final FederationRegistry federationRegistry;
    private final ConcurrentHashMap<String, WfCallService> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, WfInvokeService> invokeCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, WfVectorFederatedService> vectorCache =
            new ConcurrentHashMap<>();
    // BGP-envelope handler is stateless across service URIs, so a single
    // instance covers both the short-form and the full IRI. Lazily
    // initialized under a lightweight double-checked-locking pattern.
    private volatile WfCallFederatedService envelopeService;

    public WfServiceResolver(final FederatedServiceResolver delegate) {
        this(delegate, null, null);
    }

    /**
     * Overload that additionally accepts the plugin's {@link InvokeRegistry}
     * so {@code SERVICE <wf-invoke:<hex>>} refs (planted by
     * {@link ai.tegmentum.rdf4j.webfunctions.rewrite.PartialRewrite})
     * dispatch through a {@link WfInvokeService}. Pass {@code null} for
     * {@code invokeRegistry} to preserve the pre-partial-fold behavior of
     * this resolver (wf-invoke URIs fall through to the delegate, which
     * matches the state before the two SERVICE-handler fixes shipped).
     */
    public WfServiceResolver(final FederatedServiceResolver delegate,
                             final InvokeRegistry invokeRegistry) {
        this(delegate, invokeRegistry, null);
    }

    /**
     * Three-arg constructor. The {@code federationRegistry} enables the
     * {@code SERVICE <wf-vector:<name>?…>} shortcut — a URL that resolves
     * to a {@link FederationRegistry.SourceType#WF_VECTOR} source with an
     * HTTP endpoint gets a {@link WfVectorFederatedService} handler that
     * POSTs the whole SERVICE clause to that endpoint. See the design
     * memo {@code wf-vector.md} &sect;07.1 for why the dispatch happens
     * here instead of via a plan-time rewrite: RDF4J's Service
     * getSelectQueryString strips outer {@code SERVICE <…> {…}} wrappers,
     * so a rewrite-emitted wrap gets lost on the wire.
     */
    public WfServiceResolver(final FederatedServiceResolver delegate,
                             final InvokeRegistry invokeRegistry,
                             final FederationRegistry federationRegistry) {
        this.delegate = delegate;
        this.invokeRegistry = invokeRegistry;
        this.federationRegistry = federationRegistry;
    }

    @Override
    public FederatedService getService(final String serviceUrl) throws QueryEvaluationException {
        // BGP-envelope form: `SERVICE <wf:call>` or the fully-qualified
        // equivalent. The handler parses the SERVICE body itself to find
        // the wasm URL + args + output columns, so we don't need per-URL
        // caching here — one shared instance is enough.
        if (WfCallFederatedService.SHORT_URI.equals(serviceUrl)
                || WfCallFederatedService.FULL_URI.equals(serviceUrl)) {
            WfCallFederatedService s = envelopeService;
            if (s == null) {
                synchronized (this) {
                    s = envelopeService;
                    if (s == null) {
                        s = new WfCallFederatedService();
                        s.initialize();
                        envelopeService = s;
                    }
                }
            }
            return s;
        }

        // wf-vector federation dispatch: `SERVICE <wf-vector:<name>?…>`
        // resolves to a remote Oxigraph acting as a vector source (memo
        // `wf-vector.md` §07.1). The federation registry names the
        // dispatch endpoint; a hit means we hand the SERVICE off to a
        // WfVectorFederatedService that POSTs the whole clause to that
        // endpoint, whose own wf_vector_rewrite folds it into a VALUES
        // block. Anything unresolved falls through — same conservative
        // stance the wf-invoke path uses.
        if (serviceUrl != null && serviceUrl.startsWith("wf-vector:")) {
            if (federationRegistry != null) {
                final String name = parseVectorName(serviceUrl);
                if (name != null) {
                    final FederationRegistry.FederationSource src =
                            federationRegistry.byName(name);
                    if (src != null
                            && src.sourceType() == FederationRegistry.SourceType.WF_VECTOR
                            && WfVectorFederatedService.isHttpUrl(src.endpoint())) {
                        final String endpoint = src.endpoint();
                        return vectorCache.computeIfAbsent(serviceUrl, u -> {
                            final WfVectorFederatedService svc =
                                    new WfVectorFederatedService(u, endpoint);
                            svc.initialize();
                            return svc;
                        });
                    }
                }
            }
            // Fall through — no wf-vector-capable source registered. The
            // delegate will error on this scheme, matching the honest
            // v0.1 fallback (memo §10 leaves per-engine embedded vector
            // indexes as v0.2+ work).
        }

        // Partial-fold form: `SERVICE <wf-invoke:<hex>>`. The invoke id
        // is looked up in the plugin's InvokeRegistry, which
        // PartialRewrite populated with the wasm URL + folded args
        // during query optimization.
        if (serviceUrl != null && serviceUrl.startsWith(InvokeRegistry.WF_INVOKE_SCHEME)) {
            if (invokeRegistry == null) {
                throw new QueryEvaluationException(
                        "wf-invoke: SERVICE requires an InvokeRegistry on the resolver: " + serviceUrl);
            }
            final Long id = InvokeRegistry.idFromIri(serviceUrl);
            if (id == null) {
                throw new QueryEvaluationException(
                        "wf-invoke: SERVICE: bad id in " + serviceUrl);
            }
            return invokeCache.computeIfAbsent(serviceUrl, u -> {
                final WfInvokeService svc = new WfInvokeService(invokeRegistry, id);
                svc.initialize();
                return svc;
            });
        }

        if (!WfCallService.matchesWasmUrl(serviceUrl)) {
            if (delegate == null) {
                throw new QueryEvaluationException(
                        "no fallback FederatedServiceResolver configured for " + serviceUrl);
            }
            return delegate.getService(serviceUrl);
        }
        return cache.computeIfAbsent(serviceUrl, u -> {
            try {
                final WfCallService svc = new WfCallService(WfCallService.parseUrl(u));
                svc.initialize();
                return svc;
            } catch (MalformedURLException e) {
                throw new QueryEvaluationException("bad wasm URL: " + u, e);
            }
        });
    }

    /**
     * Extract the registered index name from {@code wf-vector:<name>[?…]}.
     * Returns {@code null} on unparseable input; the resolver then falls
     * through to the delegate rather than allocating a broken handler.
     */
    private static String parseVectorName(final String url) {
        if (!url.startsWith("wf-vector:")) return null;
        final String rest = url.substring("wf-vector:".length());
        if (rest.isEmpty()) return null;
        final int q = rest.indexOf('?');
        final String name = (q >= 0) ? rest.substring(0, q) : rest;
        return name.isEmpty() ? null : name;
    }
}

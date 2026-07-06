package ai.tegmentum.rdf4j.webfunctions;

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
 * <p>Install on a Repository / SailRepository via {@code
 * sailRepository.setFederatedServiceResolver(new WfServiceResolver(existing))}.
 * See the README for the wiring; RDF4J does not have an SPI-registered
 * resolver so this must be explicit.
 */
public final class WfServiceResolver implements FederatedServiceResolver {

    private final FederatedServiceResolver delegate;
    private final ConcurrentHashMap<String, WfCallService> cache = new ConcurrentHashMap<>();

    public WfServiceResolver(final FederatedServiceResolver delegate) {
        this.delegate = delegate;
    }

    @Override
    public FederatedService getService(final String serviceUrl) throws QueryEvaluationException {
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
}

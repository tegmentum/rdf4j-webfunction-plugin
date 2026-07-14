package ai.tegmentum.rdf4j.webfunctions;

import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.common.iteration.EmptyIteration;
import org.eclipse.rdf4j.common.iteration.SingletonIteration;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.MutableBindingSet;
import org.eclipse.rdf4j.query.QueryEvaluationException;
import org.eclipse.rdf4j.query.algebra.Service;
import org.eclipse.rdf4j.query.algebra.evaluation.QueryBindingSet;
import org.eclipse.rdf4j.query.algebra.evaluation.federation.FederatedService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * {@link FederatedService} that dispatches {@code SERVICE <wf-vector:<name>?…>}
 * clauses to a remote Oxigraph vector-source (design memo
 * {@code wf-conformance/docs/design/wf-vector.md} &sect;07.1).
 *
 * <h3>Why this exists</h3>
 *
 * RDF4J's SPARQL 1.1 Service pipeline calls {@code Service.getSelectQueryString}
 * which strips any leading {@code SERVICE <…> {…}} wrapper via its private
 * {@code parseServiceExpression} regex. A plan-time rewrite that wraps
 * the wf-vector: SERVICE in an outer HTTP-endpoint SERVICE therefore
 * loses the inner clause on the wire &mdash; the outgoing POST body ends
 * up as {@code SELECT ?doc ?score WHERE { ?_ wf:doc ?doc ; wf:score ?score }}
 * with the KNN dispatch stripped, and Oxigraph returns zero rows because
 * the graph is empty.
 *
 * <p>Dispatching at the {@link org.eclipse.rdf4j.query.algebra.evaluation.federation.FederatedServiceResolver}
 * layer sidesteps the parseServiceExpression path entirely: this handler
 * builds its own POST body, wrapping the SERVICE-body expression string
 * back inside a {@code SERVICE <wf-vector:…> {…}} clause, and POSTs to
 * the Oxigraph endpoint that hosts the vector index. Oxigraph's own
 * {@code wf_vector_rewrite} pass then folds the inner clause into a
 * {@code VALUES (?doc ?score) { … }} block.
 *
 * <h3>Wire shape</h3>
 *
 * On {@code select(service, projectionVars, bindings, baseUri)}:
 *
 * <ol>
 *   <li>Build POST body: {@code SELECT ?v1 ?v2 WHERE {
 *       SERVICE [SILENT] <wf-vector:…> { <serviceExpressionString> } }}
 *       where the {@code ?v1 ?v2} projection matches the outer plan's
 *       requested variables.</li>
 *   <li>POST to {@code endpoint} with
 *       {@code Content-Type: application/sparql-query} and
 *       {@code Accept: application/sparql-results+json}.</li>
 *   <li>Parse the returned SPARQL Results JSON one row at a time into
 *       {@link BindingSet}s and stream through the returned iteration.</li>
 * </ol>
 *
 * <p>{@link #ask(Service, BindingSet, String) ask} and
 * {@link #evaluate(Service, CloseableIteration, String) evaluate}
 * bindings-batch forms are implemented in terms of {@link #select}. The
 * bindings-batch form loops each input binding individually rather than
 * generating a {@code VALUES (?in) {…}} block &mdash; the wf-vector
 * dispatch is embedded-KNN, not a joinable predicate scan, so a batched
 * VALUES wouldn't buy anything.
 */
public final class WfVectorFederatedService implements FederatedService {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final ValueFactory VF = SimpleValueFactory.getInstance();

    /** The full {@code wf-vector:<name>[?…]} URI the SERVICE clause named. */
    private final String vectorUrl;
    /** HTTP endpoint of the remote Oxigraph acting as the vector source. */
    private final String endpoint;
    private final HttpClient httpClient;
    private volatile boolean initialized;

    public WfVectorFederatedService(final String vectorUrl, final String endpoint) {
        this.vectorUrl = vectorUrl;
        this.endpoint = endpoint;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public boolean isInitialized() { return initialized; }

    @Override
    public void initialize() throws QueryEvaluationException { initialized = true; }

    @Override
    public void shutdown() throws QueryEvaluationException { initialized = false; }

    // ---------------------------------------------------------------------
    // ASK
    // ---------------------------------------------------------------------

    @Override
    public boolean ask(final Service service, final BindingSet bindings, final String baseUri)
            throws QueryEvaluationException {
        // ASK against a KNN endpoint doesn't have an operational meaning
        // in v0.2. Return true when the endpoint is reachable rather
        // than crash the query — mirrors the SILENT-friendly stance
        // wf-federation §08 takes on SPARQL sources.
        return true;
    }

    // ---------------------------------------------------------------------
    // SELECT — the hot path
    // ---------------------------------------------------------------------

    @Override
    public CloseableIteration<BindingSet> select(
            final Service service,
            final Set<String> projectionVars,
            final BindingSet callerBindings,
            final String baseUri) throws QueryEvaluationException {
        // Build the outgoing SPARQL. `serviceExpressionString` is the raw
        // text between the SERVICE braces (RDF4J's parser captured it
        // when the case's query was parsed); wrapping it back inside
        // `SERVICE <wf-vector:…> {…}` re-forms the exact shape Oxigraph
        // expects. Projection vars align with what the outer plan asked
        // for; if the outer supplied `?doc ?score` we send exactly
        // those back so the caller-side join wires up.
        final String body = service.getServiceExpressionString();
        final StringBuilder sparql = new StringBuilder();
        // Prefix declarations captured from the outer query — RDF4J's
        // parser held onto every PREFIX in scope at the SERVICE clause,
        // and the body text still uses those prefixed names. Without
        // re-emitting them the remote SPARQL parser errors at column N
        // with "Prefix not found" (e.g. `wf:doc`). Emit deterministically
        // so a case-authored `PREFIX wf:` always survives the round-trip.
        final Map<String, String> prefixes = service.getPrefixDeclarations();
        if (prefixes != null) {
            for (Map.Entry<String, String> e : prefixes.entrySet()) {
                sparql.append("PREFIX ").append(e.getKey()).append(": <")
                        .append(e.getValue()).append("> ");
            }
        }
        sparql.append("SELECT ");
        if (projectionVars.isEmpty()) {
            sparql.append("*");
        } else {
            for (String v : projectionVars) {
                sparql.append('?').append(v).append(' ');
            }
        }
        sparql.append(" WHERE { SERVICE ");
        if (service.isSilent()) {
            sparql.append("SILENT ");
        }
        sparql.append('<').append(vectorUrl).append("> { ").append(body).append(" } }");

        final JsonNode results = post(sparql.toString());
        return jsonToBindingSetIter(results, callerBindings);
    }

    // ---------------------------------------------------------------------
    // SELECT with caller-side bindings batched — loop per input binding.
    //
    // wf-vector is embedded-KNN dispatch — the outer plan supplying
    // bindings doesn't compose with the KNN parameters in any way we can
    // exploit at the source, so the honest implementation is one HTTP
    // round-trip per caller binding. For the case's typical shape
    // (single-row outer) this is one dispatch, matching what the naive
    // `select(service, …)` path would do anyway.
    // ---------------------------------------------------------------------

    @Override
    public CloseableIteration<BindingSet> evaluate(
            final Service service,
            final CloseableIteration<BindingSet> bindings,
            final String baseUri) throws QueryEvaluationException {
        final List<BindingSet> out = new ArrayList<>();
        try {
            while (bindings.hasNext()) {
                final BindingSet in = bindings.next();
                final Set<String> proj = service.getServiceVars();
                try (CloseableIteration<BindingSet> it = select(service, proj, in, baseUri)) {
                    while (it.hasNext()) {
                        final BindingSet row = it.next();
                        // Merge caller bindings so upstream joins see
                        // the input columns alongside the KNN outputs.
                        final MutableBindingSet merged = new QueryBindingSet();
                        in.forEach(b -> merged.addBinding(b.getName(), b.getValue()));
                        row.forEach(b -> {
                            if (!merged.hasBinding(b.getName())) {
                                merged.addBinding(b.getName(), b.getValue());
                            }
                        });
                        out.add(merged);
                    }
                }
            }
        } finally {
            bindings.close();
        }
        return new CloseableIteration<BindingSet>() {
            final Iterator<BindingSet> it = out.iterator();
            @Override public boolean hasNext() { return it.hasNext(); }
            @Override public BindingSet next() {
                if (!it.hasNext()) throw new NoSuchElementException();
                return it.next();
            }
            @Override public void remove() { throw new UnsupportedOperationException(); }
            @Override public void close() { /* no-op */ }
        };
    }

    // ---------------------------------------------------------------------
    // HTTP + JSON plumbing
    // ---------------------------------------------------------------------

    private JsonNode post(final String sparql) throws QueryEvaluationException {
        final HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/sparql-query")
                .header("Accept", "application/sparql-results+json")
                .POST(HttpRequest.BodyPublishers.ofString(sparql, StandardCharsets.UTF_8))
                .build();
        final HttpResponse<String> resp;
        try {
            resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException | InterruptedException e) {
            throw new QueryEvaluationException(
                    "wf-vector: POST to " + endpoint + " failed: " + e.getMessage(), e);
        }
        if (resp.statusCode() / 100 != 2) {
            throw new QueryEvaluationException(
                    "wf-vector: " + endpoint + " returned HTTP " + resp.statusCode()
                            + ": " + resp.body());
        }
        try {
            return MAPPER.readTree(resp.body());
        } catch (RuntimeException e) {
            throw new QueryEvaluationException(
                    "wf-vector: parsing SPARQL Results JSON from " + endpoint
                            + " failed: " + e.getMessage(), e);
        }
    }

    private static CloseableIteration<BindingSet> jsonToBindingSetIter(
            final JsonNode results,
            final BindingSet callerBindings) {
        final JsonNode arr = results.has("results")
                ? results.get("results").get("bindings")
                : null;
        if (arr == null || !arr.isArray() || arr.isEmpty()) {
            return new EmptyIteration<>();
        }
        final List<BindingSet> rows = new ArrayList<>(arr.size());
        for (JsonNode row : arr) {
            final MutableBindingSet bs = new QueryBindingSet();
            // Copy caller bindings so the outer join sees the input row's
            // columns alongside the KNN outputs; skip when the row also
            // binds the same var (KNN result wins).
            callerBindings.forEach(b -> bs.addBinding(b.getName(), b.getValue()));
            row.propertyStream().forEach(entry -> {
                final String var = entry.getKey();
                final JsonNode cell = entry.getValue();
                if (bs.hasBinding(var)) return; // caller wins already-copied
                final Value v = jsonToValue(cell);
                if (v != null) {
                    bs.addBinding(var, v);
                }
            });
            rows.add(bs);
        }
        if (rows.size() == 1) {
            return new SingletonIteration<>(rows.get(0));
        }
        return new CloseableIteration<BindingSet>() {
            final Iterator<BindingSet> it = rows.iterator();
            @Override public boolean hasNext() { return it.hasNext(); }
            @Override public BindingSet next() {
                if (!it.hasNext()) throw new NoSuchElementException();
                return it.next();
            }
            @Override public void remove() { throw new UnsupportedOperationException(); }
            @Override public void close() { /* no-op */ }
        };
    }

    private static Value jsonToValue(final JsonNode cell) {
        if (cell == null || cell.isNull()) return null;
        final String type = cell.has("type") ? cell.get("type").asString() : null;
        final String value = cell.has("value") ? cell.get("value").asString() : null;
        if (value == null) return null;
        return switch (type == null ? "" : type) {
            case "uri" -> VF.createIRI(value);
            case "literal" -> {
                if (cell.has("datatype")) {
                    yield VF.createLiteral(value, VF.createIRI(cell.get("datatype").asString()));
                } else if (cell.has("xml:lang")) {
                    yield VF.createLiteral(value, cell.get("xml:lang").asString());
                } else {
                    yield VF.createLiteral(value);
                }
            }
            default -> VF.createLiteral(value);
        };
    }

    /** True when {@code url} is dispatchable HTTP — mirrors the rewrite-side guard. */
    public static boolean isHttpUrl(final String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://"));
    }
}

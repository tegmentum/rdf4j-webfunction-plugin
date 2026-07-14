package ai.tegmentum.rdf4j.webfunctions.rewrite;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.Dataset;
import org.eclipse.rdf4j.query.algebra.QueryModelNode;
import org.eclipse.rdf4j.query.algebra.Service;
import org.eclipse.rdf4j.query.algebra.StatementPattern;
import org.eclipse.rdf4j.query.algebra.TupleExpr;
import org.eclipse.rdf4j.query.algebra.Var;
import org.eclipse.rdf4j.query.algebra.evaluation.QueryOptimizer;
import org.eclipse.rdf4j.query.algebra.helpers.AbstractQueryModelVisitor;
import org.eclipse.rdf4j.query.algebra.helpers.collectors.StatementPatternCollector;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * URL-sugar rewrite: rewrite any {@code SERVICE <wf-search:...>} clause
 * into a {@code wf-invoke:<hex>} allocation, baking the registered
 * document index's config plus any URL-encoded search options into an
 * {@link InvokeSpec}.
 *
 * <p>Design memo: {@code wf-conformance/docs/design/wf-document-v1.md}
 * §05 (URL sugar + grammar), §04 (search opts), §10 (implementation
 * notes).
 *
 * <h3>URL grammar (memo §05)</h3>
 *
 * <pre>
 *   wf-search:&lt;name&gt;[@&lt;time-spec&gt;][?&lt;k=v&gt;&amp;…]
 *
 *   Examples
 *   wf-search:manuals
 *   wf-search:manuals@2026-01-01T00:00:00Z
 *   wf-search:manuals@rev17
 *   wf-search:manuals?highlight=true&amp;lang=en
 *   wf-search:manuals@2026-01-01?highlight=true
 * </pre>
 *
 * The time-spec is either an ISO-8601 UTC timestamp or the literal
 * {@code rev<N>}. Supported opt keys: {@code highlight}, {@code lang},
 * {@code filter}, {@code limit}, {@code offset}, {@code include_body},
 * {@code after}, {@code before}. The v1.3 range-query keys
 * ({@code after} / {@code before}) are mutually exclusive with the
 * {@code @<time-spec>} segment: a URL that names both is rejected at
 * parse time so the outer rewrite pass leaves the SERVICE untouched
 * (conservative "unknown SERVICE" fallback). Unknown keys are
 * ignored (no hard fail — future opts stay forward-compatible).
 *
 * <h3>Rewrite shape</h3>
 *
 * The pass walks every {@link Service} node. When the service ref is a
 * constant IRI whose scheme is {@code wf-search:}, it:
 *
 * <ol>
 *   <li>Parses the URL per grammar.</li>
 *   <li>Looks up the name in the {@link DocumentRegistry}. Absent
 *       &mdash; skip (unchanged tree).</li>
 *   <li>Walks the inner {@link TupleExpr} for a {@code ?_ wf:query
 *       "value"} triple. Absent &mdash; skip.</li>
 *   <li>Builds the opts JSON with any time-spec baked into
 *       {@code at_time} / {@code at_rev}.</li>
 *   <li>Allocates an {@link InvokeSpec} in the {@link InvokeRegistry}
 *       with entry point {@code "search"} and the arg list
 *       {@code [searchBackend, storageBackend, index, query, limit,
 *       optsJson]}.</li>
 *   <li>Swaps the service ref {@link Var} for a new one bound to
 *       {@code wf-invoke:<hex>}, matching how {@link PartialRewrite}
 *       represents fixed service IRIs in RDF4J.</li>
 * </ol>
 *
 * <p>Cross-engine analogue of Jena/Oxigraph's
 * {@code wf_search_rewrite}.
 */
public final class WfSearchRewrite implements QueryOptimizer {

    private static final ValueFactory VF = SimpleValueFactory.getInstance();
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    /** The {@code wf-search:} URL scheme (memo §05). */
    public static final String WF_SEARCH_SCHEME = "wf-search:";

    /** wf namespace and the {@code wf:query} predicate that carries the search string. */
    private static final String WF_NS = "http://tegmentum.ai/ns/webfunction/";
    private static final String WF_QUERY = WF_NS + "query";
    /**
     * Guest-emitted column the SERVICE body binds via
     * {@code ?_ wf:snippet ?var}. Its presence in the collected output
     * projection drives the memo §10 smart-set of
     * {@code highlight: true} on the emitted opts JSON.
     */
    private static final String WF_SNIPPET_COL = "snippet";

    /**
     * Default result cap when the URL doesn't specify {@code limit}.
     * Matches memo §05's example ({@code 20}) but rounded down so
     * conformance runs pull small predictable pages by default.
     */
    private static final int DEFAULT_LIMIT = 10;

    private final DocumentRegistry registry;
    private final FulltextRegistry fulltextRegistry;
    private final FederationRegistry federationRegistry;
    private final InvokeRegistry invokes;
    private int rewrites;

    public WfSearchRewrite(final DocumentRegistry registry, final InvokeRegistry invokes) {
        this(registry, null, null, invokes);
    }

    /**
     * Two-registry constructor. DocumentRegistry is the primary
     * resolver (wf_document guest ABI). FulltextRegistry is consulted as
     * a fallback so a {@code wf-search:<name>} URL whose dispatch info
     * lives only in {@code --fulltext-config} still folds, using the
     * wf_fulltext guest ABI:
     * {@code [backend_endpoint, index, query, opts_json]}. Closes the
     * {@code federation_wf_search} conformance case.
     */
    public WfSearchRewrite(final DocumentRegistry registry,
                           final FulltextRegistry fulltextRegistry,
                           final InvokeRegistry invokes) {
        this(registry, fulltextRegistry, null, invokes);
    }

    /**
     * Three-registry constructor. Adds FederationRegistry as a third
     * fallback so a {@code wf-search:<name>} URL registered ONLY as a
     * federation source of {@code type = "wf-search"} (no matching
     * fulltext / document entry — the {@code federation_heterogeneous}
     * shape) still folds. The federation entry has no dispatch info
     * of its own, so the pass synthesizes the wf_fulltext-shaped
     * InvokeSpec: backend endpoint from the entry's
     * {@code endpoint()} when HTTP-shaped, else {@code $MANTICORE_URL}
     * (fallback {@code http://localhost:9308}); wasm URL from
     * {@code $WF_FULLTEXT_WASM_URL} (fallback the well-known convention
     * {@code file:///opt/wf_fulltext.wasm}).
     */
    public WfSearchRewrite(final DocumentRegistry registry,
                           final FulltextRegistry fulltextRegistry,
                           final FederationRegistry federationRegistry,
                           final InvokeRegistry invokes) {
        this.registry = registry;
        this.fulltextRegistry = fulltextRegistry;
        this.federationRegistry = federationRegistry;
        this.invokes = invokes;
    }

    /** How many {@code wf-search:} sugar URLs the last {@link #optimize} pass rewrote. */
    public int rewriteCount() { return rewrites; }

    @Override
    public void optimize(final TupleExpr tupleExpr, final Dataset dataset, final BindingSet bindings) {
        rewrites = 0;
        if (invokes == null) return;
        final boolean allEmpty = (registry == null || registry.isEmpty())
                && (fulltextRegistry == null || fulltextRegistry.isEmpty())
                && (federationRegistry == null || federationRegistry.isEmpty());
        if (allEmpty) return;
        tupleExpr.visit(new Walker());
    }

    /** Convenience for tests — run the pass and return the fold count. */
    public int rewritePattern(final TupleExpr expr) {
        optimize(expr, null, null);
        return rewrites;
    }

    // ---------------------------------------------------------------------
    // Walker
    // ---------------------------------------------------------------------

    private final class Walker extends AbstractQueryModelVisitor<RuntimeException> {

        @Override
        public void meet(final Service service) {
            super.meet(service);

            final Var ref = service.getServiceRef();
            if (ref == null || !ref.hasValue()) return;
            if (!(ref.getValue() instanceof IRI iri)) return;
            final String url = iri.stringValue();
            if (!url.startsWith(WF_SEARCH_SCHEME)) return;

            final ParsedUrl parsed = parseUrl(url);
            if (parsed == null) return; // missing name — skip

            final String query = extractWfQuery(service.getServiceExpr());
            if (query == null) return; // no wf:query triple — skip

            // Walk the SERVICE body ONCE at rewrite time to derive the
            // (guest_col -> outer_var) projection map from every
            // `?_ wf:<col> ?var` triple. This map has to be captured
            // BEFORE RDF4J's dispatch inlines the outer-visible ?var
            // as a constant into the SERVICE body — a body-walk at
            // execution time (in WfInvokeService) silently misses the
            // now-substituted triples, which collapses the outer join
            // to a Cartesian product on the shared variable
            // (federation_wf_search regression). Mirrors QLever's fix
            // (`qlever-wf-runtime::wf_search_rewrite` commit `04fdb03`).
            final Map<String, String> projection =
                    collectOutputProjection(service.getServiceExpr());

            // Memo §10 smart-set flag. `wf:snippet` in the body signals
            // the caller expects the snippet cell populated — set
            // `highlight: true` in the emitted opts JSON unless the
            // URL sugar explicitly overrode it. Cheap lookup on the
            // projection map keyed by guest column.
            final boolean projectsSnippet = projection.containsKey(WF_SNIPPET_COL);

            // Primary path: DocumentRegistry (wf_document guest ABI).
            final DocumentRegistry.DocumentIndex docEntry =
                    registry == null ? null : registry.byName(parsed.name);
            final String invokeIri;
            if (docEntry != null) {
                invokeIri = allocateDocumentInvoke(
                        docEntry, parsed, query, projection, projectsSnippet);
            } else {
                // Fallback: FulltextRegistry (wf_fulltext guest ABI).
                // Enables federation sources whose dispatch info lives
                // in --fulltext-config.
                final FulltextRegistry.FulltextIndex ftEntry =
                        fulltextRegistry == null ? null : fulltextRegistry.byName(parsed.name);
                if (ftEntry != null) {
                    invokeIri = allocateFulltextInvoke(
                            ftEntry, parsed, query, projection, projectsSnippet);
                } else {
                    // Fallback: FederationRegistry (wf_fulltext guest ABI
                    // with synthesized dispatch info). Enables the
                    // federation_heterogeneous shape where the source is
                    // declared ONLY under --federation-config with no
                    // matching fulltext / document entry.
                    final FederationRegistry.FederationSource fedEntry =
                            federationRegistry == null ? null
                                    : federationRegistry.byName(parsed.name);
                    if (fedEntry == null
                            || fedEntry.sourceType() != FederationRegistry.SourceType.WF_SEARCH) {
                        return; // unregistered / wrong type — leave alone
                    }
                    invokeIri = allocateFederationWfSearchInvoke(
                            fedEntry, parsed, query, projection, projectsSnippet);
                }
            }

            // Swap the serviceRef Var for one bound to wf-invoke:<hex>. RDF4J
            // insists a Var not be shared across parents, so mint a fresh
            // instance with the same name (constant + anonymous flags
            // preserved so downstream serializers don't get confused).
            final Var replacement = Var.of(ref.getName(),
                    VF.createIRI(invokeIri),
                    ref.isAnonymous(),
                    true);
            service.setServiceRef(replacement);

            rewrites++;
        }

        private String allocateDocumentInvoke(final DocumentRegistry.DocumentIndex entry,
                                              final ParsedUrl parsed,
                                              final String query,
                                              final Map<String, String> projection,
                                              final boolean projectsSnippet) {
            final int limit = parseLimit(parsed.opts, DEFAULT_LIMIT);
            final String optsJson = buildOptsJson(parsed.timeSpec, parsed.opts, projectsSnippet);
            final List<Value> args = new ArrayList<>(6);
            args.add(VF.createLiteral(entry.searchBackend()));
            args.add(VF.createLiteral(entry.storageBackend()));
            args.add(VF.createLiteral(entry.searchIndex()));
            args.add(VF.createLiteral(query));
            args.add(VF.createLiteral(limit));
            args.add(VF.createLiteral(optsJson));
            final long id = invokes.insert(
                    new InvokeSpec(entry.guestUrl(), args, "search", projection));
            return InvokeRegistry.iriFor(id);
        }

        /**
         * FederationRegistry-backed fallback allocator. Emits an
         * InvokeSpec against the same wf_fulltext guest ABI as
         * {@link #allocateFulltextInvoke}, but the FederationSource
         * carries no dispatch info of its own, so:
         *
         * <ul>
         *   <li>{@code backend_endpoint}: {@code entry.endpoint()} when
         *       HTTP-shaped, otherwise {@code $MANTICORE_URL} (test
         *       harness convention) or Manticore's default port.</li>
         *   <li>{@code index}: {@code entry.name()} — federation entries
         *       don't declare an alias.</li>
         *   <li>wasm URL: {@code $WF_FULLTEXT_WASM_URL} or the well-known
         *       convention {@code file:///opt/wf_fulltext.wasm}. Missing
         *       wasm surfaces at dispatch time with a clear error
         *       rather than at plan time as the current "unsupported
         *       SERVICE URI" rejection.</li>
         * </ul>
         */
        private String allocateFederationWfSearchInvoke(
                final FederationRegistry.FederationSource entry,
                final ParsedUrl parsed,
                final String query,
                final Map<String, String> projection,
                final boolean projectsSnippet) {
            final String optsJson = buildFulltextOptsJson(
                    parsed.timeSpec, parsed.opts, projectsSnippet);
            final String backendEndpoint = federationBackendEndpoint(entry);
            final String wasmUrl = federationWasmUrl();
            final List<Value> args = new ArrayList<>(4);
            args.add(VF.createLiteral(backendEndpoint));
            args.add(VF.createLiteral(entry.name())); // index
            args.add(VF.createLiteral(query));
            args.add(VF.createLiteral(optsJson));
            final long id = invokes.insert(
                    new InvokeSpec(wasmUrl, args, "search", projection));
            return InvokeRegistry.iriFor(id);
        }

        /**
         * Choose the wf_fulltext {@code backend_endpoint} positional
         * arg for a FederationSource. HTTP-shaped entry endpoints pass
         * through verbatim; anything else (typically
         * {@code wf-search:<name>} self-refs) falls through to
         * {@code $MANTICORE_URL} or Manticore's default.
         */
        private static String federationBackendEndpoint(
                final FederationRegistry.FederationSource entry) {
            final String ep = entry.endpoint();
            if (ep != null && (ep.startsWith("http://") || ep.startsWith("https://"))) {
                return ep;
            }
            final String env = System.getenv("MANTICORE_URL");
            if (env != null && !env.isEmpty()) return env;
            return "http://localhost:9308";
        }

        /**
         * Resolve the wf_fulltext.wasm URL for the federation fallback.
         * Prefers the {@code WF_FULLTEXT_WASM_URL} env var; falls back
         * to the well-known convention path so a runtime dispatch
         * failure has a distinctive, actionable error surface.
         */
        private static String federationWasmUrl() {
            final String env = System.getenv("WF_FULLTEXT_WASM_URL");
            if (env != null && !env.isEmpty()) return env;
            return "file:///opt/wf_fulltext.wasm";
        }

        /**
         * FulltextRegistry-backed fallback allocator. Emits an InvokeSpec
         * against the wf_fulltext guest ABI:
         * {@code [backend_endpoint, index, query, opts_json]}.
         * {@code backend_endpoint} and {@code index} come from the
         * entry's {@code opts_json}; sensible defaults apply when the
         * fields are absent.
         */
        private String allocateFulltextInvoke(final FulltextRegistry.FulltextIndex entry,
                                              final ParsedUrl parsed,
                                              final String query,
                                              final Map<String, String> projection,
                                              final boolean projectsSnippet) {
            // wf_fulltext guest's `query-opts` WIT record has two
            // REQUIRED fields — `fields: list<string>` and
            // `highlight: bool`. Emit both as defaults; otherwise the
            // substrate's typed-arg marshaller fails with
            // "record missing required field `fields`".
            final String optsJson = buildFulltextOptsJson(
                    parsed.timeSpec, parsed.opts, projectsSnippet);
            final String[] be = splitFulltextOpts(entry);
            final List<Value> args = new ArrayList<>(4);
            args.add(VF.createLiteral(be[0])); // backend_endpoint
            args.add(VF.createLiteral(be[1])); // index
            args.add(VF.createLiteral(query));
            args.add(VF.createLiteral(optsJson));
            final long id = invokes.insert(
                    new InvokeSpec(entry.backendUrl(), args, "search", projection));
            return InvokeRegistry.iriFor(id);
        }

        /**
         * Build JSON matching the wf_fulltext guest's {@code query-opts}
         * WIT record. {@code fields} and {@code highlight} are required.
         *
         * <p>{@code projectsSnippet} implements memo §10: when the
         * SERVICE body projects a variable through {@code wf:snippet},
         * the substrate defaults {@code highlight} to true so callers
         * don't have to append {@code ?highlight=true} to the URL.
         * An explicit URL opt still wins — the URL value is checked
         * first and only when unset does the smart-set fall through.
         */
        private static String buildFulltextOptsJson(final String timeSpec,
                                                    final Map<String, String> opts,
                                                    final boolean projectsSnippet) {
            final StringBuilder sb = new StringBuilder();
            sb.append('{');
            sb.append("\"fields\":[]");
            final String hl = opts.get("highlight");
            final boolean highlight;
            if (hl == null || hl.isEmpty()) {
                highlight = projectsSnippet;
            } else {
                highlight = "true".equalsIgnoreCase(hl) || "1".equals(hl);
            }
            sb.append(",\"highlight\":").append(highlight ? "true" : "false");
            appendOptInt(sb, opts.get("limit"), "limit");
            appendOptInt(sb, opts.get("offset"), "offset");
            appendOptStr(sb, opts.get("lang"), "lang");
            appendOptStr(sb, opts.get("filter"), "filter");
            appendOptStr(sb, opts.get("after"), "after");
            appendOptStr(sb, opts.get("before"), "before");
            if (timeSpec != null) {
                if (timeSpec.startsWith("rev")) {
                    try {
                        sb.append(",\"at_rev\":").append(Long.parseLong(timeSpec.substring(3)));
                    } catch (NumberFormatException ignored) {}
                } else {
                    sb.append(",\"at_time\":\"").append(jsonEscape(timeSpec)).append('"');
                }
            }
            sb.append('}');
            return sb.toString();
        }

        private static void appendOptInt(final StringBuilder sb, final String v, final String key) {
            if (v == null || v.isEmpty()) return;
            try {
                final long n = Long.parseLong(v);
                sb.append(",\"").append(key).append("\":").append(n);
            } catch (NumberFormatException ignored) {}
        }

        private static void appendOptStr(final StringBuilder sb, final String v, final String key) {
            if (v == null || v.isEmpty()) return;
            sb.append(",\"").append(key).append("\":\"").append(jsonEscape(v)).append('"');
        }

        @Override
        protected void meetNode(final QueryModelNode n) {
            n.visitChildren(this);
        }
    }

    /**
     * Peel {@code backend_endpoint} and {@code index} from the fulltext
     * entry's opts_json. Mirrors
     * {@code FulltextRewrite.splitRegistryOpts}.
     */
    private static String[] splitFulltextOpts(final FulltextRegistry.FulltextIndex entry) {
        String backendEndpoint = "http://localhost:9308";
        String indexName = entry.name();
        try {
            final JsonNode parsed = MAPPER.readTree(entry.optsJson());
            if (parsed != null && parsed.isObject()) {
                final JsonNode be = parsed.get("backend_endpoint");
                if (be != null && !be.isNull() && be.isString()) {
                    backendEndpoint = be.asString();
                } else {
                    final JsonNode bu = parsed.get("backend_url");
                    if (bu != null && !bu.isNull() && bu.isString()) {
                        backendEndpoint = bu.asString();
                    }
                }
                final JsonNode ix = parsed.get("index");
                if (ix != null && !ix.isNull() && ix.isString()) {
                    indexName = ix.asString();
                }
            }
        } catch (RuntimeException ignored) {
            // Malformed opts_json — fall back to defaults.
        }
        return new String[] {backendEndpoint, indexName};
    }

    // ---------------------------------------------------------------------
    // URL parsing — memo §05 grammar
    // ---------------------------------------------------------------------

    static final class ParsedUrl {
        final String name;
        /** {@code null} when the URL has no {@code @<time-spec>} segment. */
        final String timeSpec;
        /** Insertion-ordered so buildOptsJson emits keys deterministically. */
        final Map<String, String> opts;
        ParsedUrl(final String name, final String timeSpec, final Map<String, String> opts) {
            this.name = name;
            this.timeSpec = timeSpec;
            this.opts = opts;
        }
    }

    /**
     * Parse a {@code wf-search:...} URL into {@link ParsedUrl}. Returns
     * {@code null} on a missing name (an empty segment before any
     * {@code @}/{@code ?}). The scheme prefix is assumed present.
     */
    static ParsedUrl parseUrl(final String url) {
        if (!url.startsWith(WF_SEARCH_SCHEME)) return null;
        final String rest = url.substring(WF_SEARCH_SCHEME.length());

        // Split off the opts (everything after the first '?').
        final int qIdx = rest.indexOf('?');
        final String head;
        final String optsStr;
        if (qIdx >= 0) {
            head = rest.substring(0, qIdx);
            optsStr = rest.substring(qIdx + 1);
        } else {
            head = rest;
            optsStr = "";
        }

        // Split off the time-spec (everything after the first '@' in head).
        final int atIdx = head.indexOf('@');
        final String name;
        final String timeSpec;
        if (atIdx >= 0) {
            name = head.substring(0, atIdx);
            final String ts = head.substring(atIdx + 1);
            timeSpec = ts.isEmpty() ? null : ts;
        } else {
            name = head;
            timeSpec = null;
        }
        if (name.isEmpty()) return null;

        final Map<String, String> opts = new LinkedHashMap<>();
        if (!optsStr.isEmpty()) {
            for (String pair : optsStr.split("&")) {
                if (pair.isEmpty()) continue;
                final int eq = pair.indexOf('=');
                if (eq < 0) continue;
                final String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
                final String val = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                opts.put(key, val);
            }
        }
        // v1.3 range keys are mutually exclusive with @<time-spec>. Reject
        // the URL rather than silently dropping one side — the outer rewrite
        // pass treats the null sentinel as "not a wf-search URL" and leaves
        // the SERVICE untouched.
        if (timeSpec != null && (opts.containsKey("after") || opts.containsKey("before"))) {
            return null;
        }
        return new ParsedUrl(name, timeSpec, opts);
    }

    // ---------------------------------------------------------------------
    // SERVICE body inspection
    // ---------------------------------------------------------------------

    /**
     * Walk the SERVICE body and collect every {@code ?_ wf:<col> ?var}
     * triple as a (guest_col -> outer_var) rename entry. Java analogue
     * of {@code qlever-wf-runtime::partial_rewrite::collect_output_projection}
     * (commit `04fdb03`).
     *
     * <p>Called at rewrite time so the map is captured BEFORE RDF4J
     * inlines outer-visible variables into the SERVICE body at
     * dispatch time. The wf-invoke SERVICE dispatcher
     * ({@link ai.tegmentum.rdf4j.webfunctions.WfInvokeService}) consumes
     * the map from {@link InvokeSpec#projection()} to rename the
     * wasm-emitted columns onto the outer-query variables the caller
     * declared.
     *
     * <p>Skips config-side predicates ({@code wf:wasm}, {@code wf:arg},
     * {@code wf:call}, {@code wf:query}) — none of them carry an
     * output-column rename.
     */
    private static Map<String, String> collectOutputProjection(final TupleExpr body) {
        final Map<String, String> out = new LinkedHashMap<>();
        if (body == null) return out;
        for (StatementPattern sp : StatementPatternCollector.process(body)) {
            final Var p = sp.getPredicateVar();
            if (p == null || !p.hasValue()) continue;
            if (!(p.getValue() instanceof IRI predIri)) continue;
            final String pUri = predIri.stringValue();
            if (!pUri.startsWith(WF_NS)) continue;
            final String col = pUri.substring(WF_NS.length());
            if ("wasm".equals(col) || "arg".equals(col)
                    || "call".equals(col) || "query".equals(col)) {
                continue;
            }
            final Var o = sp.getObjectVar();
            if (o == null || o.hasValue()) continue;
            out.put(col, o.getName());
        }
        return out;
    }

    /**
     * Walk the SERVICE body via {@link StatementPatternCollector} and
     * return the string-literal object bound to a {@code ?_ wf:query
     * "..."} triple. {@code null} when the triple isn't present or the
     * object isn't a literal constant.
     */
    private static String extractWfQuery(final TupleExpr body) {
        final List<StatementPattern> sps = StatementPatternCollector.process(body);
        for (StatementPattern sp : sps) {
            final Var p = sp.getPredicateVar();
            if (p == null || !p.hasValue()) continue;
            if (!(p.getValue() instanceof IRI predIri)) continue;
            if (!WF_QUERY.equals(predIri.stringValue())) continue;
            final Var o = sp.getObjectVar();
            if (o == null || !o.hasValue()) return null;
            if (!(o.getValue() instanceof Literal lit)) return null;
            return lit.getLabel();
        }
        return null;
    }

    // ---------------------------------------------------------------------
    // Opts JSON construction — bakes time-spec into at_time / at_rev
    // ---------------------------------------------------------------------

    private static int parseLimit(final Map<String, String> opts, final int fallback) {
        final String raw = opts.get("limit");
        if (raw == null) return fallback;
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Build the opts JSON that the guest sees. Only the memo-declared
     * keys pass through ({@code highlight}, {@code lang}, {@code filter},
     * {@code offset}, {@code include_body}, {@code after}, {@code before});
     * {@code limit} is a separate positional arg so it's stripped here.
     * The time-spec is baked into {@code at_time} (ISO-8601) or
     * {@code at_rev} (numeric). The v1.3 range keys ({@code after} /
     * {@code before}) are verbatim string pass-through — the guest owns
     * range interpretation.
     *
     * <p>{@code projectsSnippet} implements memo §10: when the SERVICE
     * body projects a variable through {@code wf:snippet} AND the URL
     * sugar didn't set {@code highlight}, emit {@code "highlight":true}.
     * An explicit URL opt wins because the switch below handles the
     * URL case first — the smart-set fallback only fires when no
     * highlight key was seen in the URL opts.
     */
    private static String buildOptsJson(final String timeSpec, final Map<String, String> opts,
                                        final boolean projectsSnippet) {
        final StringBuilder sb = new StringBuilder();
        sb.append('{');
        boolean first = true;

        if (timeSpec != null) {
            if (timeSpec.startsWith("rev")) {
                final String tail = timeSpec.substring(3);
                try {
                    final long rev = Long.parseLong(tail);
                    sb.append("\"at_rev\":").append(rev);
                    first = false;
                } catch (NumberFormatException ignored) {
                    // Fall through — malformed rev is silently dropped rather
                    // than aborting the rewrite. The guest will see a query
                    // without at_rev; equivalent to current-time search.
                }
            } else {
                sb.append("\"at_time\":\"").append(jsonEscape(timeSpec)).append('"');
                first = false;
            }
        }

        for (Map.Entry<String, String> e : opts.entrySet()) {
            final String k = e.getKey();
            final String v = e.getValue();
            switch (k) {
                case "limit" -> {
                    // Positional arg — already peeled off.
                }
                case "highlight", "include_body" -> {
                    if (!first) sb.append(',');
                    sb.append('"').append(k).append("\":")
                            .append("true".equalsIgnoreCase(v) ? "true" : "false");
                    first = false;
                }
                case "offset" -> {
                    try {
                        final long offs = Long.parseLong(v);
                        if (!first) sb.append(',');
                        sb.append("\"offset\":").append(offs);
                        first = false;
                    } catch (NumberFormatException ignored) {
                        // Malformed — silently drop, don't fail the rewrite.
                    }
                }
                case "lang", "filter", "after", "before" -> {
                    if (!first) sb.append(',');
                    sb.append('"').append(k).append("\":\"")
                            .append(jsonEscape(v)).append('"');
                    first = false;
                }
                default -> {
                    // Unknown key — drop silently. Future opts stay
                    // forward-compatible: a client sending a v1.1 key
                    // against a v1.0 substrate gets a v1.0-shape response
                    // rather than a rewrite failure.
                }
            }
        }

        // Memo §10 smart-set: SERVICE body projected `wf:snippet` and
        // the URL didn't say anything about `highlight` — inject the
        // default. `opts.containsKey("highlight")` guards against
        // clobbering an explicit URL opt (which was handled above by
        // the switch). Only emit when true; a false smart-set would
        // pollute the emitted JSON without changing guest behavior.
        if (projectsSnippet && !opts.containsKey("highlight")) {
            if (!first) sb.append(',');
            sb.append("\"highlight\":true");
            first = false;
        }

        sb.append('}');
        return sb.toString();
    }

    private static String jsonEscape(final String s) {
        final StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            switch (c) {
                case '"':  out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n");  break;
                case '\r': out.append("\\r");  break;
                case '\t': out.append("\\t");  break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.toString();
    }
}

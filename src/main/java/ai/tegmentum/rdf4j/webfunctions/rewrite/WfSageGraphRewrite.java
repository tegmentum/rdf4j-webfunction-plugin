package ai.tegmentum.rdf4j.webfunctions.rewrite;

import org.eclipse.rdf4j.model.IRI;
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

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * URL-sugar rewrite for {@code SERVICE <wf-sagegraph:<name>?node=<uri>&k=N>}
 * — fold the sugar into a {@code SERVICE <wf-invoke:<hex>>} allocation
 * over the locally-registered wf_sagegraph guest wasm.
 *
 * <p>Design memo: {@code wf-conformance/docs/design/wf-sagegraph.md}
 * &sect;04 (Guest ABI), &sect;05 (Wire shape), &sect;11 (Dispatch pattern).
 *
 * <h3>Why guest-dispatch, not federation-to-remote</h3>
 * Unlike {@code wf_vector}, which federates KNN queries to a remote
 * Oxigraph because only Oxigraph carried the native vector index, every
 * engine registers the {@code wf_sagegraph} guest LOCALLY per the
 * wave-8 host-callback pattern. The
 * {@code wf:sagegraph/host@0.1.0#execute-query} import is wired in
 * {@code Rdf4jWasmInstance}, so this rewrite pass dispatches the guest
 * LOCALLY via {@code wf-invoke:<hex>} on the same {@link InvokeRegistry}
 * {@link WfSearchRewrite} uses.
 *
 * <h3>Grammar</h3>
 * <pre>
 *   wf-sagegraph:&lt;name&gt;?node=&lt;iri&gt;[&amp;k=&lt;n&gt;][&amp;model=&lt;url&gt;][&amp;pool=mean|sum|max]
 * </pre>
 *
 * <h3>Positional arg shape</h3>
 * Guest export {@code embed} receives:
 * <ol start="0">
 *   <li>{@code node-iri} — subject IRI from {@code ?node=} (URL-decoded).</li>
 *   <li>{@code model-url} — from {@code ?model=} or the convention default.</li>
 *   <li>{@code k-hops} — from {@code ?k=} or 1 (default_k_hops).</li>
 *   <li>{@code opts-json} — serialized {@code embed-opts} record with
 *       {@code dimensions} + {@code pool}.</li>
 * </ol>
 *
 * <h3>Skip conditions</h3>
 * <ul>
 *   <li>{@code $WF_SAGEGRAPH_WASM_URL} unset — no guest to dispatch to.</li>
 *   <li>SERVICE body doesn't project {@code wf:embedding} — nothing to
 *       bind.</li>
 *   <li>URL fails to parse (missing name, missing {@code node=}).</li>
 * </ul>
 *
 * <h3>RDF4J caveat</h3>
 * RDF4J's {@code Service.parseServiceExpression} strips outer SERVICE
 * wrap via regex — but this pass emits {@code wf-invoke:<hex>} in the
 * service ref position (no outer SERVICE wrap), so that regex doesn't
 * apply. Verified: same shape as {@link WfSearchRewrite}, which also
 * emits {@code wf-invoke:<hex>} without an outer wrap.
 *
 * <p>Cross-engine analogue of
 * {@code qlever-wf-runtime::wf_sagegraph_rewrite},
 * {@code oxigraph-wf::wf_sagegraph_rewrite::rewrite_query_guest_dispatch},
 * and {@code jena-webfunction-plugin::WfSageGraphRewrite}.
 */
public final class WfSageGraphRewrite implements QueryOptimizer {

    private static final ValueFactory VF = SimpleValueFactory.getInstance();

    /** SERVICE URI scheme this pass matches. */
    public static final String WF_SAGEGRAPH_SCHEME = "wf-sagegraph:";
    /** wf_sagegraph guest export invoked on the on-the-fly inference path. */
    private static final String EMBED_ENTRY_POINT = "embed";
    /** wf namespace. */
    private static final String WF_NS = "http://tegmentum.ai/ns/webfunction/";
    private static final String WF_EMBEDDING = WF_NS + "embedding";
    /**
     * Default model URL when the URL sugar doesn't set {@code ?model=}.
     * The v0.2 stubbed ONNX projection uses this as a hash seed only.
     */
    private static final String DEFAULT_MODEL_URL = "wf-sagegraph:stubbed-model";
    /** WIT-declared default {@code default_k_hops} when the URL omits {@code ?k=}. */
    private static final int DEFAULT_K_HOPS = 1;
    /** WIT-declared default {@code dimensions} for the opts JSON. */
    private static final int DEFAULT_DIMENSIONS = 8;
    /** WIT-declared default {@code pool} on the guest's aggregation kernel. */
    private static final String DEFAULT_POOL = "mean";

    private final InvokeRegistry invokes;
    private final String wasmUrl;
    private int rewrites;

    public WfSageGraphRewrite(final InvokeRegistry invokes, final String wasmUrl) {
        this.invokes = invokes;
        this.wasmUrl = wasmUrl;
    }

    /** How many {@code wf-sagegraph:} sugar URLs the last {@link #optimize} pass rewrote. */
    public int rewriteCount() { return rewrites; }

    @Override
    public void optimize(final TupleExpr tupleExpr, final Dataset dataset, final BindingSet bindings) {
        rewrites = 0;
        if (invokes == null) return;
        if (wasmUrl == null || wasmUrl.isEmpty()) return;
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
            if (!url.startsWith(WF_SAGEGRAPH_SCHEME)) return;

            final ParsedUrl parsed = parseUrl(url);
            if (parsed == null) return;
            if (!bodyProjectsEmbedding(service.getServiceExpr())) return;

            // Capture the projection map BEFORE RDF4J's dispatch inlines
            // outer bindings into the SERVICE body. Same pattern as
            // WfSearchRewrite.
            final Map<String, String> projection =
                    collectOutputProjection(service.getServiceExpr());
            final String invokeIri = allocateGuestInvoke(parsed, projection);

            // Swap the serviceRef Var for one bound to wf-invoke:<hex>.
            // Mint a fresh Var (RDF4J insists a Var not be shared across
            // parents); preserve name / anonymous / mark constant so
            // downstream serializers don't get confused.
            final Var replacement = Var.of(ref.getName(),
                    VF.createIRI(invokeIri),
                    ref.isAnonymous(),
                    true);
            service.setServiceRef(replacement);
            rewrites++;
        }

        @Override
        protected void meetNode(final QueryModelNode n) {
            n.visitChildren(this);
        }
    }

    // ---------------------------------------------------------------------
    // URL parsing
    // ---------------------------------------------------------------------

    /** Result of a successful {@code wf-sagegraph:} URL parse. */
    static final class ParsedUrl {
        final String name;
        final String nodeIri;
        /** null when {@code ?k=} isn't set. */
        final Integer k;
        /** null when {@code ?model=} isn't set. */
        final String model;
        /** null when {@code ?pool=} isn't set. */
        final String pool;

        ParsedUrl(final String name, final String nodeIri, final Integer k,
                  final String model, final String pool) {
            this.name = name;
            this.nodeIri = nodeIri;
            this.k = k;
            this.model = model;
            this.pool = pool;
        }
    }

    static ParsedUrl parseUrl(final String url) {
        if (!url.startsWith(WF_SAGEGRAPH_SCHEME)) return null;
        final String rest = url.substring(WF_SAGEGRAPH_SCHEME.length());
        if (rest.isEmpty()) return null;
        final int qIdx = rest.indexOf('?');
        final String name;
        final String optsStr;
        if (qIdx >= 0) {
            name = rest.substring(0, qIdx);
            optsStr = rest.substring(qIdx + 1);
        } else {
            name = rest;
            optsStr = null;
        }
        if (name.isEmpty()) return null;
        final Map<String, String> opts = new LinkedHashMap<>();
        if (optsStr != null && !optsStr.isEmpty()) {
            for (String pair : optsStr.split("&")) {
                if (pair.isEmpty()) continue;
                final int eq = pair.indexOf('=');
                final String key;
                final String value;
                if (eq < 0) {
                    key = urlDecode(pair);
                    value = "";
                } else {
                    key = urlDecode(pair.substring(0, eq));
                    value = urlDecode(pair.substring(eq + 1));
                }
                opts.put(key, value);
            }
        }
        final String nodeIri = opts.get("node");
        if (nodeIri == null || nodeIri.isEmpty()) return null;
        Integer k = null;
        final String kStr = opts.get("k");
        if (kStr != null && !kStr.isEmpty()) {
            try {
                k = Integer.parseUnsignedInt(kStr);
            } catch (NumberFormatException ignored) {
                // Malformed — treat as absent, dispatch will use the default.
            }
        }
        return new ParsedUrl(name, nodeIri, k, opts.get("model"), opts.get("pool"));
    }

    private static String urlDecode(final String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return s;
        }
    }

    // ---------------------------------------------------------------------
    // SERVICE body inspection
    // ---------------------------------------------------------------------

    /**
     * True when the SERVICE body contains at least one
     * {@code ?_ wf:embedding ?var} triple. Absence means nothing for
     * the dispatcher to bind, so the fold is a no-op.
     */
    private static boolean bodyProjectsEmbedding(final TupleExpr body) {
        if (body == null) return false;
        for (StatementPattern sp : StatementPatternCollector.process(body)) {
            final Var p = sp.getPredicateVar();
            if (p == null || !p.hasValue()) continue;
            if (!(p.getValue() instanceof IRI predIri)) continue;
            if (!WF_EMBEDDING.equals(predIri.stringValue())) continue;
            final Var o = sp.getObjectVar();
            if (o != null && !o.hasValue()) return true;
        }
        return false;
    }

    /**
     * Walk the SERVICE body and collect every {@code ?_ wf:<col> ?var}
     * triple as a (guest_col -> outer_var) rename entry — mirrors the
     * companion pass in {@link WfSearchRewrite} so the wf-invoke
     * dispatcher can rename the guest-emitted columns onto the
     * outer-query variables the caller declared. Called at rewrite
     * time so the map is captured BEFORE RDF4J's dispatch inlines
     * outer bindings into the SERVICE body.
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

    // ---------------------------------------------------------------------
    // InvokeSpec allocation
    // ---------------------------------------------------------------------

    private String allocateGuestInvoke(final ParsedUrl parsed,
                                       final Map<String, String> projection) {
        final int k = parsed.k == null ? DEFAULT_K_HOPS : parsed.k;
        final String model = parsed.model == null ? DEFAULT_MODEL_URL : parsed.model;
        final String pool = parsed.pool == null ? DEFAULT_POOL : parsed.pool;
        final String optsJson = "{\"dimensions\":" + DEFAULT_DIMENSIONS
                + ",\"pool\":\"" + jsonEscape(pool) + "\"}";
        final List<Value> args = new ArrayList<>(4);
        args.add(VF.createLiteral(parsed.nodeIri));
        args.add(VF.createLiteral(model));
        args.add(VF.createLiteral(Integer.toString(k)));
        args.add(VF.createLiteral(optsJson));
        final long id = invokes.insert(
                new InvokeSpec(wasmUrl, args, EMBED_ENTRY_POINT, projection));
        return InvokeRegistry.iriFor(id);
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

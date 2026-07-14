package ai.tegmentum.rdf4j.webfunctions.rewrite;

import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.Dataset;
import org.eclipse.rdf4j.query.algebra.TupleExpr;
import org.eclipse.rdf4j.query.algebra.evaluation.QueryOptimizer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Orchestrates the four webfunction query-rewrite passes.
 *
 * <p>Runs the passes in the same order as
 * {@code oxigraph-wf/src/main.rs} (lines 630&ndash;665):
 *
 * <ol>
 *   <li>{@link PartialRewrite} &mdash; constant-fold {@code wf:partial(...)}
 *       so downstream passes see the folded {@code wf-invoke:} IRIs
 *       instead of the FunctionCall expression.</li>
 *   <li>{@link ConversionRewrite} &mdash; expand virtual
 *       {@code urn:wf:conversion:*} named graphs into computed
 *       triples.</li>
 *   <li>{@link AliasRewrite} &mdash; substitute IRI aliases with their
 *       canonical form and record the reverse map for the solution
 *       serializer.</li>
 *   <li>{@link ShapeRewrite} &mdash; replace shape-covered BGPs with
 *       {@code SERVICE <wf:call>} against {@code wf_fetch.wasm}.</li>
 * </ol>
 *
 * <p>Any registry that is empty (or a null fetch URL for
 * ShapeRewrite) short-circuits its pass to identity &mdash; a plugin
 * configured with no shapes/conversions/aliases pays effectively zero
 * cost.
 *
 * <p>The RDF4J port composes as a list of {@link QueryOptimizer}s in
 * the same order, so it can either be spliced into an
 * optimizer-pipeline (see
 * {@code WfEvaluationStrategyFactory#withWebfunctionRewrites(QueryOptimizerPipeline, RewritePipeline)})
 * or invoked directly against a bare {@link TupleExpr} via
 * {@link #apply(TupleExpr, Dataset, BindingSet)}.
 *
 * <p>Java analogue of
 * {@code jena-webfunction-plugin/src/main/java/ai/tegmentum/jena/webfunctions/rewrite/RewritePipeline.java}.
 */
public final class RewritePipeline {

    private final InvokeRegistry invokeRegistry;
    private final ConversionRegistry conversionRegistry;
    private final AliasMap aliasMap;
    private final FulltextRegistry fulltextRegistry;
    private final DocumentRegistry documentRegistry;
    private final FederationRegistry federationRegistry;
    private final ShapeRegistry shapeRegistry;
    private final String wfFetchUrl;

    /**
     * Shared AliasRewrite instance whose state accumulates across passes
     * (for the common one-query-per-runner case). Callers that need
     * per-query state can construct a fresh {@link RewritePipeline}.
     */
    private final AliasRewrite aliasRewrite;

    private RewritePipeline(final Builder b) {
        this.invokeRegistry     = b.invokeRegistry     == null ? new InvokeRegistry()      : b.invokeRegistry;
        this.conversionRegistry = b.conversionRegistry == null ? ConversionRegistry.empty(): b.conversionRegistry;
        this.aliasMap           = b.aliasMap           == null ? AliasMap.empty()          : b.aliasMap;
        this.fulltextRegistry   = b.fulltextRegistry   == null ? FulltextRegistry.empty()  : b.fulltextRegistry;
        this.documentRegistry   = b.documentRegistry   == null ? DocumentRegistry.empty()  : b.documentRegistry;
        this.federationRegistry = b.federationRegistry == null ? FederationRegistry.empty(): b.federationRegistry;
        this.shapeRegistry      = b.shapeRegistry      == null ? ShapeRegistry.empty()     : b.shapeRegistry;
        this.wfFetchUrl         = b.wfFetchUrl;
        this.aliasRewrite       = new AliasRewrite(this.aliasMap);
    }

    public static Builder builder() { return new Builder(); }

    public InvokeRegistry     invokeRegistry()     { return invokeRegistry; }
    public ConversionRegistry conversionRegistry() { return conversionRegistry; }
    public AliasMap           aliasMap()           { return aliasMap; }
    public FulltextRegistry   fulltextRegistry()   { return fulltextRegistry; }
    public DocumentRegistry   documentRegistry()   { return documentRegistry; }
    public FederationRegistry federationRegistry() { return federationRegistry; }
    public ShapeRegistry      shapeRegistry()      { return shapeRegistry; }
    public String             wfFetchUrl()         { return wfFetchUrl; }

    /**
     * The alias reverse-map accumulated by {@link AliasRewrite} while
     * running this pipeline. Callers pipe result rows through
     * {@link AliasRewriteState#rewriteBindingSet(BindingSet)}.
     */
    public AliasRewriteState aliasState() { return aliasRewrite.state(); }

    /**
     * Run all four passes in reference order against {@code expr},
     * mutating it in place. Returns the accumulated
     * {@link AliasRewriteState} so callers can restore the caller's
     * original alias IRIs on the way out.
     *
     * <p>Any single pass whose registry is empty (or whose fetch URL is
     * null/empty for ShapeRewrite) is a no-op &mdash; a fully-empty
     * pipeline is a genuine passthrough.
     */
    public AliasRewriteState apply(final TupleExpr expr, final Dataset dataset, final BindingSet bindings) {
        for (QueryOptimizer opt : optimizers()) {
            opt.optimize(expr, dataset, bindings);
        }
        return aliasRewrite.state();
    }

    /**
     * List of {@link QueryOptimizer}s in reference order. Suitable for
     * splicing into an optimizer pipeline &mdash; each is a fresh
     * instance except {@link AliasRewrite}, which is shared so its
     * state can be retrieved later via {@link #aliasState()}.
     *
     * <p>Empty registries yield an empty list slot &mdash; callers get
     * only the passes that would do work.
     */
    public List<QueryOptimizer> optimizers() {
        final List<QueryOptimizer> out = new ArrayList<>(5);
        // Order matches oxigraph-wf/src/main.rs 630-665.
        if (invokeRegistry != null) {
            out.add(new PartialRewrite(invokeRegistry));
        }
        if (conversionRegistry != null && !conversionRegistry.isEmpty()) {
            out.add(new ConversionRewrite(conversionRegistry));
        }
        if (aliasMap != null && !aliasMap.isEmpty()) {
            out.add(aliasRewrite);
        }
        // FulltextRewrite runs after Alias (so aliased predicate IRIs are
        // canonicalized before the registry lookup) and before Shape (so
        // shape-covered BGPs are still folded on a tree that may have
        // acquired a SERVICE child from filter-fold).
        if (fulltextRegistry != null && !fulltextRegistry.isEmpty() && invokeRegistry != null) {
            out.add(new FulltextRewrite(fulltextRegistry, invokeRegistry));
        }
        // wf-vector federation dispatch (memo `wf-vector.md` §07.1) is
        // NOT wired as a plan-time rewrite on RDF4J — the class
        // WfVectorRewrite exists for reference / potential future use,
        // but RDF4J's Service.parseServiceExpression strips any outer
        // SERVICE wrap this pass tried to emit (regex-based `SERVICE
        // <…> {…}` unwrap), which drops the KNN dispatch on the wire.
        // Instead, the dispatch happens at the FederatedServiceResolver
        // layer via `WfVectorFederatedService`: the `wf-vector:` scheme
        // is recognised by `WfServiceResolver`, which looks up the name
        // in the FederationRegistry and returns a handler that POSTs the
        // whole SERVICE clause to the remote Oxigraph endpoint. See
        // `WfServiceResolver.getService` for the wf-vector branch.
        // WfFederationRewrite runs after Alias (so aliased predicate IRIs
        // are canonicalised before the source-selection lookup) and
        // BEFORE WfSearchRewrite (which expands the wf-search:/wf-fetch:/
        // wf-document: URLs the federation pass emits into wf-invoke:<hex>
        // allocations). Design memo §04 + §11 step 2.
        if (federationRegistry != null && !federationRegistry.isEmpty() && invokeRegistry != null) {
            out.add(new WfFederationRewrite(federationRegistry, invokeRegistry));
        }
        // WfSearchRewrite runs between Alias and Shape too — same
        // reasoning: aliased document-index names get canonicalised
        // before the registry lookup, and the SERVICE URL sugar becomes
        // a wf-invoke:<hex> allocation before ShapeRewrite decides
        // whether to fold any surrounding BGP.
        // WfSearchRewrite consults BOTH DocumentRegistry (primary,
        // wf_document guest ABI) and FulltextRegistry (fallback,
        // wf_fulltext guest ABI). It additionally consults
        // FederationRegistry as a third fallback so `wf-search:<name>`
        // URLs registered ONLY as a federation source of type wf-search
        // (the federation_heterogeneous shape) still fold. Enabled when
        // any of the three registries has an entry.
        final boolean anyWfSearchSource =
                (documentRegistry != null && !documentRegistry.isEmpty())
                        || (fulltextRegistry != null && !fulltextRegistry.isEmpty())
                        || (federationRegistry != null && !federationRegistry.isEmpty());
        if (anyWfSearchSource && invokeRegistry != null) {
            out.add(new WfSearchRewrite(documentRegistry, fulltextRegistry,
                    federationRegistry, invokeRegistry));
        }
        // WfFetchRewrite runs between WfSearchRewrite and ShapeRewrite.
        // Folds SERVICE <wf-fetch:<name>> (emitted by WfFederationRewrite
        // for WF_FETCH-typed sources) into the same SERVICE <wf:call>
        // envelope ShapeRewrite emits for direct-BGP shape hits. Bridge:
        // FederationRegistry names the source, confirms WF_FETCH type;
        // ShapeRegistry supplies the wire contract; both keyed by the
        // same name.
        if (shapeRegistry != null && !shapeRegistry.isEmpty()
                && wfFetchUrl != null && !wfFetchUrl.isEmpty()) {
            out.add(new WfFetchRewrite(federationRegistry, shapeRegistry, wfFetchUrl));
        }
        if (shapeRegistry != null && !shapeRegistry.isEmpty()
                && wfFetchUrl != null && !wfFetchUrl.isEmpty()) {
            out.add(new ShapeRewrite(shapeRegistry, wfFetchUrl));
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * True when no rule/alias/shape is registered and no fold has fired
     * yet. Handy for tests that assert "an unconfigured pipeline is a
     * strict passthrough". PartialRewrite is elided from the check
     * because its default (empty) registry is a no-op for any query
     * without a {@code wf:partial(...)} call.
     */
    public boolean isEmpty() {
        return (conversionRegistry == null || conversionRegistry.isEmpty())
                && (aliasMap == null           || aliasMap.isEmpty())
                && (fulltextRegistry == null   || fulltextRegistry.isEmpty())
                && (documentRegistry == null   || documentRegistry.isEmpty())
                && (federationRegistry == null || federationRegistry.isEmpty())
                && (shapeRegistry == null      || shapeRegistry.isEmpty());
    }

    public static final class Builder {
        private InvokeRegistry invokeRegistry;
        private ConversionRegistry conversionRegistry;
        private AliasMap aliasMap;
        private FulltextRegistry fulltextRegistry;
        private DocumentRegistry documentRegistry;
        private FederationRegistry federationRegistry;
        private ShapeRegistry shapeRegistry;
        private String wfFetchUrl;

        public Builder invokeRegistry(final InvokeRegistry r)         { this.invokeRegistry = r; return this; }
        public Builder conversionRegistry(final ConversionRegistry r) { this.conversionRegistry = r; return this; }
        public Builder aliasMap(final AliasMap m)                     { this.aliasMap = m; return this; }
        public Builder fulltextRegistry(final FulltextRegistry r)     { this.fulltextRegistry = r; return this; }
        public Builder documentRegistry(final DocumentRegistry r)     { this.documentRegistry = r; return this; }
        public Builder federationRegistry(final FederationRegistry r) { this.federationRegistry = r; return this; }
        public Builder shapeRegistry(final ShapeRegistry r)           { this.shapeRegistry = r; return this; }
        public Builder wfFetchUrl(final String url)                   { this.wfFetchUrl = url; return this; }

        public RewritePipeline build() { return new RewritePipeline(this); }
    }
}

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
        this.shapeRegistry      = b.shapeRegistry      == null ? ShapeRegistry.empty()     : b.shapeRegistry;
        this.wfFetchUrl         = b.wfFetchUrl;
        this.aliasRewrite       = new AliasRewrite(this.aliasMap);
    }

    public static Builder builder() { return new Builder(); }

    public InvokeRegistry     invokeRegistry()     { return invokeRegistry; }
    public ConversionRegistry conversionRegistry() { return conversionRegistry; }
    public AliasMap           aliasMap()           { return aliasMap; }
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
        final List<QueryOptimizer> out = new ArrayList<>(4);
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
                && (shapeRegistry == null      || shapeRegistry.isEmpty());
    }

    public static final class Builder {
        private InvokeRegistry invokeRegistry;
        private ConversionRegistry conversionRegistry;
        private AliasMap aliasMap;
        private ShapeRegistry shapeRegistry;
        private String wfFetchUrl;

        public Builder invokeRegistry(final InvokeRegistry r)         { this.invokeRegistry = r; return this; }
        public Builder conversionRegistry(final ConversionRegistry r) { this.conversionRegistry = r; return this; }
        public Builder aliasMap(final AliasMap m)                     { this.aliasMap = m; return this; }
        public Builder shapeRegistry(final ShapeRegistry r)           { this.shapeRegistry = r; return this; }
        public Builder wfFetchUrl(final String url)                   { this.wfFetchUrl = url; return this; }

        public RewritePipeline build() { return new RewritePipeline(this); }
    }
}

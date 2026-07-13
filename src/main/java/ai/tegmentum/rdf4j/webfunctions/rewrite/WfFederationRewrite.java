package ai.tegmentum.rdf4j.webfunctions.rewrite;

import ai.tegmentum.rdf4j.webfunctions.rewrite.FederationRegistry.FederationSource;
import ai.tegmentum.rdf4j.webfunctions.rewrite.FederationRegistry.SourceType;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.Dataset;
import org.eclipse.rdf4j.query.algebra.Filter;
import org.eclipse.rdf4j.query.algebra.Join;
import org.eclipse.rdf4j.query.algebra.QueryModelNode;
import org.eclipse.rdf4j.query.algebra.Service;
import org.eclipse.rdf4j.query.algebra.StatementPattern;
import org.eclipse.rdf4j.query.algebra.TupleExpr;
import org.eclipse.rdf4j.query.algebra.Union;
import org.eclipse.rdf4j.query.algebra.Var;
import org.eclipse.rdf4j.query.algebra.evaluation.QueryOptimizer;
import org.eclipse.rdf4j.query.algebra.helpers.AbstractQueryModelVisitor;
import org.eclipse.rdf4j.query.algebra.helpers.collectors.VarNameCollector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Static-mode federation planner. Walks a query's BGPs and assigns each
 * triple pattern to a registered {@link FederationSource} using
 * predicate lookup (memo §04 static mode). Emits source-grouped
 * {@link Service} nodes so joins between same-source patterns happen at
 * the source, not client-side.
 *
 * <p>Design memo: {@code wf-conformance/docs/design/wf-federation.md}
 * §03, §04, §05, §06, §07, §11.
 *
 * <h3>Assignment rules (memo §04)</h3>
 *
 * <ul>
 *   <li><b>Unambiguous</b> &mdash; predicate declared by exactly one
 *       source: the pattern goes inside a {@link Service} targeting that
 *       source.</li>
 *   <li><b>Multi-source</b> &mdash; predicate declared by more than one
 *       source: emit a {@link Union} of per-source {@link Service}
 *       clauses.</li>
 *   <li><b>Unregistered</b> &mdash; predicate not covered by any source:
 *       leave the pattern in place at the outer BGP.</li>
 * </ul>
 *
 * <p><b>Same-source grouping.</b> Within one source's assigned patterns,
 * split into connected components by shared variables. Each connected
 * component becomes one {@code Service(<endpoint>, Join(sp1, sp2, ...))}
 * &mdash; the biggest single win over naive dispatch (memo §04 step 3).
 *
 * <p><b>Filter pushdown.</b> After BGP rewrite, walk for {@link Filter}
 * nodes whose free variables are all bound within exactly one Service's
 * body (and nowhere else in the filter's argument tree). Splice the
 * filter's condition into that Service (memo §04 step 5, §05).
 *
 * <p><b>Existing SERVICE.</b> Explicit {@link Service} nodes in the input
 * are skipped &mdash; the caller opted into a specific source; respect
 * that (memo §04 step 1).
 *
 * <h3>ServiceRef URL emission</h3>
 *
 * For {@link SourceType#WF_SEARCH}, {@link SourceType#WF_FETCH},
 * {@link SourceType#WF_DOCUMENT}, the Service's {@code serviceRef} is
 * set to the substrate URL sugar ({@code wf-search:<name>} etc.); the
 * sibling {@link WfSearchRewrite} pass expands it further. For
 * {@link SourceType#SPARQL} and {@link SourceType#HTTP_SPARQL}, the
 * serviceRef is the raw endpoint URL from the registry.
 *
 * <p>v0.1 does not implement ASK-probe source discovery (memo §11 step 4)
 * or cost-based join reorder (§11 step 5). Same-source Service call
 * ordering is lexicographic on source name so plans stay deterministic
 * (memo §11 step 2: "correct, not optimal").
 */
public final class WfFederationRewrite implements QueryOptimizer {

    private static final ValueFactory VF = SimpleValueFactory.getInstance();

    private final FederationRegistry registry;
    private final InvokeRegistry invokes;
    private int rewrites;

    public WfFederationRewrite(final FederationRegistry registry, final InvokeRegistry invokes) {
        this.registry = registry;
        this.invokes = invokes;
    }

    /** How many BGPs the last {@link #optimize} pass rewrote. */
    public int rewriteCount() { return rewrites; }

    @Override
    public void optimize(final TupleExpr tupleExpr, final Dataset dataset, final BindingSet bindings) {
        rewrites = 0;
        if (registry == null || registry.isEmpty()) return;
        // Phase 1: rewrite BGPs into source-grouped Service clauses.
        tupleExpr.visit(new BgpRewriter());
        // Phase 2: opportunistic filter pushdown into single-source Services.
        tupleExpr.visit(new FilterPushdown());
    }

    /** Convenience for tests — run the pass and return the rewrite count. */
    public int rewritePattern(final TupleExpr expr) {
        optimize(expr, null, null);
        return rewrites;
    }

    // ---------------------------------------------------------------------
    // Phase 1: BGP rewrite
    // ---------------------------------------------------------------------

    private final class BgpRewriter extends AbstractQueryModelVisitor<RuntimeException> {

        @Override
        public void meet(final Service s) {
            // Caller opted into this source — respect that. Don't descend
            // into the Service body: any BGP inside is already scoped to
            // one endpoint and rewriting it here would be a semantics
            // change (see memo §04 step 1).
        }

        @Override
        public void meet(final Join node) {
            // If this Join subtree is a pure BGP (only SPs and Joins) we
            // own the decision for the whole subtree: either rewrite it
            // wholesale or leave it alone. Recursing into a mixed BGP is
            // the wrong semantic — mirrors ShapeRewrite's approach.
            final List<StatementPattern> collected = new ArrayList<>();
            if (collectPureBgp(node, collected)) {
                final TupleExpr rewritten = tryRewriteBgp(collected);
                if (rewritten != null) {
                    node.replaceWith(rewritten);
                    rewrites++;
                }
                return;
            }
            super.meet(node);
        }

        @Override
        public void meet(final StatementPattern sp) {
            // Standalone SP (not part of a Join subtree handled above).
            if (sp.getParentNode() instanceof Join) return;
            if (isInsideService(sp)) return;
            final TupleExpr rewritten = tryRewriteBgp(Collections.singletonList(sp));
            if (rewritten != null) {
                sp.replaceWith(rewritten);
                rewrites++;
            }
        }

        @Override
        protected void meetNode(final QueryModelNode n) {
            n.visitChildren(this);
        }
    }

    /**
     * Populate {@code acc} with every {@link StatementPattern} under
     * {@code node}, returning true only if {@code node} is a pure BGP
     * &mdash; nested {@link Join}s of statement patterns only.
     */
    private static boolean collectPureBgp(final TupleExpr node, final List<StatementPattern> acc) {
        if (node instanceof StatementPattern sp) {
            acc.add(sp);
            return true;
        }
        if (node instanceof Join j) {
            return collectPureBgp(j.getLeftArg(), acc)
                    && collectPureBgp(j.getRightArg(), acc);
        }
        return false;
    }

    /** Was this SP already parented by (nested somewhere under) a Service? */
    private static boolean isInsideService(final QueryModelNode node) {
        QueryModelNode p = node.getParentNode();
        while (p != null) {
            if (p instanceof Service) return true;
            p = p.getParentNode();
        }
        return false;
    }

    /**
     * Given all SPs in a BGP, return the rewritten replacement or
     * {@code null} if nothing in the BGP is registered (leave the BGP
     * untouched).
     */
    private TupleExpr tryRewriteBgp(final List<StatementPattern> sps) {
        if (sps.isEmpty()) return null;

        // Step 1: assignments per SP.
        final List<List<FederationSource>> assigned = new ArrayList<>(sps.size());
        boolean anyRegistered = false;
        for (StatementPattern sp : sps) {
            final Var pVar = sp.getPredicateVar();
            List<FederationSource> matches = List.of();
            if (pVar != null && pVar.hasValue() && pVar.getValue() instanceof IRI predIri) {
                matches = registry.findByPredicate(predIri.stringValue());
            }
            assigned.add(matches);
            if (!matches.isEmpty()) anyRegistered = true;
        }
        if (!anyRegistered) return null;

        // Step 2: partition SPs into single-source, multi-source, unregistered.
        // TreeMap so per-source groups emit in lexicographic order — the
        // v0.1 "uniform cost, deterministic" reorder (memo §11 step 2).
        final Map<String, List<StatementPattern>> singleSource = new TreeMap<>();
        final List<MultiSourceEntry> multi = new ArrayList<>();
        final List<StatementPattern> unregistered = new ArrayList<>();
        for (int i = 0; i < sps.size(); i++) {
            final StatementPattern sp = sps.get(i);
            final List<FederationSource> m = assigned.get(i);
            if (m.isEmpty()) {
                unregistered.add(sp);
            } else if (m.size() == 1) {
                singleSource.computeIfAbsent(m.get(0).name(), k -> new ArrayList<>()).add(sp);
            } else {
                multi.add(new MultiSourceEntry(sp, m));
            }
        }

        // Step 3: build parts.
        final List<TupleExpr> parts = new ArrayList<>();
        for (Map.Entry<String, List<StatementPattern>> e : singleSource.entrySet()) {
            final FederationSource src = registry.byName(e.getKey());
            for (List<StatementPattern> comp : connectedComponents(e.getValue())) {
                parts.add(buildService(src, comp));
            }
        }
        for (MultiSourceEntry mse : multi) {
            final List<TupleExpr> branches = new ArrayList<>(mse.sources.size());
            for (FederationSource src : mse.sources) {
                branches.add(buildService(src, Collections.singletonList(mse.sp)));
            }
            parts.add(unionOf(branches));
        }
        for (StatementPattern sp : unregistered) {
            parts.add(sp.clone());
        }
        return joinAll(parts);
    }

    /**
     * Within one source's SPs, split into connected components by shared
     * variables (subject/object/predicate). Same-source SPs that share a
     * variable become one Service; disjoint groups stay in their own
     * Service so we don't force a cross product at the source.
     */
    private static List<List<StatementPattern>> connectedComponents(final List<StatementPattern> sps) {
        final int n = sps.size();
        final int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        final List<Set<String>> vars = new ArrayList<>(n);
        for (StatementPattern sp : sps) vars.add(spVars(sp));
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (!Collections.disjoint(vars.get(i), vars.get(j))) {
                    union(parent, i, j);
                }
            }
        }
        final Map<Integer, List<StatementPattern>> groups = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            groups.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(sps.get(i));
        }
        return new ArrayList<>(groups.values());
    }

    private static Set<String> spVars(final StatementPattern sp) {
        final Set<String> out = new HashSet<>();
        addIfVar(out, sp.getSubjectVar());
        addIfVar(out, sp.getPredicateVar());
        addIfVar(out, sp.getObjectVar());
        return out;
    }

    private static void addIfVar(final Set<String> acc, final Var v) {
        if (v != null && !v.hasValue()) acc.add(v.getName());
    }

    private static int find(final int[] parent, final int x) {
        int r = x;
        while (parent[r] != r) r = parent[r];
        int i = x;
        while (parent[i] != r) {
            final int next = parent[i];
            parent[i] = r;
            i = next;
        }
        return r;
    }

    private static void union(final int[] parent, final int a, final int b) {
        final int ra = find(parent, a);
        final int rb = find(parent, b);
        if (ra != rb) parent[ra] = rb;
    }

    /** Build one Service wrapping the given SPs (cloned so Var uniqueness holds). */
    private Service buildService(final FederationSource source, final List<StatementPattern> sps) {
        TupleExpr body = sps.get(0).clone();
        for (int i = 1; i < sps.size(); i++) {
            body = new Join(body, sps.get(i).clone());
        }
        final String url = urlFor(source);
        // Anonymous, constant Var carrying the SERVICE URL — matches how
        // RDF4J's parser materialises a `SERVICE <url>` ref (see
        // PartialRewrite / WfSearchRewrite for the same pattern).
        final Var serviceRef = Var.of("_wf_fed_svc_" + source.name(),
                VF.createIRI(url),
                true,
                true);
        // Render the body as SPARQL text and hand it to the Service
        // constructor. RDF4J's SPARQLFederatedService uses the raw
        // string (via getSelectQueryString()), NOT the algebra tree, to
        // build the SPARQL that flies over the wire. Passing "" here
        // ships `SELECT ... WHERE {}` and every remote returns zero
        // rows — the whole federation-empty-bindings bug.
        return new Service(serviceRef, body, BgpSparqlRenderer.render(body),
                new HashMap<>(), "", resolveSilent(source));
    }

    private static String urlFor(final FederationSource s) {
        return switch (s.sourceType()) {
            case SPARQL, HTTP_SPARQL -> s.endpoint();
            case WF_SEARCH           -> "wf-search:"   + s.name();
            case WF_FETCH            -> "wf-fetch:"    + s.name();
            case WF_DOCUMENT         -> "wf-document:" + s.name();
        };
    }

    /**
     * Resolve the {@code SERVICE SILENT} flag for {@code source} per
     * memo &sect;08. Explicit {@code silent} on the registry entry
     * wins; otherwise fall back to the per-source-type default:
     *
     * <ul>
     *   <li>{@code SPARQL} / {@code HTTP_SPARQL} &rarr; {@code true}
     *       (network endpoint; transport errors degrade to empty
     *       bindings rather than fail the whole query, honest since
     *       static-mode has no probing).</li>
     *   <li>{@code WF_SEARCH} / {@code WF_FETCH} / {@code WF_DOCUMENT}
     *       &rarr; {@code false} (substrate-local dispatch; a failure
     *       is a real bug the operator should see, not a network flap
     *       to mask).</li>
     * </ul>
     *
     * Package-private for the tests.
     */
    static boolean resolveSilent(final FederationSource source) {
        return source.silent().orElseGet(() -> defaultSilentFor(source.sourceType()));
    }

    private static boolean defaultSilentFor(final SourceType type) {
        return switch (type) {
            case SPARQL, HTTP_SPARQL -> true;
            case WF_SEARCH, WF_FETCH, WF_DOCUMENT -> false;
        };
    }

    private static TupleExpr unionOf(final List<TupleExpr> branches) {
        if (branches.isEmpty()) throw new IllegalArgumentException("empty union");
        TupleExpr acc = branches.get(0);
        for (int i = 1; i < branches.size(); i++) {
            acc = new Union(acc, branches.get(i));
        }
        return acc;
    }

    private static TupleExpr joinAll(final List<TupleExpr> parts) {
        if (parts.isEmpty()) throw new IllegalArgumentException("empty join");
        TupleExpr acc = parts.get(0);
        for (int i = 1; i < parts.size(); i++) {
            acc = new Join(acc, parts.get(i));
        }
        return acc;
    }

    private static final class MultiSourceEntry {
        final StatementPattern sp;
        final List<FederationSource> sources;

        MultiSourceEntry(final StatementPattern sp, final List<FederationSource> sources) {
            this.sp = sp;
            this.sources = sources;
        }
    }

    // ---------------------------------------------------------------------
    // Phase 2: filter pushdown
    // ---------------------------------------------------------------------

    private final class FilterPushdown extends AbstractQueryModelVisitor<RuntimeException> {

        @Override
        public void meet(final Filter f) {
            // Recurse first so any nested filter under this one has a
            // chance to push down before we make our own decision.
            super.meet(f);

            final Set<String> freeVars = VarNameCollector.process(f.getCondition());
            if (freeVars.isEmpty()) return;

            final Service target = findCoveringService(f.getArg(), freeVars);
            if (target == null) return;

            // Splice: wrap the target Service's body in Filter(body, cond),
            // then remove the outer Filter. Clone the condition so the
            // outer Filter's now-detached expression tree isn't reused as
            // a live parent target.
            final TupleExpr body = target.getServiceExpr();
            final Filter inner = new Filter(body, f.getCondition().clone());
            target.setArg(inner);
            // Re-render the serviceExpressionString now that the body
            // grew a filter. RDF4J's SPARQLFederatedService reads that
            // raw string, not the algebra, when it renders the SPARQL
            // it POSTs to the remote — a stale string would ship the
            // pre-pushdown BGP and hide the filter from the source.
            target.setExpressionString(BgpSparqlRenderer.render(inner));
            f.replaceWith(f.getArg());
        }

        @Override
        protected void meetNode(final QueryModelNode n) {
            n.visitChildren(this);
        }
    }

    /**
     * Return the unique Service among the top-level Join branches of
     * {@code node} whose body binds all {@code filterVars}, provided
     * none of the sibling branches also bind any of those vars.
     * {@code null} for the ambiguous / cross-source case (leave the
     * filter at the outer level per memo §04 step 5).
     */
    private static Service findCoveringService(final TupleExpr node, final Set<String> filterVars) {
        final List<TupleExpr> parts = new ArrayList<>();
        collectJoinParts(node, parts);

        Service target = null;
        for (TupleExpr part : parts) {
            if (!(part instanceof Service s)) continue;
            final Set<String> bound = VarNameCollector.process(s.getServiceExpr());
            if (bound.containsAll(filterVars)) {
                if (target != null) return null; // more than one candidate — bail
                target = s;
            }
        }
        if (target == null) return null;

        // If any sibling part references any filter var, joining that var
        // across sources means we can't push the check inside one arm.
        for (TupleExpr part : parts) {
            if (part == target) continue;
            final Set<String> bound;
            if (part instanceof Service ss) {
                bound = VarNameCollector.process(ss.getServiceExpr());
            } else {
                bound = VarNameCollector.process(part);
            }
            for (String v : filterVars) {
                if (bound.contains(v)) return null;
            }
        }
        return target;
    }

    private static void collectJoinParts(final TupleExpr node, final List<TupleExpr> acc) {
        if (node instanceof Join j) {
            collectJoinParts(j.getLeftArg(), acc);
            collectJoinParts(j.getRightArg(), acc);
        } else {
            acc.add(node);
        }
    }
}

package ai.tegmentum.rdf4j.webfunctions.rewrite;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.Dataset;
import org.eclipse.rdf4j.query.algebra.Compare;
import org.eclipse.rdf4j.query.algebra.Filter;
import org.eclipse.rdf4j.query.algebra.FunctionCall;
import org.eclipse.rdf4j.query.algebra.Join;
import org.eclipse.rdf4j.query.algebra.Lang;
import org.eclipse.rdf4j.query.algebra.LangMatches;
import org.eclipse.rdf4j.query.algebra.QueryModelNode;
import org.eclipse.rdf4j.query.algebra.Regex;
import org.eclipse.rdf4j.query.algebra.Service;
import org.eclipse.rdf4j.query.algebra.StatementPattern;
import org.eclipse.rdf4j.query.algebra.Str;
import org.eclipse.rdf4j.query.algebra.TupleExpr;
import org.eclipse.rdf4j.query.algebra.ValueConstant;
import org.eclipse.rdf4j.query.algebra.ValueExpr;
import org.eclipse.rdf4j.query.algebra.Var;
import org.eclipse.rdf4j.query.algebra.evaluation.QueryOptimizer;
import org.eclipse.rdf4j.query.algebra.helpers.AbstractQueryModelVisitor;
import org.eclipse.rdf4j.query.algebra.helpers.collectors.StatementPatternCollector;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

/**
 * Filter-fold rewrite: lift ordinary {@code FILTER(REGEX | CONTAINS |
 * STRSTARTS | LANG = ... | LANGMATCHES)} clauses over registered
 * literal-index predicates into a {@code wf-invoke:<hex>} SERVICE
 * dispatch.
 *
 * <p>Design memo: {@code wf-conformance/docs/design/wf-fulltext.md} §06.
 * Rewrite target vocabulary: memo §08 ({@code ?_ wf:doc ?subj} envelope).
 *
 * <h3>Safety invariant (memo §06)</h3>
 *
 * The rewrite is a <b>superset</b> guarantee. The fulltext SERVICE
 * returns a <i>candidate set</i>; the original FILTER is preserved
 * around the joined result so the store's own comparison re-checks each
 * candidate. Never lose rows: every fold is provably a superset of the
 * FILTER's row-set. Never add rows: the FILTER stays as a
 * candidate-check.
 *
 * <p>Java port of {@code oxigraph-wf/src/fulltext_rewrite.rs}.
 */
public final class FulltextRewrite implements QueryOptimizer {

    private static final ValueFactory VF = SimpleValueFactory.getInstance();
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    /** wf namespace for the envelope predicate (matches partial.rs and memo §08). */
    private static final String WF_NS = "http://tegmentum.ai/ns/webfunction/";
    private static final String WF_DOC = WF_NS + "doc";

    // XPath function URIs — how the RDF4J parser materialises SPARQL string
    // built-ins that aren't dedicated algebra nodes.
    private static final String FN_CONTAINS   = "http://www.w3.org/2005/xpath-functions#contains";
    private static final String FN_STARTSWITH = "http://www.w3.org/2005/xpath-functions#starts-with";
    private static final String FN_LOWER_CASE = "http://www.w3.org/2005/xpath-functions#lower-case";
    private static final String FN_UPPER_CASE = "http://www.w3.org/2005/xpath-functions#upper-case";

    private final FulltextRegistry registry;
    private final InvokeRegistry invokeRegistry;
    private int folds;

    public FulltextRewrite(final FulltextRegistry registry, final InvokeRegistry invokeRegistry) {
        this.registry = registry;
        this.invokeRegistry = invokeRegistry;
    }

    public int foldCount() { return folds; }

    @Override
    public void optimize(final TupleExpr tupleExpr, final Dataset dataset, final BindingSet bindings) {
        if (registry == null || registry.isEmpty() || invokeRegistry == null) return;
        tupleExpr.visit(new Walker());
    }

    /** Convenience matching the Rust name. */
    public int rewritePattern(final TupleExpr expr) {
        optimize(expr, null, null);
        return folds;
    }

    // ---------------------------------------------------------------------
    // Walker
    // ---------------------------------------------------------------------

    private final class Walker extends AbstractQueryModelVisitor<RuntimeException> {

        @Override
        public void meet(final Filter node) {
            // Recurse first so nested Filter/Join layers get their own
            // fold attempts before we decide about this one.
            super.meet(node);

            final FoldCandidate candidate = analyzeFilter(node.getCondition());
            if (candidate == null) return;
            final TargetTriple target = findTargetTriple(node.getArg(), candidate.varName);
            if (target == null) return;
            final FulltextRegistry.FulltextIndex entry = registry.findByPredicate(target.predicateIri);
            if (entry == null) return;
            if (!candidate.compatibleWithIndex(entry)) return;

            final String iri = allocateInvoke(candidate, entry);
            final TupleExpr envelope = buildEnvelope(target.subjectVarName);
            final Var serviceRef = Var.of("_wf_svc", VF.createIRI(iri), true, true);
            final Service service = new Service(serviceRef, envelope, "", new HashMap<>(), "", false);

            // Splice: keep the original Filter { expr, .. } exactly where it
            // was, but rewrite its inner to Join(inner, Service). The Filter
            // stays as the safety-invariant candidate re-check.
            final TupleExpr innerTaken = node.getArg();
            final Join newInner = new Join(innerTaken, service);
            node.setArg(newInner);

            folds++;
        }

        @Override
        protected void meetNode(final QueryModelNode n) {
            n.visitChildren(this);
        }
    }

    // ---------------------------------------------------------------------
    // Filter analysis
    // ---------------------------------------------------------------------

    /** Case-hint recovered from a wrapper unwrap. */
    private enum CaseHint {
        NONE, CASE_INSENSITIVE;

        CaseHint merge(final CaseHint other) {
            if (this == CASE_INSENSITIVE || other == CASE_INSENSITIVE) return CASE_INSENSITIVE;
            return NONE;
        }
        boolean isCi() { return this == CASE_INSENSITIVE; }
    }

    /** One recognised foldable filter shape. */
    private static final class FoldCandidate {
        final String varName;
        final String query;
        final String lang; // nullable
        final CaseHint caseHint;

        FoldCandidate(final String varName, final String query, final String lang, final CaseHint caseHint) {
            this.varName = varName;
            this.query = query;
            this.lang = lang;
            this.caseHint = caseHint;
        }

        boolean compatibleWithIndex(final FulltextRegistry.FulltextIndex entry) {
            if (lang == null) return true;
            for (String l : entry.languages()) if (l.equals(lang)) return true;
            return false;
        }
    }

    private FoldCandidate analyzeFilter(final ValueExpr expr) {
        if (expr == null) return null;

        // REGEX(?x, "pat"[, "flags"])
        if (expr instanceof Regex regex) {
            return analyzeRegex(regex);
        }

        // FunctionCall shapes: CONTAINS, STRSTARTS
        if (expr instanceof FunctionCall fc) {
            final String uri = fc.getURI();
            final List<ValueExpr> args = fc.getArgs();
            if (FN_CONTAINS.equals(uri)) {
                if (args.size() != 2) return null;
                final Unwrap uw = unwrapStringFunctions(args.get(0));
                if (uw == null) return null;
                String sub = literalArg(args.get(1));
                if (sub == null) return null;
                if (uw.caseHint.isCi()) sub = sub.toLowerCase(java.util.Locale.ROOT);
                if (!isWordSafeSubstring(sub)) return null;
                return new FoldCandidate(uw.varName, sub, null, uw.caseHint);
            }
            if (FN_STARTSWITH.equals(uri)) {
                if (args.size() != 2) return null;
                final Unwrap uw = unwrapStringFunctions(args.get(0));
                if (uw == null) return null;
                String pre = literalArg(args.get(1));
                if (pre == null || pre.isEmpty()) return null;
                if (uw.caseHint.isCi()) pre = pre.toLowerCase(java.util.Locale.ROOT);
                return new FoldCandidate(uw.varName, pre, null, uw.caseHint);
            }
        }

        // LANGMATCHES(LANG(?x), "en")
        if (expr instanceof LangMatches lm) {
            final String varName = langOfVar(lm.getLeftArg());
            if (varName == null) return null;
            final String lang = literalArg(lm.getRightArg());
            if (lang == null || lang.isEmpty()) return null;
            return new FoldCandidate(varName, "", lang, CaseHint.NONE);
        }

        // Compare( LANG(?x), "en", = ) — either operand order.
        if (expr instanceof Compare cmp && cmp.getOperator() == Compare.CompareOp.EQ) {
            final FoldCandidate a = tryLangEq(cmp.getLeftArg(), cmp.getRightArg());
            if (a != null) return a;
            return tryLangEq(cmp.getRightArg(), cmp.getLeftArg());
        }

        return null;
    }

    private FoldCandidate tryLangEq(final ValueExpr maybeLang, final ValueExpr maybeLit) {
        final String varName = langOfVar(maybeLang);
        if (varName == null) return null;
        final String lang = literalArg(maybeLit);
        if (lang == null || lang.isEmpty()) return null;
        return new FoldCandidate(varName, "", lang, CaseHint.NONE);
    }

    private FoldCandidate analyzeRegex(final Regex regex) {
        final Unwrap uw = unwrapStringFunctions(regex.getArg());
        if (uw == null) return null;
        String pat = literalArg(regex.getPatternArg());
        if (pat == null) return null;
        if (!isSafeRegexPattern(pat)) return null;

        CaseHint caseHint = uw.caseHint;
        final ValueExpr flagsArg = regex.getFlagsArg();
        if (flagsArg != null) {
            final String flags = literalArg(flagsArg);
            // Only "i" is documented as safe by the memo. Anything else
            // (m, s, x) changes semantics we can't match against the index.
            if (!"i".equals(flags)) return null;
            caseHint = caseHint.merge(CaseHint.CASE_INSENSITIVE);
        }
        if (caseHint.isCi()) pat = pat.toLowerCase(java.util.Locale.ROOT);
        return new FoldCandidate(uw.varName, pat, null, caseHint);
    }

    /** Return record from wrapper unwrap: which variable and what case hint. */
    private static final class Unwrap {
        final String varName;
        final CaseHint caseHint;
        Unwrap(final String v, final CaseHint c) { this.varName = v; this.caseHint = c; }
    }

    /**
     * Recursively strip case/lexical-form wrappers (LCASE, UCASE, STR)
     * around a bare variable, capping the depth at three. Any other outer
     * shape (CONCAT, SUBSTR, arithmetic) returns null.
     */
    private static Unwrap unwrapStringFunctions(final ValueExpr e) {
        return unwrapGo(e, 3);
    }

    private static Unwrap unwrapGo(final ValueExpr e, final int depth) {
        if (e instanceof Var v && !v.hasValue()) {
            return new Unwrap(v.getName(), CaseHint.NONE);
        }
        if (depth == 0) return null;

        // STR is a dedicated algebra class.
        if (e instanceof Str s) {
            final Unwrap inner = unwrapGo(s.getArg(), depth - 1);
            if (inner == null) return null;
            return new Unwrap(inner.varName, inner.caseHint); // STR does not imply CI
        }
        // LCASE/UCASE arrive as FunctionCalls on XPath fn:lower-case / fn:upper-case.
        if (e instanceof FunctionCall fc) {
            final String uri = fc.getURI();
            final CaseHint thisHint;
            if (FN_LOWER_CASE.equals(uri) || FN_UPPER_CASE.equals(uri)) {
                thisHint = CaseHint.CASE_INSENSITIVE;
            } else {
                return null;
            }
            final List<ValueExpr> args = fc.getArgs();
            if (args.size() != 1) return null;
            final Unwrap inner = unwrapGo(args.get(0), depth - 1);
            if (inner == null) return null;
            return new Unwrap(inner.varName, thisHint.merge(inner.caseHint));
        }
        return null;
    }

    /** If {@code e} is a plain string literal, return its lexical form. */
    private static String literalArg(final ValueExpr e) {
        if (e instanceof ValueConstant vc && vc.getValue() instanceof Literal lit) {
            return lit.getLabel();
        }
        if (e instanceof Var v && v.hasValue() && v.getValue() instanceof Literal lit) {
            return lit.getLabel();
        }
        return null;
    }

    /** If {@code e} is exactly {@code LANG(?x)}, return the variable name. */
    private static String langOfVar(final ValueExpr e) {
        if (e instanceof Lang lang) {
            final ValueExpr inner = lang.getArg();
            if (inner instanceof Var v && !v.hasValue()) return v.getName();
        }
        return null;
    }

    // ---------------------------------------------------------------------
    // Safety checks
    // ---------------------------------------------------------------------

    /** Alnum-and-space-and-dash-and-underscore allowlist; must contain an alnum. */
    private static boolean isSafeRegexPattern(final String pat) {
        if (pat.isEmpty()) return false;
        boolean sawAlnum = false;
        for (int i = 0; i < pat.length(); i++) {
            final char c = pat.charAt(i);
            final boolean allowed = Character.isLetterOrDigit(c) || c == ' ' || c == '-' || c == '_';
            if (!allowed) return false;
            if (Character.isLetterOrDigit(c)) sawAlnum = true;
        }
        return sawAlnum;
    }

    /** Word-safe substring: length >= 4, all alphanumeric. */
    private static boolean isWordSafeSubstring(final String sub) {
        if (sub.length() < 4) return false;
        for (int i = 0; i < sub.length(); i++) {
            if (!Character.isLetterOrDigit(sub.charAt(i))) return false;
        }
        return true;
    }

    // ---------------------------------------------------------------------
    // Target-triple discovery
    // ---------------------------------------------------------------------

    private static final class TargetTriple {
        final String subjectVarName;
        final String predicateIri;
        TargetTriple(final String s, final String p) { this.subjectVarName = s; this.predicateIri = p; }
    }

    /**
     * Find the triple pattern binding {@code varName} as its OBJECT with a
     * concrete-IRI predicate. If multiple such triples exist under
     * different predicates, return null (ambiguous — which index do we
     * hit?).
     */
    private TargetTriple findTargetTriple(final TupleExpr inner, final String varName) {
        final List<StatementPattern> all = StatementPatternCollector.process(inner);
        String pred = null;
        String subj = null;
        for (StatementPattern sp : all) {
            final Var oVar = sp.getObjectVar();
            if (oVar == null || oVar.hasValue()) continue;
            if (!varName.equals(oVar.getName())) continue;

            final Var pVar = sp.getPredicateVar();
            if (pVar == null || !pVar.hasValue() || !(pVar.getValue() instanceof IRI predIri)) return null;
            final Var sVar = sp.getSubjectVar();
            if (sVar == null || sVar.hasValue()) return null; // subject must be a variable

            if (pred == null) {
                pred = predIri.stringValue();
                subj = sVar.getName();
            } else if (!pred.equals(predIri.stringValue())) {
                return null; // multi-predicate binding — refuse
            }
        }
        if (pred == null) return null;
        return new TargetTriple(subj, pred);
    }

    // ---------------------------------------------------------------------
    // InvokeSpec + SERVICE envelope
    // ---------------------------------------------------------------------

    private String allocateInvoke(final FoldCandidate candidate, final FulltextRegistry.FulltextIndex entry) {
        final String[] be = splitRegistryOpts(entry);
        final String backendEndpoint = be[0];
        final String indexName       = be[1];
        final String queryOptsJson   = buildQueryOptsJson(candidate.lang, candidate.caseHint);

        final List<Value> args = new ArrayList<>(4);
        args.add(VF.createLiteral(backendEndpoint));
        args.add(VF.createLiteral(indexName));
        args.add(VF.createLiteral(candidate.query));
        args.add(VF.createLiteral(queryOptsJson));

        // wf_fulltext guest exports `search` per its WIT world; set the
        // entry point explicitly so intent is visible in the registry.
        final long id = invokeRegistry.insert(new InvokeSpec(entry.backendUrl(), args, "search"));
        return InvokeRegistry.iriFor(id);
    }

    private static String[] splitRegistryOpts(final FulltextRegistry.FulltextIndex entry) {
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
            // Fall back to defaults; matches Rust `unwrap_or(Value::Null)`.
        }
        return new String[] { backendEndpoint, indexName };
    }

    /**
     * Query-time opts JSON. Minimal on purpose: propagates the language
     * tag when present, {@code case_insensitive} when the wrapper implied
     * it, plus a generous limit and highlight=false.
     *
     * <h4>Required WIT fields</h4>
     *
     * The {@code wf:fulltext} {@code query-opts} record declares two
     * NON-OPTIONAL fields — {@code fields: list<string>} and
     * {@code highlight: bool} (design memo §04). The substrate's
     * typed-args coercer parses this JSON back into the record shape; a
     * missing required field yields "record missing required field
     * `fields`" (or `highlight`) and the dispatch fails before it reaches
     * the guest. Emit safe defaults for both:
     * <ul>
     *   <li>{@code fields: []} — "use the analyzer's default field set" per §04.</li>
     *   <li>{@code highlight: false} — skip snippet extraction on the
     *       fold path; the filter-fold's job is a candidate set for the
     *       outer FILTER to re-check, not surface highlighting.</li>
     * </ul>
     * Mirrors {@code oxigraph-wf/src/fulltext_rewrite.rs} 92083ba
     * byte-for-byte on the wire.
     */
    private static String buildQueryOptsJson(final String lang, final CaseHint caseHint) {
        // Hand-built to match the Rust output's key ordering:
        //   {"limit":10000,"fields":[],"highlight":false[,"lang":"..."][,"case_insensitive":true]}
        final StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"limit\":10000");
        // Required by the wf_fulltext WIT record — see doc-comment above.
        sb.append(",\"fields\":[]");
        sb.append(",\"highlight\":false");
        if (lang != null) {
            sb.append(",\"lang\":\"").append(jsonEscape(lang)).append('"');
        }
        if (caseHint.isCi()) {
            sb.append(",\"case_insensitive\":true");
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

    /**
     * SERVICE envelope BGP: {@code _:o wf:doc ?subj}. Binds the
     * outer-scope subject variable so the join restricts the pre-existing
     * BGP to the fulltext candidate set.
     */
    private TupleExpr buildEnvelope(final String subjectVarName) {
        // Fresh anonymous blank-node var per envelope; Var uniqueness is
        // preserved by minting a UUID.
        final Var oVar = Var.of("_wf_ft_o_" + UUID.randomUUID().toString().replace("-", ""), null, true, false);
        final IRI wfDoc = VF.createIRI(WF_DOC);
        final Var pVar = Var.of("_const_wf_doc_" + Integer.toHexString(wfDoc.stringValue().hashCode()),
                wfDoc, true, true);
        // Subject variable participates in the outer BGP; it must NOT be
        // anonymous so the outer join binds to it by name. Use a fresh Var
        // referencing the same name; RDF4J forbids sharing the same Var
        // instance across multiple parents.
        final Var subjVar = Var.of(subjectVarName, null, false, false);
        return new StatementPattern(oVar, pVar, subjVar);
    }
}

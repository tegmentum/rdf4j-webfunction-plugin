package ai.tegmentum.rdf4j.webfunctions.conformance;

import ai.tegmentum.rdf4j.webfunctions.CallbackContext;
import ai.tegmentum.rdf4j.webfunctions.Rdf4jWasmInstance;
import ai.tegmentum.rdf4j.webfunctions.WfEvaluationStrategyFactory;
import ai.tegmentum.rdf4j.webfunctions.WfServiceResolver;
import ai.tegmentum.rdf4j.webfunctions.WitValueMarshaller;
import ai.tegmentum.rdf4j.webfunctions.rewrite.AliasMap;
import ai.tegmentum.rdf4j.webfunctions.rewrite.AliasRewriteState;
import ai.tegmentum.rdf4j.webfunctions.rewrite.ConversionRegistry;
import ai.tegmentum.rdf4j.webfunctions.rewrite.DocumentRegistry;
import ai.tegmentum.rdf4j.webfunctions.rewrite.FederationRegistry;
import ai.tegmentum.rdf4j.webfunctions.rewrite.FulltextRegistry;
import ai.tegmentum.rdf4j.webfunctions.rewrite.RewritePipeline;
import ai.tegmentum.rdf4j.webfunctions.rewrite.ShapeEntry;
import ai.tegmentum.rdf4j.webfunctions.rewrite.ShapeRegistry;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.BooleanQuery;
import org.eclipse.rdf4j.query.GraphQuery;
import org.eclipse.rdf4j.query.GraphQueryResult;
import org.eclipse.rdf4j.query.Query;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.query.impl.MapBindingSet;
import org.eclipse.rdf4j.query.resultio.sparqljson.SPARQLResultsJSONWriter;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.repository.sparql.federation.SPARQLServiceResolver;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.sail.memory.MemoryStore;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.PrintStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cross-engine conformance runner. Loads a Turtle dataset into a fresh
 * {@link MemoryStore}, wires up an optional
 * {@link RewritePipeline} from JSON config files, executes a single
 * SPARQL SELECT query, and emits the result as
 * {@code application/sparql-results+json} on stdout.
 *
 * <p>Argument surface, matching the cross-engine harness:
 * <pre>
 *   --data          &lt;path.ttl&gt;
 *   --query         &lt;path.sparql&gt;
 *   --alias-config      &lt;path.json&gt;   (optional)
 *   --shape-config      &lt;path.json&gt;   (optional)
 *   --conversion-config &lt;path.json&gt;   (optional)
 *   --partial-config    &lt;path.json&gt;   (optional)
 *   --fulltext-config   &lt;path.json&gt;   (optional)
 *   --document-config   &lt;path.json&gt;   (optional)
 * </pre>
 *
 * <p>Exits {@code 0} on success. A non-zero exit code plus a message on
 * stderr signals argument, config, or query failure.
 *
 * <p>Config file formats (flat JSON matching the registries):
 * <pre>
 *   alias-config      : { "aliases": { "alias-iri": "canonical-iri", ... } }
 *   shape-config      : { "shapes":  [ { "name":..., "anchor_class":...,
 *                                        "descriptor_json":..., "sink_url":...,
 *                                        "predicates_to_columns": {...} }, ... ] }
 *   conversion-config : { "rules": [ { "source_predicate":...,
 *                                      "target_predicate":...,
 *                                      "expression":... }, ... ] }
 *   partial-config    : { "wf_fetch_url": "file:///path/to/wf_fetch.wasm" }
 * </pre>
 * The {@code wf_fetch_url} is what {@code ShapeRewrite} needs; it can
 * also be inline in a shape config's top-level for convenience.
 */
public final class ConformanceMain {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private ConformanceMain() {}

    public static void main(final String[] args) {
        try {
            System.exit(run(args, System.out, System.err));
        } catch (Throwable t) {
            t.printStackTrace(System.err);
            System.exit(2);
        }
    }

    /** Testable entry point; returns the process exit code. */
    public static int run(final String[] args, final PrintStream out, final PrintStream err) throws Exception {
        // Canonicalize-sweep CLI mode: mirrors the oxigraph-wf
        // `/admin/canonicalize-sweep` endpoint. RDF4J's ConformanceMain
        // has no persistent HTTP server for the wf-conformance harness
        // to poke, so the harness re-invokes this main with
        // `--canonicalize-sweep --sweep-config <path>`, we load
        // wf_canonicalize.wasm via Rdf4jWasmInstance, dispatch one
        // evaluate() with the config JSON as a literal arg, print a
        // JSON status line, and exit. Detected here (not in parseArgs)
        // because parseArgs enforces value-per-flag.
        if (containsFlag(args, "--canonicalize-sweep")) {
            final String sweepCfg = valueFor(args, "--sweep-config");
            if (sweepCfg == null) {
                err.println("--sweep-config <path> required with --canonicalize-sweep");
                return 2;
            }
            return runCanonicalizeSweep(Path.of(sweepCfg), out, err);
        }
        final Args parsed = parseArgs(args, err);
        if (parsed == null) return 2;

        // Fulltext registry is loaded independently of the rewrite
        // pipeline: it stores config only, and the filter-fold rewrite
        // pass that consumes it is a separate follow-up. Presence of
        // the flag exercises the parser + validation surface end-to-end;
        // absence keeps the runner identical to the pre-fulltext build.
        final FulltextRegistry fulltextRegistry;
        try {
            fulltextRegistry = parsed.fulltextConfig == null
                    ? FulltextRegistry.empty()
                    : FulltextRegistry.loadFromJson(parsed.fulltextConfig);
        } catch (Exception e) {
            err.println("fulltext config error: " + e.getMessage());
            return 2;
        }
        // Diagnostic on stderr so the parity harness (and the
        // ConformanceMainFulltextTest) can assert the registry
        // populated correctly without smuggling state through a static.
        if (parsed.fulltextConfig != null) {
            err.println("loaded " + fulltextRegistry.size()
                    + " fulltext index(es) from " + parsed.fulltextConfig);
        }

        // Document registry powers both administrative lookup and the
        // v1.0 wf-search: SERVICE URL sugar. When populated it is spliced
        // into the RewritePipeline so WfSearchRewrite can allocate
        // wf-invoke:<hex> IRIs at plan time.
        final DocumentRegistry documentRegistry;
        try {
            documentRegistry = parsed.documentConfig == null
                    ? DocumentRegistry.empty()
                    : DocumentRegistry.loadFromJson(parsed.documentConfig);
        } catch (Exception e) {
            err.println("document config error: " + e.getMessage());
            return 2;
        }
        if (parsed.documentConfig != null) {
            err.println("loaded " + documentRegistry.size()
                    + " document(s) from " + parsed.documentConfig);
        }

        // Federation registry powers the wf_federation rewrite pass:
        // static-mode source selection over heterogeneous backends
        // (SPARQL / wf-search / wf-fetch / wf-document / http-sparql).
        // Absent flag → empty registry → pass is a no-op.
        //
        // v0.2 probe-mode wiring: every loaded registry is chained
        // through `withProbeFn(defaultAskProbeFn())` so registries whose
        // JSON sets `probe_mode: true` actually issue ASK queries at
        // plan time. Empty registries pay nothing (the probe path never
        // fires when `probe_mode = false`), so we install the fn
        // unconditionally to keep the boot path simple.
        final FederationRegistry federationRegistry;
        try {
            federationRegistry = parsed.federationConfig == null
                    ? FederationRegistry.empty()
                    : FederationRegistry.loadFromJson(parsed.federationConfig)
                            .withProbeFn(defaultAskProbeFn());
        } catch (Exception e) {
            err.println("federation config error: " + e.getMessage());
            return 2;
        }
        if (parsed.federationConfig != null) {
            err.println("loaded " + federationRegistry.size()
                    + " federation source(s) from " + parsed.federationConfig);
        }

        // v0.3 — wf-relational shape descriptors ride on
        // FederationSource.relationalConfig(), populated by the same
        // FederationRegistry parse above. The sidecar
        // WfRelationalRegistry that used to re-parse the same file was
        // folded away; WfRelationalRewrite now reads the descriptor
        // straight off the federation registry entry.

        final RewritePipeline pipeline = loadPipeline(parsed, fulltextRegistry, documentRegistry, federationRegistry, err);
        if (pipeline == null) return 2;

        final MemoryStore store = new MemoryStore();
        // Install the pipeline before init() so the first strategy handed
        // out by createEvaluationStrategy() picks it up.
        //
        // The federated-service resolver is a WfServiceResolver in front
        // of a real {@link SPARQLServiceResolver} so:
        //   * wf-invoke:<hex> SERVICE refs — planted by PartialRewrite
        //     when it constant-folds wf:partial(...) — dispatch through
        //     WfInvokeService.
        //   * SERVICE clauses pointing at wasm URLs (file:/ipfs:, or
        //     http(s):// with a `.wasm` suffix) dispatch through
        //     WfCallService.
        //   * SERVICE clauses pointing at ordinary HTTP SPARQL endpoints
        //     — the ones the wf_federation rewrite emits for `type =
        //     "sparql"` sources — fall through to the SPARQL client and
        //     actually issue an HTTP POST. Without a real delegate, the
        //     resolver would either reject the URL outright or (with the
        //     pre-fix wasm-URL matcher) mis-classify it as wasm; either
        //     way SILENT would swallow the failure and federation would
        //     return `[]` with zero visible cause.
        // A non-null resolver is also required to sidestep an RDF4J NPE
        // inside ServiceQueryEvaluationStep.evaluate whenever any SERVICE
        // clause reaches evaluation (which the ShapeRewrite pass always
        // installs).
        final WfServiceResolver resolver =
                new WfServiceResolver(new SPARQLServiceResolver(),
                        pipeline.invokeRegistry(),
                        pipeline.federationRegistry());
        store.setEvaluationStrategyFactory(new WfEvaluationStrategyFactory(resolver, store, pipeline));

        final SailRepository repo = new SailRepository(store);
        repo.init();
        try {
            try (RepositoryConnection conn = repo.getConnection()) {
                conn.add(parsed.data.toFile(), null, RDFFormat.TURTLE);

                final String sparql = Files.readString(parsed.query);
                // Dispatch on query shape. SELECT is the historical happy
                // path; CONSTRUCT / DESCRIBE surface their triples as an
                // s/p/o tuple-shape SPARQL Results JSON so the conformance
                // runner (which canonicalises Results JSON only) can compare
                // without a graph-shape-aware code path. ASK bypasses the
                // JSON writer entirely and writes the SPARQL Results JSON
                // boolean shape directly.
                final Query rawQuery = conn.prepareQuery(sparql);
                if (rawQuery instanceof TupleQuery tq) {
                    final SPARQLResultsJSONWriter writer = new SPARQLResultsJSONWriter(out);
                    try (TupleQueryResult results = tq.evaluate()) {
                        final AliasRewriteState aliasState = pipeline.aliasState();
                        writer.startQueryResult(new ArrayList<>(results.getBindingNames()));
                        while (results.hasNext()) {
                            final BindingSet in = results.next();
                            final BindingSet rewritten = aliasState == null ? in : aliasState.rewriteBindingSet(in);
                            writer.handleSolution(rewritten);
                        }
                        writer.endQueryResult();
                    }
                } else if (rawQuery instanceof BooleanQuery bq) {
                    writeAskAsJson(out, bq.evaluate());
                } else if (rawQuery instanceof GraphQuery gq) {
                    writeGraphAsSpoJson(out, gq, pipeline.aliasState());
                } else {
                    throw new IllegalArgumentException(
                            "conformance-main: unsupported query shape: "
                                    + rawQuery.getClass().getName());
                }
                out.flush();
            }
        } finally {
            repo.shutDown();
        }
        return 0;
    }

    // ---- non-SELECT query shape helpers -----------------------------------

    /**
     * Emit the SPARQL 1.1 Results JSON boolean serialization —
     * {@code {"head":{},"boolean":true|false}} — for an ASK result.
     * Hand-rolled rather than routed through
     * {@link SPARQLResultsJSONWriter} because that writer's API is
     * bound to the tuple-shape response only.
     */
    private static void writeAskAsJson(final PrintStream out, final boolean answer) {
        out.print("{\"head\":{},\"boolean\":");
        out.print(answer ? "true" : "false");
        out.print('}');
    }

    /**
     * Serialize a {@link GraphQuery} result as a tuple-shape SPARQL Results
     * JSON with columns {@code ?s}, {@code ?p}, {@code ?o} — one row per
     * statement in the CONSTRUCT / DESCRIBE output. Runs each binding
     * through {@link AliasRewriteState#rewriteBindingSet} so output IRIs
     * come back under whatever alias the query mentioned, matching the
     * wrapping the SELECT path already performs.
     */
    private static void writeGraphAsSpoJson(final PrintStream out,
                                            final GraphQuery gq,
                                            final AliasRewriteState aliasState) {
        final SPARQLResultsJSONWriter writer = new SPARQLResultsJSONWriter(out);
        final List<String> spo = List.of("s", "p", "o");
        writer.startQueryResult(new ArrayList<>(spo));
        try (GraphQueryResult results = gq.evaluate()) {
            while (results.hasNext()) {
                final Statement st = results.next();
                final MapBindingSet bs = new MapBindingSet();
                bs.setBinding("s", st.getSubject());
                bs.setBinding("p", st.getPredicate());
                bs.setBinding("o", st.getObject());
                final BindingSet rewritten = aliasState == null
                        ? bs : aliasState.rewriteBindingSet(bs);
                writer.handleSolution(rewritten);
            }
        }
        writer.endQueryResult();
    }

    /**
     * Default v0.2 ASK-probe function &mdash; issues
     * {@code ASK { ?s <predicate> ?o }} against
     * {@code source.endpoint()} over HTTP using the JDK's built-in
     * {@link java.net.http.HttpClient} (same class
     * {@code HostCallbacks.httpPostJsonImpl} uses, so substrate-wide
     * error/timeout behaviour stays consistent). Only meaningful for
     * {@code SPARQL} / {@code HTTP_SPARQL} sources &mdash; other source
     * types (wf-search / wf-fetch / etc.) don't speak SPARQL so the
     * probe returns {@code false} for them (the static predicate list
     * stays the source of truth for wf-* sources).
     *
     * <p>Kept in {@code ConformanceMain} rather than in
     * {@link FederationRegistry} to respect the v0.2 landing's DNT
     * fence on the registry module. Mirrors
     * {@code oxigraph-wf::federation_registry::default_ask_probe_fn}
     * so all four engines exhibit identical probe semantics under an
     * identical registry JSON.
     */
    private static FederationRegistry.ProbeFn defaultAskProbeFn() {
        return (src, predicateIri) -> {
            final FederationRegistry.SourceType t = src.sourceType();
            if (t != FederationRegistry.SourceType.SPARQL
                    && t != FederationRegistry.SourceType.HTTP_SPARQL) {
                return false;
            }
            final String query = "ASK { ?s <" + predicateIri + "> ?o }";
            final java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(30))
                    .build();
            final java.net.http.HttpRequest request = java.net.http.HttpRequest
                    .newBuilder(java.net.URI.create(src.endpoint()))
                    .timeout(java.time.Duration.ofSeconds(30))
                    .header("Content-Type", "application/sparql-query")
                    .header("Accept", "application/sparql-results+json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                            query, java.nio.charset.StandardCharsets.UTF_8))
                    .build();
            final java.net.http.HttpResponse<String> response = client.send(
                    request, java.net.http.HttpResponse.BodyHandlers.ofString(
                            java.nio.charset.StandardCharsets.UTF_8));
            final int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw new java.io.IOException(
                        "probe HTTP " + status + ": " + response.body());
            }
            // Minimal SPARQL Results JSON parse — {"head":{}, "boolean": true|false}.
            final JsonNode node = MAPPER.readTree(response.body());
            final JsonNode b = node.get("boolean");
            return b != null && b.isBoolean() && b.asBoolean();
        };
    }

    // ---- canonicalize-sweep CLI mode --------------------------------------

    /**
     * In-process replacement for oxigraph-wf's
     * {@code POST /admin/canonicalize-sweep} endpoint. See the sibling
     * Jena {@code ConformanceMain#runCanonicalizeSweep} for the design
     * rationale — RDF4J's ConformanceMain has the same one-shot CLI
     * shape and needs the same admin surface for parity.
     *
     * <p>The sweep config file mirrors oxigraph-wf's
     * {@code build_canonicalize_sweep_config} output with one added
     * top-level field:
     * <ul>
     *   <li>{@code canonicalize_wasm_url} — URL of wf_canonicalize.wasm.
     *       Stripped from the payload before it reaches the guest.</li>
     * </ul>
     * The remainder ({@code sink}, {@code rule},
     * {@code fulltext_indexes}, {@code document_indexes},
     * {@code full_scan}) is passed through verbatim as a string
     * literal into the guest's {@code evaluate} export.
     *
     * <p>Prints {@code {"status":"ok","processed":N}} on success and
     * returns 0. {@code N} sums the numeric literal cells in the
     * guest's first result row.
     */
    private static int runCanonicalizeSweep(final Path sweepCfgPath,
                                            final PrintStream out,
                                            final PrintStream err) {
        final JsonNode cfg;
        try {
            cfg = MAPPER.readTree(Files.readString(sweepCfgPath));
        } catch (Exception e) {
            err.println("sweep config error: " + e.getMessage());
            return 2;
        }
        if (!cfg.hasNonNull("canonicalize_wasm_url")) {
            err.println("sweep config error: missing `canonicalize_wasm_url`");
            return 2;
        }
        final String wasmUrl = cfg.get("canonicalize_wasm_url").asString();

        // Copy every top-level field except our own wiring key. The
        // remaining object is what the guest reads at evaluate() time.
        final tools.jackson.databind.node.ObjectNode guest = MAPPER.createObjectNode();
        for (String k : cfg.propertyNames()) {
            if ("canonicalize_wasm_url".equals(k)) continue;
            guest.set(k, cfg.get(k));
        }
        final String guestCfgJson = guest.toString();

        // Stand up an empty MemoryStore + SailRepository with the
        // webfunction strategy factory. This binds a live
        // CallbackContext (via WfEvaluationStrategyFactory) so the
        // sweep guest's sink-open / sink-execute / sink-close and
        // execute-query callbacks all resolve. The store is empty —
        // execute-query lookups (e.g. owl:sameAs canonicalisation)
        // return no rows, which is the correct behaviour for a sweep
        // that has no ambient graph.
        final MemoryStore store = new MemoryStore();
        // Pass the store as the Sail so the callback context can honour
        // execute-update from the sweep guest (owl:sameAs deletions land
        // against this empty write connection — a noop against no
        // matching triples).
        store.setEvaluationStrategyFactory(
                new WfEvaluationStrategyFactory(new SPARQLServiceResolver(), store));
        final SailRepository repo = new SailRepository(store);
        repo.init();

        long processed = 0;
        try (RepositoryConnection conn = repo.getConnection()) {
            // Prime the strategy factory. Its createEvaluationStrategy
            // implementation calls CallbackContext.bind on the newly
            // constructed strategy; the binding leaks past query
            // teardown (see the factory's own class comment), which is
            // exactly what we want here.
            try (var r = conn.prepareTupleQuery("SELECT * WHERE {} LIMIT 0").evaluate()) {
                while (r.hasNext()) r.next();
            }

            final URL wasm = URI.create(wasmUrl).toURL();
            final ValueFactory vf = SimpleValueFactory.getInstance();
            try (Rdf4jWasmInstance instance = new Rdf4jWasmInstance(wasm)) {
                final Value cfgLit = vf.createLiteral(guestCfgJson);
                final List<WitValueMarshaller.Row> rows = instance.evaluate(vf, cfgLit);
                if (!rows.isEmpty()) {
                    for (Value v : rows.get(0).values) {
                        if (v instanceof Literal l) {
                            try {
                                processed += Long.parseLong(l.getLabel());
                            } catch (NumberFormatException ignore) {
                                // Non-numeric literal (e.g. a status
                                // string) — skip; only numeric cells
                                // contribute to the processed count.
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            err.println("canonicalize sweep failed: " + e.getMessage());
            e.printStackTrace(err);
            return 3;
        } finally {
            CallbackContext.unbind();
            repo.shutDown();
        }

        out.print("{\"status\":\"ok\",\"processed\":");
        out.print(processed);
        out.println("}");
        out.flush();
        return 0;
    }

    private static boolean containsFlag(final String[] args, final String flag) {
        for (String a : args) {
            if (flag.equals(a)) return true;
        }
        return false;
    }

    private static String valueFor(final String[] args, final String flag) {
        for (int i = 0; i < args.length - 1; i++) {
            if (flag.equals(args[i])) return args[i + 1];
        }
        return null;
    }

    // ---- argument parsing -------------------------------------------------

    static final class Args {
        Path data;
        Path query;
        Path aliasConfig;
        Path shapeConfig;
        Path conversionConfig;
        Path partialConfig;
        Path fulltextConfig;
        Path documentConfig;
        Path federationConfig;
    }

    static Args parseArgs(final String[] argv, final PrintStream err) {
        final Args a = new Args();
        for (int i = 0; i < argv.length; i++) {
            final String flag = argv[i];
            final String value = i + 1 < argv.length ? argv[++i] : null;
            if (value == null) {
                err.println("missing value for " + flag);
                return null;
            }
            switch (flag) {
                case "--data"              -> a.data = Path.of(value);
                case "--query"             -> a.query = Path.of(value);
                case "--alias-config"      -> a.aliasConfig = Path.of(value);
                case "--shape-config"      -> a.shapeConfig = Path.of(value);
                case "--conversion-config" -> a.conversionConfig = Path.of(value);
                case "--partial-config"    -> a.partialConfig = Path.of(value);
                case "--fulltext-config"   -> a.fulltextConfig = Path.of(value);
                case "--document-config"   -> a.documentConfig = Path.of(value);
                case "--federation-config" -> a.federationConfig = Path.of(value);
                default -> {
                    err.println("unknown argument: " + flag);
                    return null;
                }
            }
        }
        if (a.data == null) {
            err.println("--data is required");
            return null;
        }
        if (a.query == null) {
            err.println("--query is required");
            return null;
        }
        return a;
    }

    // ---- config loading ---------------------------------------------------

    static RewritePipeline loadPipeline(final Args a, final FulltextRegistry fulltextRegistry,
                                        final DocumentRegistry documentRegistry,
                                        final FederationRegistry federationRegistry,
                                        final PrintStream err) {
        try {
            final RewritePipeline.Builder b = RewritePipeline.builder();
            if (a.aliasConfig      != null) b.aliasMap(loadAliasMap(a.aliasConfig));
            if (a.conversionConfig != null) b.conversionRegistry(loadConversionRegistry(a.conversionConfig));
            if (a.shapeConfig      != null) b.shapeRegistry(loadShapeRegistry(a.shapeConfig));
            if (fulltextRegistry   != null && !fulltextRegistry.isEmpty()) b.fulltextRegistry(fulltextRegistry);
            if (documentRegistry   != null && !documentRegistry.isEmpty()) b.documentRegistry(documentRegistry);
            if (federationRegistry != null && !federationRegistry.isEmpty()) b.federationRegistry(federationRegistry);
            if (a.partialConfig    != null) {
                final JsonNode root = MAPPER.readTree(Files.readString(a.partialConfig));
                if (root.hasNonNull("wf_fetch_url")) {
                    b.wfFetchUrl(root.get("wf_fetch_url").asString());
                }
            }
            // Shape configs may also carry wf_fetch_url as a top-level convenience.
            if (a.shapeConfig != null) {
                final JsonNode root = MAPPER.readTree(Files.readString(a.shapeConfig));
                if (root.hasNonNull("wf_fetch_url")) {
                    b.wfFetchUrl(root.get("wf_fetch_url").asString());
                }
            }
            return b.build();
        } catch (Exception e) {
            err.println("config error: " + e.getMessage());
            return null;
        }
    }

    static AliasMap loadAliasMap(final Path path) throws Exception {
        final JsonNode root = MAPPER.readTree(Files.readString(path));
        final Map<String, String> out = new HashMap<>();
        final JsonNode aliases = root.path("aliases");
        if (aliases != null && aliases.isObject()) {
            for (String alias : aliases.propertyNames()) {
                out.put(alias, aliases.get(alias).asString());
            }
        }
        return AliasMap.of(out);
    }

    static ConversionRegistry loadConversionRegistry(final Path path) throws Exception {
        final JsonNode root = MAPPER.readTree(Files.readString(path));
        final List<String[]> rows = new ArrayList<>();
        final JsonNode rules = root.path("rules");
        if (rules != null && rules.isArray()) {
            for (JsonNode r : rules) {
                rows.add(new String[]{
                        r.path("target_predicate").asString(),
                        r.path("source_predicate").asString(),
                        r.path("expression").asString()
                });
            }
        }
        return ConversionRegistry.of(rows);
    }

    static ShapeRegistry loadShapeRegistry(final Path path) throws Exception {
        final JsonNode root = MAPPER.readTree(Files.readString(path));
        final List<ShapeEntry> entries = new ArrayList<>();
        final JsonNode shapes = root.path("shapes");
        if (shapes != null && shapes.isArray()) {
            for (JsonNode s : shapes) {
                final String name = s.path("name").asString();
                final String anchorClass = s.hasNonNull("anchor_class") ? s.get("anchor_class").asString() : null;
                final String descriptorJson;
                if (s.hasNonNull("descriptor_json")) {
                    // Callers commonly pass the descriptor as an already-encoded
                    // JSON string; accept either shape (string or object) here.
                    final JsonNode d = s.get("descriptor_json");
                    descriptorJson = d.isTextual() ? d.asString() : d.toString();
                } else {
                    descriptorJson = s.toString();
                }
                final Map<String, String> columns = new LinkedHashMap<>();
                final JsonNode cols = s.path("predicates_to_columns");
                if (cols != null && cols.isObject()) {
                    for (String pred : cols.propertyNames()) {
                        columns.put(pred, cols.get(pred).asString());
                    }
                }
                final String subjectColumn = s.hasNonNull("subject_column")
                        ? s.get("subject_column").asString() : "id";
                entries.add(new ShapeEntry(name, descriptorJson, anchorClass, columns, subjectColumn));
            }
        }
        return ShapeRegistry.of(entries);
    }
}

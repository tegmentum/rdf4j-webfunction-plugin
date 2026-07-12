package ai.tegmentum.rdf4j.webfunctions.conformance;

import ai.tegmentum.rdf4j.webfunctions.WfEvaluationStrategyFactory;
import ai.tegmentum.rdf4j.webfunctions.WfServiceResolver;
import ai.tegmentum.rdf4j.webfunctions.rewrite.AliasMap;
import ai.tegmentum.rdf4j.webfunctions.rewrite.AliasRewriteState;
import ai.tegmentum.rdf4j.webfunctions.rewrite.ConversionRegistry;
import ai.tegmentum.rdf4j.webfunctions.rewrite.RewritePipeline;
import ai.tegmentum.rdf4j.webfunctions.rewrite.ShapeEntry;
import ai.tegmentum.rdf4j.webfunctions.rewrite.ShapeRegistry;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.query.resultio.sparqljson.SPARQLResultsJSONWriter;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.sail.memory.MemoryStore;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.PrintStream;
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
        final Args parsed = parseArgs(args, err);
        if (parsed == null) return 2;

        final RewritePipeline pipeline = loadPipeline(parsed, err);
        if (pipeline == null) return 2;

        final MemoryStore store = new MemoryStore();
        // Install the pipeline before init() so the first strategy handed
        // out by createEvaluationStrategy() picks it up.
        //
        // The federated-service resolver is a WfServiceResolver with no
        // delegate (the runner has no HTTP SPARQL federation to fall back
        // to) and the pipeline's InvokeRegistry so wf-invoke:<hex> SERVICE
        // refs — planted by PartialRewrite when it constant-folds
        // wf:partial(...) — dispatch through WfInvokeService. A non-null
        // resolver is also required to sidestep an RDF4J NPE inside
        // ServiceQueryEvaluationStep.evaluate whenever any SERVICE clause
        // reaches evaluation (which the ShapeRewrite pass always installs).
        final WfServiceResolver resolver =
                new WfServiceResolver(null, pipeline.invokeRegistry());
        store.setEvaluationStrategyFactory(new WfEvaluationStrategyFactory(resolver, store, pipeline));

        final SailRepository repo = new SailRepository(store);
        repo.init();
        try {
            try (RepositoryConnection conn = repo.getConnection()) {
                conn.add(parsed.data.toFile(), null, RDFFormat.TURTLE);

                final String sparql = Files.readString(parsed.query);
                final TupleQuery q = conn.prepareTupleQuery(sparql);

                final SPARQLResultsJSONWriter writer = new SPARQLResultsJSONWriter(out);
                try (TupleQueryResult results = q.evaluate()) {
                    final AliasRewriteState aliasState = pipeline.aliasState();
                    writer.startQueryResult(new ArrayList<>(results.getBindingNames()));
                    while (results.hasNext()) {
                        final BindingSet in = results.next();
                        final BindingSet rewritten = aliasState == null ? in : aliasState.rewriteBindingSet(in);
                        writer.handleSolution(rewritten);
                    }
                    writer.endQueryResult();
                }
                out.flush();
            }
        } finally {
            repo.shutDown();
        }
        return 0;
    }

    // ---- argument parsing -------------------------------------------------

    private static final class Args {
        Path data;
        Path query;
        Path aliasConfig;
        Path shapeConfig;
        Path conversionConfig;
        Path partialConfig;
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

    static RewritePipeline loadPipeline(final Args a, final PrintStream err) {
        try {
            final RewritePipeline.Builder b = RewritePipeline.builder();
            if (a.aliasConfig      != null) b.aliasMap(loadAliasMap(a.aliasConfig));
            if (a.conversionConfig != null) b.conversionRegistry(loadConversionRegistry(a.conversionConfig));
            if (a.shapeConfig      != null) b.shapeRegistry(loadShapeRegistry(a.shapeConfig));
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

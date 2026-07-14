package ai.tegmentum.rdf4j.webfunctions.rewrite;

import org.eclipse.rdf4j.model.Value;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Snapshot of a {@code wf:partial(...)} call &mdash; the wasm URL plus
 * the bound positional args, and optionally the export name to invoke.
 * Held in an {@link InvokeRegistry} until the SERVICE handler pops it.
 *
 * <p>Java port of {@code oxigraph-wf/src/partial.rs::InvokeSpec}.
 *
 * <p>The {@link #entryPoint()} field lets rewrite passes that know the
 * WIT world's contract point the dispatcher at the correct export (e.g.
 * {@code wf_fulltext} exports {@code search}, not {@code evaluate}).
 * When {@code null}, the substrate auto-detects — see
 * {@link ai.tegmentum.rdf4j.webfunctions.EntryPointResolver}.
 *
 * <p>The {@link #projection()} map carries a rewrite-time
 * {@code guest_column -> outer_variable} rename derived from the
 * SERVICE body's {@code ?_ wf:<col> ?var} triples. The wf-invoke
 * SERVICE dispatcher applies it to the wasm-emitted rows so the outer
 * join sees the caller-declared variables. Captured at rewrite time
 * because RDF4J's federated-service dispatch, like Jena's, drops the
 * outer-visible {@code ?var} into the SERVICE body before the
 * dispatcher sees it — a body-walk at execution time silently loses
 * those triples and the outer join collapses to a Cartesian product
 * (federation_wf_search regression). Mirrors QLever's identical fix
 * ({@code qlever-wf-runtime::wf_search_rewrite} commit `04fdb03`).
 */
public final class InvokeSpec {

    private final String wasmUrl;
    private final List<Value> args;
    private final String entryPoint;
    private final Map<String, String> projection;

    /**
     * Legacy two-arg constructor — entry point defaults to {@code null}
     * so the substrate falls back to {@code evaluate}. Every existing
     * call site (rewrite pass, tests, host callbacks) keeps compiling
     * without a churn diff.
     */
    public InvokeSpec(final String wasmUrl, final List<Value> args) {
        this(wasmUrl, args, null, Map.of());
    }

    public InvokeSpec(final String wasmUrl, final List<Value> args, final String entryPoint) {
        this(wasmUrl, args, entryPoint, Map.of());
    }

    public InvokeSpec(final String wasmUrl, final List<Value> args, final String entryPoint,
                      final Map<String, String> projection) {
        this.wasmUrl = wasmUrl;
        this.args = List.copyOf(args);
        this.entryPoint = entryPoint;
        this.projection = projection == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(projection));
    }

    public String wasmUrl()      { return wasmUrl; }
    public List<Value> args()    { return args; }
    /** Caller-supplied export-name override, or {@code null} to auto-detect. */
    public String entryPoint()   { return entryPoint; }
    /**
     * Rewrite-time guest-column-to-outer-variable rename map. Empty
     * when the allocating rewrite pass didn't populate it (legacy
     * behavior) — dispatcher falls back to walking the SERVICE body.
     */
    public Map<String, String> projection() { return projection; }
}

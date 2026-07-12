package ai.tegmentum.rdf4j.webfunctions.rewrite;

import org.eclipse.rdf4j.model.Value;

import java.util.List;

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
 */
public final class InvokeSpec {

    private final String wasmUrl;
    private final List<Value> args;
    private final String entryPoint;

    /**
     * Legacy two-arg constructor — entry point defaults to {@code null}
     * so the substrate falls back to {@code evaluate}. Every existing
     * call site (rewrite pass, tests, host callbacks) keeps compiling
     * without a churn diff.
     */
    public InvokeSpec(final String wasmUrl, final List<Value> args) {
        this(wasmUrl, args, null);
    }

    public InvokeSpec(final String wasmUrl, final List<Value> args, final String entryPoint) {
        this.wasmUrl = wasmUrl;
        this.args = List.copyOf(args);
        this.entryPoint = entryPoint;
    }

    public String wasmUrl()      { return wasmUrl; }
    public List<Value> args()    { return args; }
    /** Caller-supplied export-name override, or {@code null} to auto-detect. */
    public String entryPoint()   { return entryPoint; }
}

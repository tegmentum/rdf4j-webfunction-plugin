package ai.tegmentum.rdf4j.webfunctions.rewrite;

import org.eclipse.rdf4j.model.Value;

import java.util.List;

/**
 * Snapshot of a {@code wf:partial(...)} call &mdash; the wasm URL plus
 * the bound positional args. Held in an {@link InvokeRegistry} until the
 * SERVICE handler pops it.
 *
 * <p>Java port of {@code oxigraph-wf/src/partial.rs::InvokeSpec}.
 */
public final class InvokeSpec {

    private final String wasmUrl;
    private final List<Value> args;

    public InvokeSpec(final String wasmUrl, final List<Value> args) {
        this.wasmUrl = wasmUrl;
        this.args = List.copyOf(args);
    }

    public String wasmUrl()      { return wasmUrl; }
    public List<Value> args()    { return args; }
}

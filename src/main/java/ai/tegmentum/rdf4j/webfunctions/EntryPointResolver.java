package ai.tegmentum.rdf4j.webfunctions;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves the wasm-component function export to invoke for a given
 * guest, given a caller-supplied override and the guest's list of
 * exported function names.
 *
 * <p>Substrate-wide entry-point contract, mirroring
 * {@code oxigraph-wf/src/wf_call.rs::resolve_entry_point} (commit
 * {@code 3a92707}). Resolution proceeds in the following order:
 *
 * <ol>
 *   <li>Caller override — if the caller supplies a name (via e.g.
 *       {@code InvokeSpec.entryPoint}), it is used verbatim.</li>
 *   <li>Substrate default — {@code evaluate}, if present. Every
 *       existing guest shipped under {@code stardog:webfunction} exports
 *       {@code evaluate}, so this is the backwards-compatible case.</li>
 *   <li>Single top-level function export — used when the guest's WIT
 *       world names its export differently (e.g. {@code wf_fulltext}
 *       exports {@code search}).</li>
 *   <li>Otherwise raises, listing the visible exports so the caller can
 *       pick one via an explicit override.</li>
 * </ol>
 *
 * <p>Pure and side-effect free; the wasmtime-side introspection lives
 * in {@link Rdf4jWasmInstance} and just feeds this resolver a
 * {@link List} of names.
 */
public final class EntryPointResolver {

    private EntryPointResolver() {}

    /** The historical substrate-wide default entry-point name. */
    public static final String DEFAULT_ENTRY_POINT = "evaluate";

    /**
     * Resolve the entry-point name to invoke on a guest with the given
     * function exports. See class Javadoc for the resolution order.
     *
     * @param exportedFunctions names of the component's top-level
     *                          function exports, as reported by
     *                          {@code ComponentInstance.exportedFunctions()}
     * @param override          caller-supplied override, or {@code null}
     * @return the resolved entry-point name; never {@code null}
     * @throws IllegalStateException on ambiguous multi-export guests
     *                               that ship no {@code evaluate}
     */
    public static String resolve(final List<String> exportedFunctions,
                                 final String override) {
        if (override != null && !override.isEmpty()) {
            return override;
        }
        if (exportedFunctions == null || exportedFunctions.isEmpty()) {
            throw new IllegalStateException(
                    "component has no function exports at the top level");
        }
        // Prefer the substrate-wide default when the component ships one.
        for (String name : exportedFunctions) {
            if (DEFAULT_ENTRY_POINT.equals(name)) {
                return DEFAULT_ENTRY_POINT;
            }
        }
        // Fall back to a single function export.
        final List<String> distinct = new ArrayList<>();
        for (String name : exportedFunctions) {
            if (!distinct.contains(name)) distinct.add(name);
        }
        if (distinct.size() == 1) {
            return distinct.get(0);
        }
        throw new IllegalStateException(
                "component has multiple function exports [" + String.join(", ", distinct)
                        + "] and no `evaluate` — specify one via wf:partial's "
                        + "entry_point override on InvokeSpec");
    }
}

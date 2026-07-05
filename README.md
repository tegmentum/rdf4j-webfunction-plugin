# rdf4j-webfunction-plugin

Eclipse RDF4J binding for the WebAssembly Component Model. Registers a SPARQL
filter function under `http://tegmentum.ai/ns/webfunction/` that loads a WASM
component from a URL and invokes its `evaluate` export.

Component runtime: [webassembly4j](https://github.com/tegmentum/webassembly4j)
(wasmtime provider). Component ABI shared with the Stardog and Jena bindings —
the WIT world at `src/main/wit/webfunction.wit` is package
`stardog:webfunction@0.2.0` (kept as a cross-framework namespace so the same
`.wasm` component can be invoked from any of the three).

## Usage

Auto-registered via `META-INF/services/org.eclipse.rdf4j.query.algebra.evaluation.function.Function`
on the classpath. Then in SPARQL:

```sparql
PREFIX wf: <http://tegmentum.ai/ns/webfunction/>
SELECT ?result WHERE {
  BIND(wf:call(<file:/path/to/component.wasm>, "stardog") AS ?result)
}
```

## Config (system properties)

- `webfunctions.engine.provider` (default `wasmtime`)
- `webfunctions.engine.id`
- `webfunctions.fuel.limit`
- `webfunctions.memory.max.bytes`
- `webfunctions.timeout.millis`
- `webfunctions.exec.max.millis`
- `webfunctions.max.instances`
- `webfunctions.table.max.elements`

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

## Performance

- Shared static `Engine` built once from `WebFunctionConfig` on first `wf:call`.
- `ConcurrentHashMap<URL, Component>` caches compiled components per URL. Repeat
  calls to the same wasm skip download + compile; only the per-call
  `ComponentInstance` is fresh.
- Cost: `webfunctions.*` system properties are read once at first use — changing
  them mid-run has no effect. Test-only `Rdf4jWasmInstance.resetCache()` drops
  shared state for isolation.
- Bench (Darwin aarch64, `to_upper` component, warm cache):
  - `evaluate`: ~17 µs/op (59k ops/s)
  - `instantiate`: ~268 µs/op
- Datatype IRIs are interned in a static `ConcurrentHashMap` because
  RDF4J's `SimpleValueFactory.createIRI` allocates a fresh `SimpleIRI` per
  call (Jena's `TypeMapper` interns equivalent datatypes for free).
  Without this cache, per-literal `createIRI` was ~half of the evaluate hot
  path's allocations — the fix drops evaluate from ~95 µs/op to ~17 µs/op.

## Config (system properties)

- `webfunctions.engine.provider` (default `wasmtime`)
- `webfunctions.engine.id`
- `webfunctions.fuel.limit`
- `webfunctions.memory.max.bytes`
- `webfunctions.timeout.millis`
- `webfunctions.exec.max.millis`
- `webfunctions.max.instances`
- `webfunctions.table.max.elements`

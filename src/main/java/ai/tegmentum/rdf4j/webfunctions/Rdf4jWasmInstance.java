package ai.tegmentum.rdf4j.webfunctions;

import ai.tegmentum.webassembly4j.api.Component;
import ai.tegmentum.webassembly4j.api.ComponentInstance;
import ai.tegmentum.webassembly4j.api.DefaultLinkingContext;
import ai.tegmentum.webassembly4j.api.Engine;
import ai.tegmentum.wasmtime4j.wit.WitResult;
import ai.tegmentum.wasmtime4j.wit.WitString;
import ai.tegmentum.wasmtime4j.wit.WitU64;
import ai.tegmentum.wasmtime4j.wit.WitValue;

import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.util.List;

/**
 * Component-model WASM instance for the RDF4J binding.
 */
public final class Rdf4jWasmInstance implements Closeable {

    private Engine engine;
    private Component component;
    private ComponentInstance instance;
    private boolean closed;

    public Rdf4jWasmInstance(final URL wasmUrl) throws IOException {
        this.engine = WebFunctionConfig.buildEngine();
        if (!engine.capabilities().supportsComponents()) {
            engine.close();
            throw new IllegalStateException("engine '"
                    + engine.info().engineId() + "' does not support components");
        }
        this.component = engine.loadComponent(readAll(wasmUrl));
        this.instance = component.instantiate(DefaultLinkingContext.builder().build());
    }

    public List<WitValueMarshaller.Row> evaluate(final ValueFactory vf, final Value... args) throws IOException {
        final WitValue result = (WitValue) instance.invokeWit(
                "evaluate", WitValueMarshaller.toWitArgs(args));
        return WitValueMarshaller.bindingSetsFromWit(unwrapOk(result), vf);
    }

    public void aggregateStep(final Value[] args, final long multiplicity) throws IOException {
        final WitValue result = (WitValue) instance.invokeWit(
                "aggregate-step",
                WitValueMarshaller.toWitArgs(args),
                WitU64.of(multiplicity));
        unwrapOk(result);
    }

    public List<WitValueMarshaller.Row> aggregateFinish(final ValueFactory vf) throws IOException {
        final WitValue result = (WitValue) instance.invokeWit("aggregate-finish");
        return WitValueMarshaller.bindingSetsFromWit(unwrapOk(result), vf);
    }

    public List<WitValueMarshaller.Row> doc(final ValueFactory vf) {
        final WitValue result = (WitValue) instance.invokeWit("doc");
        return WitValueMarshaller.bindingSetsFromWit(result, vf);
    }

    private static WitValue unwrapOk(final WitValue result) throws IOException {
        if (!(result instanceof WitResult wr)) {
            throw new IOException("Unexpected component return type: "
                    + (result == null ? "null" : result.getClass().getName()));
        }
        if (wr.isErr()) {
            throw new IOException(wr.getErr()
                    .map(v -> ((WitString) v).getValue())
                    .orElse("component returned err with no payload"));
        }
        return wr.getOk().orElse(null);
    }

    private static byte[] readAll(final URL url) throws IOException {
        final URLConnection conn = url.openConnection();
        conn.setConnectTimeout(240000);
        conn.setReadTimeout(240000);
        conn.connect();
        try (java.io.InputStream in = conn.getInputStream();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            in.transferTo(out);
            return out.toByteArray();
        }
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (component != null) component.close();
        if (engine != null) engine.close();
        instance = null;
        component = null;
        engine = null;
        closed = true;
    }
}

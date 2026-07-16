package ai.tegmentum.rdf4j.webfunctions;

import ai.tegmentum.wasmtime4j.wit.WitFloat32;
import ai.tegmentum.wasmtime4j.wit.WitList;
import ai.tegmentum.wasmtime4j.wit.WitType;
import ai.tegmentum.wasmtime4j.wit.WitValue;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.XSD;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Regression coverage for
 * {@link WitValueMarshaller#bindingSetsFromWit(WitValue, ValueFactory, String)}
 * — the wf_sagegraph {@code embed} return decode path. The guest hands
 * back a bare {@code list<float32>}; the substrate flattens it into a
 * single-row binding-sets that projects {@code ?node} (from the
 * decode-time input-node-iri hint) and {@code ?embedding} (JSON string).
 *
 * <p>Mirrors {@code oxigraph-wf/src/wf_call.rs::embedding_vector_tests}
 * and the Jena plugin's {@code EmbeddingVectorDecoderTest}.
 */
public class EmbeddingVectorDecoderTest {

    private static final ValueFactory VF = SimpleValueFactory.getInstance();

    @Test
    public void floatListProjectsNodeAndEmbeddingColumns() {
        final WitList list = floatList(4.0f, 3.5f, 4.0f, 0.0f);
        final List<WitValueMarshaller.Row> rows =
                WitValueMarshaller.bindingSetsFromWit(list, VF,
                        "http://example.com/alice");
        assertEquals(1, rows.size());
        final WitValueMarshaller.Row row = rows.get(0);
        assertEquals(Arrays.asList("node", "embedding"), row.vars);
        final Value node = row.values.get(0);
        assertNotNull(node);
        assertTrue(node instanceof IRI);
        assertEquals("http://example.com/alice", node.stringValue());
        final Value embedding = row.values.get(1);
        assertNotNull(embedding);
        assertTrue(embedding instanceof Literal);
        final Literal lit = (Literal) embedding;
        assertEquals("[4, 3.5, 4, 0]", lit.getLabel());
        assertEquals(XSD.STRING, lit.getDatatype());
    }

    @Test
    public void floatListWithoutNodeHintEmitsEmbeddingOnly() {
        final WitList list = floatList(1.0f, -0.25f);
        final List<WitValueMarshaller.Row> rows =
                WitValueMarshaller.bindingSetsFromWit(list, VF, null);
        assertEquals(1, rows.size());
        final WitValueMarshaller.Row row = rows.get(0);
        assertEquals(java.util.Collections.singletonList("embedding"), row.vars);
        // Java `Float.toString(-0.25f)` is `-0.25`; matches the Rust
        // `format!("{}", -0.25f32)` output byte-for-byte.
        assertEquals("[1, -0.25]",
                ((Literal) row.values.get(0)).getLabel());
    }

    @Test
    public void nonUriHintDropsNodeColumn() {
        final WitList list = floatList(0.5f);
        final List<WitValueMarshaller.Row> rows =
                WitValueMarshaller.bindingSetsFromWit(list, VF, "not an iri");
        assertEquals(1, rows.size());
        assertEquals(java.util.Collections.singletonList("embedding"),
                rows.get(0).vars);
    }

    @Test
    public void emptyListRoutesThroughHitDecoder() {
        // Empty list defaults to hit-list decode → empty rows.
        // Preserves the historical contract for callers that never
        // emit a wf_sagegraph return.
        final WitList list = WitList.empty(WitType.createFloat32());
        final List<WitValueMarshaller.Row> rows =
                WitValueMarshaller.bindingSetsFromWit(list, VF, null);
        assertTrue("empty list → empty binding-set", rows.isEmpty());
    }

    @Test
    public void integerValuedComponentsPrintWithoutTrailingDotZero() {
        // Byte-parity target with the Rust substrate.
        final String s = WitValueMarshaller.embeddingVectorToJson(
                new float[]{4.0f, 3.5f, 4.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f});
        assertEquals("[4, 3.5, 4, 0, 0, 0, 0, 0]", s);
    }

    private static WitList floatList(final float... xs) {
        final List<WitValue> elems = new ArrayList<>(xs.length);
        for (float x : xs) elems.add(WitFloat32.of(x));
        return WitList.of(elems);
    }
}

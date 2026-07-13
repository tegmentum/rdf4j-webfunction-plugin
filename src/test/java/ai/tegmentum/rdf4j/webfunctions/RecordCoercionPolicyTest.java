package ai.tegmentum.rdf4j.webfunctions;

import ai.tegmentum.wasmtime4j.component.ComponentType;
import ai.tegmentum.wasmtime4j.component.ComponentTypeDescriptor;
import ai.tegmentum.wasmtime4j.wit.WitBool;
import ai.tegmentum.wasmtime4j.wit.WitList;
import ai.tegmentum.wasmtime4j.wit.WitS32;
import ai.tegmentum.wasmtime4j.wit.WitString;
import ai.tegmentum.wasmtime4j.wit.WitValue;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static ai.tegmentum.rdf4j.webfunctions.RecordCoercionPolicy.FieldShape;
import static ai.tegmentum.rdf4j.webfunctions.RecordCoercionPolicy.JsonShape;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Regression tests for the bare-arg record-coercion policy that
 * unblocks Gap A in
 * {@code wf-conformance/cases/fulltext_document_corpus.toml}. Mirrors
 * {@code oxigraph-wf/src/wf_call.rs::bare_arg_synthesis_tests} and the
 * Jena port's {@code RecordCoercionPolicyTest}.
 */
public class RecordCoercionPolicyTest {

    @Test
    public void jsonShapeClassifiesScalars() {
        assertEquals(JsonShape.BOOL, RecordCoercionPolicy.jsonShapeOf(JsonParser.parseString("true")));
        assertEquals(JsonShape.NUMBER, RecordCoercionPolicy.jsonShapeOf(JsonParser.parseString("10")));
        assertEquals(JsonShape.STRING, RecordCoercionPolicy.jsonShapeOf(JsonParser.parseString("\"hi\"")));
        assertEquals(JsonShape.ARRAY, RecordCoercionPolicy.jsonShapeOf(JsonParser.parseString("[]")));
        assertEquals(JsonShape.OBJECT, RecordCoercionPolicy.jsonShapeOf(JsonParser.parseString("{}")));
    }

    @Test
    public void fieldShapeCategorisesTypes() {
        assertEquals(FieldShape.INT, RecordCoercionPolicy.fieldShapeOf(ComponentType.S32));
        assertEquals(FieldShape.INT, RecordCoercionPolicy.fieldShapeOf(ComponentType.U64));
        assertEquals(FieldShape.FLOAT, RecordCoercionPolicy.fieldShapeOf(ComponentType.F64));
        assertEquals(FieldShape.BOOL, RecordCoercionPolicy.fieldShapeOf(ComponentType.BOOL));
        assertEquals(FieldShape.LIST, RecordCoercionPolicy.fieldShapeOf(ComponentType.LIST));
        assertEquals(FieldShape.OPTION, RecordCoercionPolicy.fieldShapeOf(ComponentType.OPTION));
    }

    @Test
    public void shapeAcceptsIntFieldTakesNumber() {
        assertTrue(RecordCoercionPolicy.shapeAccepts(FieldShape.INT, JsonShape.NUMBER));
        assertTrue(RecordCoercionPolicy.shapeAccepts(FieldShape.FLOAT, JsonShape.NUMBER));
        assertFalse(RecordCoercionPolicy.shapeAccepts(FieldShape.BOOL, JsonShape.NUMBER));
        assertFalse(RecordCoercionPolicy.shapeAccepts(FieldShape.LIST, JsonShape.NUMBER));
    }

    /**
     * Core Gap A regression: bare int against a query-opts-shaped
     * record lands in the {@code limit} slot; {@code fields: []} and
     * {@code highlight: false} synthesize.
     */
    @Test
    public void placesLimitAndSynthesizesRemainingQueryOptsFields() {
        final Map<String, ComponentTypeDescriptor> fields = new LinkedHashMap<>();
        fields.put("limit", ComponentTypeDescriptor.s32());
        fields.put("fields", ComponentTypeDescriptor.list(ComponentTypeDescriptor.string()));
        fields.put("highlight", ComponentTypeDescriptor.bool());

        final String placed = Rdf4jWasmInstance.placeBareArgIntoRecord(
                JsonParser.parseString("10"), fields);
        assertEquals("limit", placed);

        final WitValue emptyFields = Rdf4jWasmInstance.defaultValFor(fields.get("fields"));
        assertTrue("fields default should be list, got " + emptyFields.getClass(), emptyFields instanceof WitList);
        assertEquals(0, ((WitList) emptyFields).size());

        final WitValue highlightFalse = Rdf4jWasmInstance.defaultValFor(fields.get("highlight"));
        assertTrue(highlightFalse instanceof WitBool);
        assertFalse(((WitBool) highlightFalse).getValue());
    }

    @Test
    public void ambiguousBareArgFailsWithBothCandidatesNamed() {
        final Map<String, ComponentTypeDescriptor> fields = new LinkedHashMap<>();
        fields.put("min", ComponentTypeDescriptor.s32());
        fields.put("max", ComponentTypeDescriptor.s32());

        final IllegalArgumentException iae = assertThrows(
                IllegalArgumentException.class,
                () -> Rdf4jWasmInstance.placeBareArgIntoRecord(JsonParser.parseString("10"), fields));
        final String msg = iae.getMessage();
        assertNotNull(msg);
        assertTrue(msg, msg.contains("ambiguous"));
        assertTrue(msg, msg.contains("min"));
        assertTrue(msg, msg.contains("max"));
    }

    @Test
    public void noMatchingFieldReportsCandidatesConsidered() {
        final Map<String, ComponentTypeDescriptor> fields = new LinkedHashMap<>();
        fields.put("fields", ComponentTypeDescriptor.list(ComponentTypeDescriptor.string()));
        fields.put("highlight", ComponentTypeDescriptor.bool());

        final IllegalArgumentException iae = assertThrows(
                IllegalArgumentException.class,
                () -> Rdf4jWasmInstance.placeBareArgIntoRecord(JsonParser.parseString("10"), fields));
        final String msg = iae.getMessage();
        assertTrue(msg, msg.contains("does not match any"));
    }

    @Test
    public void defaultValForScalarTypes() {
        final WitValue s32Zero = Rdf4jWasmInstance.defaultValFor(ComponentTypeDescriptor.s32());
        assertTrue(s32Zero instanceof WitS32);
        assertEquals(0, ((WitS32) s32Zero).getValue());

        final WitValue emptyString = Rdf4jWasmInstance.defaultValFor(ComponentTypeDescriptor.string());
        assertTrue(emptyString instanceof WitString);
        assertEquals("", ((WitString) emptyString).getValue());
    }
}

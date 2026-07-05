package ai.tegmentum.rdf4j.webfunctions;

import ai.tegmentum.wasmtime4j.wit.WitList;
import ai.tegmentum.wasmtime4j.wit.WitOption;
import ai.tegmentum.wasmtime4j.wit.WitRecord;
import ai.tegmentum.wasmtime4j.wit.WitString;
import ai.tegmentum.wasmtime4j.wit.WitType;
import ai.tegmentum.wasmtime4j.wit.WitValue;
import ai.tegmentum.wasmtime4j.wit.WitVariant;

import org.eclipse.rdf4j.model.BNode;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.vocabulary.XSD;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Marshalling between RDF4J {@link Value} and the WIT value model declared in
 * {@code src/main/wit/webfunction.wit}. Package name is {@code
 * stardog:webfunction@0.2.0} — a shared cross-framework namespace so components
 * are portable across the Stardog, Jena, and RDF4J bindings.
 */
public final class WitValueMarshaller {

    static final WitType LITERAL_TYPE;
    static final WitType VALUE_TYPE;
    static final WitType BINDING_TYPE;
    static final WitType BINDING_SETS_TYPE;
    static final WitType ACCURACY_TYPE;
    static final WitType CARDINALITY_TYPE;

    static {
        final Map<String, WitType> literalFields = new LinkedHashMap<>();
        literalFields.put("label", WitType.createString());
        literalFields.put("datatype", WitType.createString());
        literalFields.put("lang", WitType.option(WitType.createString()));
        LITERAL_TYPE = WitType.record("literal", literalFields);

        final Map<String, Optional<WitType>> valueCases = new LinkedHashMap<>();
        valueCases.put("iri", Optional.of(WitType.createString()));
        valueCases.put("literal", Optional.of(LITERAL_TYPE));
        valueCases.put("bnode", Optional.of(WitType.createString()));
        VALUE_TYPE = WitType.variant("value", valueCases);

        final Map<String, WitType> bindingFields = new LinkedHashMap<>();
        bindingFields.put("name", WitType.createString());
        bindingFields.put("value", VALUE_TYPE);
        BINDING_TYPE = WitType.record("binding", bindingFields);

        final Map<String, WitType> bindingSetsFields = new LinkedHashMap<>();
        bindingSetsFields.put("vars", WitType.list(WitType.createString()));
        bindingSetsFields.put("rows", WitType.list(WitType.list(BINDING_TYPE)));
        BINDING_SETS_TYPE = WitType.record("binding-sets", bindingSetsFields);

        ACCURACY_TYPE = WitType.enumType("accuracy", Arrays.asList(
                "verified", "injected", "accurate", "mostly-accurate",
                "probably-accurate", "possibly-off", "probably-off", "random"));

        final Map<String, WitType> cardinalityFields = new LinkedHashMap<>();
        cardinalityFields.put("value", WitType.createFloat64());
        cardinalityFields.put("accuracy", ACCURACY_TYPE);
        CARDINALITY_TYPE = WitType.record("cardinality", cardinalityFields);
    }

    private WitValueMarshaller() {}

    // ---- RDF4J → WIT --------------------------------------------------------

    public static WitValue toWit(final Value value) {
        if (value instanceof IRI iri) {
            return WitVariant.of(VALUE_TYPE, "iri", witString(iri.stringValue()));
        }
        if (value instanceof BNode bnode) {
            return WitVariant.of(VALUE_TYPE, "bnode", witString(bnode.getID()));
        }
        if (value instanceof Literal literal) {
            return WitVariant.of(VALUE_TYPE, "literal", literalToWit(literal));
        }
        throw new IllegalArgumentException("Unsupported Value kind: " + value.getClass().getName());
    }

    private static WitRecord literalToWit(final Literal literal) {
        final WitType optionStringType = WitType.option(WitType.createString());
        final Optional<String> lang = literal.getLanguage();
        final String datatype = literal.getDatatype() != null
                ? literal.getDatatype().stringValue()
                : XSD.STRING.stringValue();
        return WitRecord.builder()
                .field("label", witString(literal.getLabel()))
                .field("datatype", witString(datatype))
                .field("lang", lang.isPresent()
                        ? WitOption.some(optionStringType, witString(lang.get()))
                        : WitOption.none(optionStringType))
                .build();
    }

    public static WitList toWitArgs(final Value[] args) {
        if (args.length == 0) return WitList.empty(VALUE_TYPE);
        final List<WitValue> elems = new ArrayList<>(args.length);
        for (Value v : args) elems.add(toWit(v));
        return WitList.of(elems);
    }

    private static WitString witString(final String s) {
        try {
            return WitString.of(s);
        } catch (ai.tegmentum.wasmtime4j.exception.ValidationException e) {
            throw new IllegalArgumentException("invalid UTF-8 string for WIT: " + s, e);
        }
    }

    // ---- WIT → RDF4J --------------------------------------------------------

    public static Value valueFromWit(final WitValue witValue, final ValueFactory vf) {
        final WitVariant variant = (WitVariant) witValue;
        switch (variant.getCaseName()) {
            case "iri":
                return vf.createIRI(
                        ((WitString) variant.getPayload()
                                .orElseThrow(() -> missingPayload("iri"))).getValue());
            case "bnode":
                return vf.createBNode(
                        ((WitString) variant.getPayload()
                                .orElseThrow(() -> missingPayload("bnode"))).getValue());
            case "literal":
                return literalFromWit((WitRecord) variant.getPayload()
                        .orElseThrow(() -> missingPayload("literal")), vf);
            default:
                throw new IllegalArgumentException("Unknown value case: " + variant.getCaseName());
        }
    }

    private static IllegalArgumentException missingPayload(final String kase) {
        return new IllegalArgumentException("value variant '" + kase + "' is missing payload");
    }

    private static Literal literalFromWit(final WitRecord record, final ValueFactory vf) {
        final String label = ((WitString) record.getField("label")).getValue();
        final String datatype = ((WitString) record.getField("datatype")).getValue();
        final Optional<Object> lang = ((WitOption) record.getField("lang")).toJava();
        if (lang.isPresent()) {
            return vf.createLiteral(label, (String) lang.get());
        }
        return vf.createLiteral(label, vf.createIRI(datatype));
    }

    /**
     * One row of a WIT {@code binding-sets} value — vars in declared order,
     * corresponding RDF4J values (null when unbound).
     */
    public static final class Row {
        public final List<String> vars;
        public final List<Value> values;
        Row(final List<String> vars, final List<Value> values) {
            this.vars = vars;
            this.values = values;
        }
    }

    public static List<Row> bindingSetsFromWit(final WitValue witValue, final ValueFactory vf) {
        final WitRecord record = (WitRecord) witValue;
        final List<String> vars = new ArrayList<>();
        for (WitValue v : ((WitList) record.getField("vars")).getElements()) {
            vars.add(((WitString) v).getValue());
        }
        final List<Row> rows = new ArrayList<>();
        for (WitValue rowVal : ((WitList) record.getField("rows")).getElements()) {
            final List<Value> byName = new ArrayList<>(java.util.Collections.nCopies(vars.size(), null));
            for (WitValue bindingVal : ((WitList) rowVal).getElements()) {
                final WitRecord binding = (WitRecord) bindingVal;
                final String name = ((WitString) binding.getField("name")).getValue();
                final Value value = valueFromWit(binding.getField("value"), vf);
                final int idx = vars.indexOf(name);
                if (idx >= 0) byName.set(idx, value);
            }
            rows.add(new Row(vars, byName));
        }
        return rows;
    }
}

package ai.tegmentum.rdf4j.webfunctions;

import ai.tegmentum.wasmtime4j.wit.WitFloat32;
import ai.tegmentum.wasmtime4j.wit.WitFloat64;
import ai.tegmentum.wasmtime4j.wit.WitList;
import ai.tegmentum.wasmtime4j.wit.WitOption;
import ai.tegmentum.wasmtime4j.wit.WitRecord;
import ai.tegmentum.wasmtime4j.wit.WitS32;
import ai.tegmentum.wasmtime4j.wit.WitS64;
import ai.tegmentum.wasmtime4j.wit.WitString;
import ai.tegmentum.wasmtime4j.wit.WitTuple;
import ai.tegmentum.wasmtime4j.wit.WitType;
import ai.tegmentum.wasmtime4j.wit.WitU32;
import ai.tegmentum.wasmtime4j.wit.WitU64;
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
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

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

    // Cache datatype IRIs so repeated literal round-trips don't allocate a
    // fresh SimpleIRI per call. RDF4J's SimpleValueFactory.createIRI has no
    // built-in interning (Jena's TypeMapper caches equivalent Node datatypes),
    // and profiling showed this was ~half of the evaluate-hot-path allocations.
    private static final ConcurrentHashMap<String, IRI> DATATYPE_CACHE = new ConcurrentHashMap<>();

    private static Literal literalFromWit(final WitRecord record, final ValueFactory vf) {
        final String label = ((WitString) record.getField("label")).getValue();
        final String datatype = ((WitString) record.getField("datatype")).getValue();
        final Optional<Object> lang = ((WitOption) record.getField("lang")).toJava();
        if (lang.isPresent()) {
            return vf.createLiteral(label, (String) lang.get());
        }
        final IRI dt = DATATYPE_CACHE.computeIfAbsent(datatype, vf::createIRI);
        return vf.createLiteral(label, dt);
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
        // Domain guests (wf_fulltext, wf_document) declare their own
        // WIT world and return `list<hit>` rather than the substrate's
        // `binding-sets { vars, rows }` record. Coerce hit-records into
        // binding-sets at decode time; see `hitListToRows` for the
        // projection rules.
        if (witValue instanceof WitList) {
            return hitListToRows((WitList) witValue, vf);
        }
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

    /**
     * Coerce a {@code list<hit>} value into binding-sets Rows. Mirror
     * of {@code oxigraph-wf/src/wf_call.rs::decode_hit_list} and the
     * Jena plugin's equivalent helper.
     *
     * <p>Projection rules:
     * <ul>
     *   <li>Each hit becomes one row.</li>
     *   <li>Top-level scalar fields (doc, score, snippet, lang, ...)
     *       become columns of the same name.</li>
     *   <li>{@code hit.fields[k]} tuples become their own columns.</li>
     *   <li>URI-shaped strings project as IRIs; otherwise as
     *       {@code xsd:string} literals.</li>
     *   <li>The {@code doc} column prefers a URI-shaped
     *       {@code subject} sidecar over {@code hit.doc} when the
     *       top-level value isn't URI-shaped.</li>
     *   <li>Option-typed fields drop when None.</li>
     *   <li>Numeric score projects as {@code xsd:double}.</li>
     * </ul>
     * See wf-conformance/docs/design/wf-fulltext.md §11.
     */
    private static List<Row> hitListToRows(final WitList hits, final ValueFactory vf) {
        final TreeSet<String> varSet = new TreeSet<>();
        final List<LinkedHashMap<String, Value>> perRow = new ArrayList<>();

        for (WitValue hitVal : hits.getElements()) {
            if (!(hitVal instanceof WitRecord)) continue;
            final WitRecord hit = (WitRecord) hitVal;
            final Map<String, WitValue> fields = hit.getFields();

            final List<Map.Entry<String, String>> innerFields = new ArrayList<>();
            if (fields.get("fields") instanceof WitList) {
                for (WitValue item : ((WitList) fields.get("fields")).getElements()) {
                    final Map.Entry<String, String> pair = stringTuple(item);
                    if (pair != null) innerFields.add(pair);
                }
            }

            final LinkedHashMap<String, Value> row = new LinkedHashMap<>();

            boolean docUsedSubject = false;
            if (fields.get("doc") instanceof WitString) {
                final String docStr = ((WitString) fields.get("doc")).getValue();
                final String[] picked = pickDoc(docStr, innerFields);
                docUsedSubject = "1".equals(picked[1]);
                row.put("doc", scalarToValue(picked[0], vf));
                varSet.add("doc");
            }

            final Double score = numericField(fields.get("score"));
            if (score != null) {
                row.put("score", vf.createLiteral(score.toString(), XSD.DOUBLE));
                varSet.add("score");
            }

            for (Map.Entry<String, WitValue> e : fields.entrySet()) {
                if (e.getKey().equals("doc")
                        || e.getKey().equals("score")
                        || e.getKey().equals("fields")) continue;
                final String s = flattenOptionalString(e.getValue());
                if (s == null) continue;
                row.put(e.getKey(), scalarToValue(s, vf));
                varSet.add(e.getKey());
            }

            for (Map.Entry<String, String> f : innerFields) {
                if (f.getKey().equals("subject") && docUsedSubject) continue;
                row.put(f.getKey(), scalarToValue(f.getValue(), vf));
                varSet.add(f.getKey());
            }

            perRow.add(row);
        }

        final List<String> vars = new ArrayList<>(varSet);
        final List<Row> rows = new ArrayList<>(perRow.size());
        for (LinkedHashMap<String, Value> src : perRow) {
            final List<Value> byIndex = new ArrayList<>(
                    java.util.Collections.nCopies(vars.size(), null));
            for (Map.Entry<String, Value> e : src.entrySet()) {
                final int idx = vars.indexOf(e.getKey());
                if (idx >= 0) byIndex.set(idx, e.getValue());
            }
            rows.add(new Row(vars, byIndex));
        }
        return rows;
    }

    private static String[] pickDoc(
            final String topDoc,
            final List<Map.Entry<String, String>> innerFields) {
        if (isUriShaped(topDoc)) return new String[] { topDoc, "0" };
        for (Map.Entry<String, String> e : innerFields) {
            if (e.getKey().equals("subject") && isUriShaped(e.getValue())) {
                return new String[] { e.getValue(), "1" };
            }
        }
        return new String[] { topDoc, "0" };
    }

    private static boolean isUriShaped(final String s) {
        return s.startsWith("http://")
                || s.startsWith("https://")
                || s.startsWith("urn:")
                || s.startsWith("file://")
                || s.startsWith("ipfs://")
                || s.startsWith("ipns://")
                || s.startsWith("sirix://");
    }

    private static Value scalarToValue(final String s, final ValueFactory vf) {
        if (isUriShaped(s)) return vf.createIRI(s);
        return vf.createLiteral(s, XSD.STRING);
    }

    private static Map.Entry<String, String> stringTuple(final WitValue v) {
        if (!(v instanceof WitTuple)) return null;
        final List<WitValue> elems = ((WitTuple) v).getElements();
        if (elems.size() != 2) return null;
        if (!(elems.get(0) instanceof WitString)) return null;
        if (!(elems.get(1) instanceof WitString)) return null;
        return new java.util.AbstractMap.SimpleEntry<>(
                ((WitString) elems.get(0)).getValue(),
                ((WitString) elems.get(1)).getValue());
    }

    private static Double numericField(final WitValue v) {
        if (v instanceof WitFloat64) return ((WitFloat64) v).getValue();
        if (v instanceof WitFloat32) return (double) ((WitFloat32) v).getValue();
        if (v instanceof WitS64) return (double) ((WitS64) v).getValue();
        if (v instanceof WitU64) return (double) ((WitU64) v).getValue();
        if (v instanceof WitS32) return (double) ((WitS32) v).getValue();
        if (v instanceof WitU32) return (double) ((WitU32) v).getValue();
        return null;
    }

    private static String flattenOptionalString(final WitValue v) {
        if (v instanceof WitString) return ((WitString) v).getValue();
        if (v instanceof WitOption) {
            final Optional<WitValue> inner = ((WitOption) v).getValue();
            if (inner.isPresent() && inner.get() instanceof WitString) {
                return ((WitString) inner.get()).getValue();
            }
            return null;
        }
        return null;
    }
}

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
import ai.tegmentum.wasmtime4j.wit.WitU8;
import ai.tegmentum.wasmtime4j.wit.WitValue;
import ai.tegmentum.wasmtime4j.wit.WitVariant;

import org.eclipse.rdf4j.model.BNode;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.vocabulary.XSD;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Marshalling between RDF4J {@link Value} and the WIT value model.
 *
 * <p>The base value model (variant/literal/binding shapes) lives in the
 * tegmentum:webfunction package at src/main/wit/base/types.wit; the
 * Stardog-only accuracy enum and cardinality record live in the
 * stardog:webfunction@0.3.0 overlay at src/main/wit/overlay/planner.wit.
 * The WitType instances below still mirror the pre-split
 * stardog:webfunction@0.2.0 shape verbatim — value is still a 3-arm
 * variant (iri/literal/bnode), literal fields are still label/datatype/
 * lang, binding fields are still name/value. The overlay adopts the
 * base's renamed fields at the WIT layer; the Java-side marshalling
 * intentionally stays on the old shape until the shaded webassembly4j
 * bindings are regenerated against the two-submodule layout (tracked
 * separately — see the plugin's WIT migration follow-ups).
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
        return bindingSetsFromWit(witValue, vf, null);
    }

    /**
     * Full-fidelity overload that also threads an optional
     * {@code inputNodeIri} decode-time context. The decoder uses this
     * only when the guest returns a bare {@code list<float32>} — the
     * wf_sagegraph {@code embed} shape — to bind {@code ?node}
     * alongside the emitted {@code ?embedding} in the same row. Every
     * other return shape ignores the hint and behaves identically to
     * {@link #bindingSetsFromWit(WitValue, ValueFactory)}.
     */
    public static List<Row> bindingSetsFromWit(final WitValue witValue,
                                               final ValueFactory vf,
                                               final String inputNodeIri) {
        // Domain guests (wf_fulltext, wf_document) declare their own
        // WIT world and return `list<hit>` rather than the substrate's
        // `binding-sets { vars, rows }` record. Coerce hit-records into
        // binding-sets at decode time; see `hitListToRows` for the
        // projection rules.
        //
        // wf_sagegraph's `embed` export additionally returns a bare
        // `list<float32>` (raw embedding vector — memo §04).
        // Discriminate on the first element's type: `WitFloat32` →
        // embedding-vector shape, `WitRecord` → hit-list shape. Empty
        // lists fall through to the hit-list path, which produces an
        // empty binding-set — stable behaviour for both callers.
        if (witValue instanceof WitList) {
            final WitList list = (WitList) witValue;
            final List<WitValue> elems = list.getElements();
            if (!elems.isEmpty() && elems.get(0) instanceof WitFloat32) {
                return floatListToRows(elems, vf, inputNodeIri);
            }
            return hitListToRows(list, vf);
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

            // `hit.doc` shape: either a plain `string` (legacy
            // wf_fulltext) or a `doc-ref { id: string,
            // revision: option<u64> }` record (wf_document v1.0+).
            // The record shape carries a bitemporal `revision` signal
            // that we surface as `?revision` (xsd:integer) when Some.
            // Legacy string shape stays wire-compatible. Mirrors
            // `oxigraph-wf/src/wf_call.rs::flatten_hit_doc`.
            boolean docUsedSubject = false;
            final DocRefFlat flat = flattenHitDoc(fields.get("doc"));
            if (flat != null) {
                final String[] picked = pickDoc(flat.id, innerFields);
                docUsedSubject = "1".equals(picked[1]);
                row.put("doc", scalarToValue(picked[0], vf));
                varSet.add("doc");
                if (flat.revision != null) {
                    row.put("revision", vf.createLiteral(
                            flat.revision.toString(), XSD.INTEGER));
                    varSet.add("revision");
                }
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
                final TypedScalar ts = flattenOptionalStringOrBytes(e.getValue());
                if (ts == null) continue;
                row.put(e.getKey(), typedScalarToValue(ts, vf));
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

    /**
     * Decode a bare {@code list<float32>} guest return into Rows. This
     * is the wf_sagegraph {@code embed} return shape (memo §04): a
     * raw embedding vector, one f32 per dimension.
     *
     * <p>Projects a single-row binding-sets:
     * <ul>
     *   <li>vars: {@code ["node", "embedding"]} when {@code inputNodeIri}
     *       is URI-shaped; otherwise just {@code ["embedding"]} (the
     *       caller's outer BGP is expected to re-bind {@code ?node}).</li>
     *   <li>{@code ?embedding} — xsd:string literal holding the JSON-
     *       array serialization of the vector. Integer-valued
     *       components print without a trailing {@code .0}
     *       ({@code 4} not {@code 4.0}) to match the Rust engines'
     *       wire shape.</li>
     *   <li>{@code ?node} — IRI carrying {@code inputNodeIri} when the
     *       hint is URI-shaped.</li>
     * </ul>
     * Mirrors {@code oxigraph-wf/src/wf_call.rs::decode_embedding_vector}
     * and {@code qlever-wf-runtime/src/lib.rs::embedding_vector_to_binding_sets}.
     */
    private static List<Row> floatListToRows(
            final List<WitValue> items,
            final ValueFactory vf,
            final String inputNodeIri) {
        final float[] floats = new float[items.size()];
        for (int i = 0; i < items.size(); i++) {
            final WitValue v = items.get(i);
            if (!(v instanceof WitFloat32)) {
                throw new IllegalArgumentException(
                        "embedding element not a float32: " + v);
            }
            floats[i] = ((WitFloat32) v).getValue();
        }
        final String json = embeddingVectorToJson(floats);
        final Value embedding = vf.createLiteral(json, XSD.STRING);

        final boolean includeNode = inputNodeIri != null && isUriShaped(inputNodeIri);
        final List<String> vars = new ArrayList<>(2);
        final List<Value> values = new ArrayList<>(2);
        if (includeNode) {
            vars.add("node");
            values.add(vf.createIRI(inputNodeIri));
        }
        vars.add("embedding");
        values.add(embedding);
        return java.util.Collections.singletonList(new Row(vars, values));
    }

    /**
     * Serialize an embedding vector as a JSON list literal. Byte-parity
     * target: {@code oxigraph-wf::wf_call::embedding_vector_to_json}
     * and {@code oxigraph-wf::wf_sagegraph_rewrite::embedding_to_json_string}.
     * Integer-valued components print as an integer ({@code 4}), all
     * other finite values fall through to {@link Float#toString(float)}.
     */
    static String embeddingVectorToJson(final float[] v) {
        final StringBuilder s = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) s.append(", ");
            final float x = v[i];
            if (Float.isFinite(x) && x == (long) x) {
                s.append(Long.toString((long) x));
            } else {
                s.append(Float.toString(x));
            }
        }
        s.append(']');
        return s.toString();
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

    /**
     * Flattened view of {@code hit.doc}: the {@code id} string plus an
     * optional {@code revision} (Long — WIT {@code option<u64>}).
     * {@code revision} is {@code null} when the guest left it {@code None}.
     */
    static final class DocRefFlat {
        final String id;
        final Long revision;
        DocRefFlat(final String id, final Long revision) {
            this.id = id;
            this.revision = revision;
        }
    }

    /**
     * Flatten {@code hit.doc} into an {@link DocRefFlat}.
     *
     * <p>Accepts two shapes:
     * <ul>
     *   <li>{@link WitString} — legacy wf_fulltext hit, doc is a plain
     *       string. {@code revision} is {@code null}.</li>
     *   <li>{@link WitRecord} with {@code id: string} (required) and
     *       {@code revision: option<u64>} (optional) — wf_document
     *       v1.0+ {@code doc-ref} shape. Extra fields are ignored
     *       (forward-compat).</li>
     * </ul>
     * Returns {@code null} when {@code doc} is missing or an
     * unrecognised shape; a missing {@code id} on a record throws.
     * Mirrors {@code oxigraph-wf/src/wf_call.rs::flatten_hit_doc}.
     */
    static DocRefFlat flattenHitDoc(final WitValue doc) {
        if (doc instanceof WitString) {
            return new DocRefFlat(((WitString) doc).getValue(), null);
        }
        if (doc instanceof WitRecord) {
            final Map<String, WitValue> fields = ((WitRecord) doc).getFields();
            final WitValue idVal = fields.get("id");
            if (!(idVal instanceof WitString)) {
                throw new IllegalArgumentException(
                        "hit.doc record missing `id` field or wrong type: " + idVal);
            }
            final String id = ((WitString) idVal).getValue();
            Long revision = null;
            final WitValue revVal = fields.get("revision");
            if (revVal instanceof WitOption) {
                final Optional<WitValue> inner = ((WitOption) revVal).getValue();
                if (inner.isPresent()) {
                    final WitValue rv = inner.get();
                    if (rv instanceof WitU64) revision = ((WitU64) rv).getValue();
                    else if (rv instanceof WitS64) {
                        final long n = ((WitS64) rv).getValue();
                        if (n < 0) throw new IllegalArgumentException(
                                "hit.doc.revision negative: " + n);
                        revision = n;
                    } else if (rv instanceof WitU32) {
                        revision = (long) ((WitU32) rv).getValue();
                    } else if (rv instanceof WitS32) {
                        final int n = ((WitS32) rv).getValue();
                        if (n < 0) throw new IllegalArgumentException(
                                "hit.doc.revision negative: " + n);
                        revision = (long) n;
                    } else {
                        throw new IllegalArgumentException(
                                "hit.doc.revision inner not a u64: " + rv);
                    }
                }
            }
            return new DocRefFlat(id, revision);
        }
        return null;
    }

    /**
     * Result of {@link #flattenOptionalStringOrBytes(WitValue)} —
     * either UTF-8 text (routed through
     * {@link #scalarToValue(String, ValueFactory)} so URI-shaped
     * strings still lift to IRIs) or opaque bytes (rendered as an
     * {@code xsd:base64Binary} literal).
     *
     * <p>Mirrors {@code oxigraph-wf::wf_call::TypedScalar} field-for-
     * field so the four engines project identical bindings on the
     * same guest output.
     */
    static final class TypedScalar {
        static final int TEXT = 0;
        static final int BYTES = 1;
        final int kind;
        final String text;
        final byte[] bytes;

        private TypedScalar(final int kind, final String text, final byte[] bytes) {
            this.kind = kind;
            this.text = text;
            this.bytes = bytes;
        }

        static TypedScalar text(final String s) {
            return new TypedScalar(TEXT, s, null);
        }

        static TypedScalar bytes(final byte[] b) {
            return new TypedScalar(BYTES, null, b);
        }
    }

    /**
     * Flatten {@code option<string>} <b>or</b> {@code option<list<u8>>}
     * — the two shapes hit records use for opaque top-level scalar
     * fields.
     *
     * <p>Projection rules:
     * <ul>
     *   <li>{@link WitString} and {@code Option(Some(WitString))} → text.</li>
     *   <li>{@link WitList} of {@link WitU8} and
     *       {@code Option(Some(WitList<u8>))} → UTF-8 decode. If the
     *       bytes are valid UTF-8 the result is text; otherwise raw
     *       bytes for the caller to base64-encode.</li>
     *   <li>Everything else — including {@code Option(None)} — returns
     *       {@code null} so the caller drops the binding.</li>
     * </ul>
     *
     * <p>The UTF-8-first policy trades one strict decode per hit for
     * ergonomic SPARQL access to text bodies (the common case for
     * wf_document corpora); binary bodies still round-trip correctly
     * via base64. See
     * {@code oxigraph-wf::wf_call::flatten_optional_string_or_bytes}
     * for the shared policy rationale.
     *
     * <p>Non-list, non-string option payloads (e.g. {@code option<u32>})
     * are ignored here — those need their own projector.
     */
    static TypedScalar flattenOptionalStringOrBytes(final WitValue v) {
        if (v == null) return null;
        if (v instanceof WitString) {
            return TypedScalar.text(((WitString) v).getValue());
        }
        if (v instanceof WitOption) {
            final Optional<WitValue> inner = ((WitOption) v).getValue();
            if (inner.isEmpty()) return null;
            return flattenOptionalStringOrBytes(inner.get());
        }
        if (v instanceof WitList) {
            final byte[] bytes = collectBytes((WitList) v);
            if (bytes == null) return null;
            return classifyBytes(bytes);
        }
        return null;
    }

    /**
     * UTF-8-first classification with strict decode reporting: valid
     * UTF-8 wins so downstream SPARQL sees an {@code xsd:string};
     * failure falls back to raw bytes for the base64 path.
     *
     * <p>Uses a {@link java.nio.charset.CharsetDecoder} in REPORT mode
     * rather than {@code new String(bytes, UTF_8)} because the latter
     * silently replaces malformed sequences with U+FFFD, which would
     * lie to the caller about the datatype.
     */
    private static TypedScalar classifyBytes(final byte[] bytes) {
        try {
            final String decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            return TypedScalar.text(decoded);
        } catch (CharacterCodingException e) {
            return TypedScalar.bytes(bytes);
        }
    }

    /**
     * A WIT {@code list<u8>} arrives as a {@link WitList} whose every
     * element is a {@link WitU8}. If any element isn't a byte we bail
     * out ({@code null}) so the caller doesn't accidentally coerce an
     * unrelated list shape into a bytes projection.
     */
    private static byte[] collectBytes(final WitList list) {
        final List<WitValue> elems = list.getElements();
        final byte[] out = new byte[elems.size()];
        for (int i = 0; i < elems.size(); i++) {
            final WitValue e = elems.get(i);
            if (!(e instanceof WitU8)) return null;
            out[i] = ((WitU8) e).getValue();
        }
        return out;
    }

    /**
     * Render a {@link TypedScalar} as an RDF4J {@link Value}:
     *   * text → {@link #scalarToValue(String, ValueFactory)}
     *     (URI-shaped strings become IRIs, everything else
     *     {@code xsd:string});
     *   * bytes → {@code xsd:base64Binary} literal.
     */
    private static Value typedScalarToValue(final TypedScalar ts, final ValueFactory vf) {
        if (ts.kind == TypedScalar.TEXT) {
            return scalarToValue(ts.text, vf);
        }
        return vf.createLiteral(
                Base64.getEncoder().encodeToString(ts.bytes),
                XSD.BASE64BINARY);
    }
}

package ai.tegmentum.rdf4j.webfunctions.rewrite;

import java.util.Map;

/**
 * One entry in the shape registry. Carries only the fields the planner
 * rewrite needs: shape name, full descriptor JSON (pass-through to
 * {@code wf_fetch}), the anchor class IRI (for BGPs that assert
 * {@code ?s a <class>}), and the predicate &rarr; column-name index.
 *
 * <p>Java port of {@code oxigraph-wf/src/shape_registry.rs::ShapeEntry}.
 */
public final class ShapeEntry {

    private final String name;
    private final String descriptorJson;
    private final String anchorClass; // nullable
    private final Map<String, String> columnsByPredicate;
    private final String subjectColumnName; // resolved from descriptor, "id" fallback

    public ShapeEntry(final String name,
                      final String descriptorJson,
                      final String anchorClass,
                      final Map<String, String> columnsByPredicate,
                      final String subjectColumnName) {
        this.name = name;
        this.descriptorJson = descriptorJson;
        this.anchorClass = anchorClass;
        this.columnsByPredicate = Map.copyOf(columnsByPredicate);
        this.subjectColumnName = subjectColumnName;
    }

    public String name() { return name; }
    public String descriptorJson() { return descriptorJson; }
    public String anchorClass() { return anchorClass; }
    public Map<String, String> columnsByPredicate() { return columnsByPredicate; }
    public String subjectColumnName() { return subjectColumnName; }
}

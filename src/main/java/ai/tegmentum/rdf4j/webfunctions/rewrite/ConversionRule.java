package ai.tegmentum.rdf4j.webfunctions.rewrite;

import org.eclipse.rdf4j.query.algebra.ValueExpr;

/**
 * A single (target, source, expression) conversion rule loaded from the
 * sink's {@code conversions} table.
 *
 * <p>Java port of {@code oxigraph-wf/src/conversion_registry.rs::ConversionRule}.
 */
public final class ConversionRule {

    private final String targetPredicate;
    private final String sourcePredicate;
    private final String expression;
    /** Deterministic virtual graph IRI where this rule's computed triples appear. */
    private final String graphIri;
    /** Parsed form of {@code expression}; splice into the rewritten algebra. */
    private final ValueExpr parsedExpression;

    public ConversionRule(final String targetPredicate,
                          final String sourcePredicate,
                          final String expression,
                          final String graphIri,
                          final ValueExpr parsedExpression) {
        this.targetPredicate = targetPredicate;
        this.sourcePredicate = sourcePredicate;
        this.expression = expression;
        this.graphIri = graphIri;
        this.parsedExpression = parsedExpression;
    }

    public String targetPredicate()   { return targetPredicate; }
    public String sourcePredicate()   { return sourcePredicate; }
    public String expression()        { return expression; }
    public String graphIri()          { return graphIri; }
    public ValueExpr parsedExpression() { return parsedExpression; }
}

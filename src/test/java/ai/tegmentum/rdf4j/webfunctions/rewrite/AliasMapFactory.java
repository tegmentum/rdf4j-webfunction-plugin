package ai.tegmentum.rdf4j.webfunctions.rewrite;

import java.util.Map;

/**
 * Test helper: build an in-memory {@link AliasMap} without touching
 * SQLite.
 */
final class AliasMapFactory {

    private AliasMapFactory() {}

    static AliasMap of(final String alias, final String canonical) {
        return AliasMap.of(Map.of(alias, canonical));
    }
}

package ai.tegmentum.rdf4j.webfunctions;

import org.junit.Test;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Deterministic tests for the {@code postgres://} URL parser inside
 * {@link PostgresSink}. Kept separate from the end-to-end sink test
 * (which needs a live Postgres and lives in wf-conformance's
 * relational_basic case) so this file always runs green in CI.
 */
public class TestPostgresSinkUrl {

    @Test
    public void parsesUserinfoAndAuthority() throws SQLException {
        PostgresSink.ParsedPostgresUrl p = PostgresSink.ParsedPostgresUrl.parse(
                "postgres://wf:wf@127.0.0.1:5432/wf");
        assertThat(p.user).isEqualTo("wf");
        assertThat(p.password).isEqualTo("wf");
        assertThat(p.jdbcUrl).isEqualTo("jdbc:postgresql://127.0.0.1:5432/wf");
        assertThat(p.queryParams).isEmpty();
    }

    @Test
    public void acceptsPostgresqlSchemeAlias() throws SQLException {
        PostgresSink.ParsedPostgresUrl p = PostgresSink.ParsedPostgresUrl.parse(
                "postgresql://wf:wf@127.0.0.1:5432/wf");
        assertThat(p.jdbcUrl).isEqualTo("jdbc:postgresql://127.0.0.1:5432/wf");
    }

    @Test
    public void liftsQueryParamsIntoConnectionProperties() throws SQLException {
        PostgresSink.ParsedPostgresUrl p = PostgresSink.ParsedPostgresUrl.parse(
                "postgres://wf@127.0.0.1:5432/wf?sslmode=require&connect_timeout=5");
        assertThat(p.user).isEqualTo("wf");
        assertThat(p.password).isNull();
        assertThat(p.queryParams)
                .containsEntry("sslmode", "require")
                .containsEntry("connect_timeout", "5");
    }

    @Test
    public void urlDecodesUserinfo() throws SQLException {
        PostgresSink.ParsedPostgresUrl p = PostgresSink.ParsedPostgresUrl.parse(
                "postgres://u:p%40ss@host:5432/db");
        assertThat(p.user).isEqualTo("u");
        assertThat(p.password).isEqualTo("p@ss");
    }

    @Test
    public void rejectsWrongScheme() {
        Throwable t = catchThrowable(() ->
                PostgresSink.ParsedPostgresUrl.parse("mongodb://host/db"));
        assertThat(t).isInstanceOf(SQLException.class)
                .hasMessageContaining("postgres://");
    }
}

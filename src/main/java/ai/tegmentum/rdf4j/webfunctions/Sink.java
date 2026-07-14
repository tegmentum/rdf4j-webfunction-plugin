package ai.tegmentum.rdf4j.webfunctions;

import ai.tegmentum.wasmtime4j.component.ComponentVal;

import java.util.List;

/**
 * Backend abstraction for the v0.5 {@code sink-*} host imports. A sink is
 * an out-of-graph destination guests write to during materialization and
 * read from during federated query — the {@code wf_materialize} and
 * {@code wf_fetch} portable guests are the two current callers.
 *
 * <p>Handle-based: the guest calls {@code sink-open(url)} once to receive
 * an opaque {@code u32}, then hands that handle to {@code sink-execute}
 * for each query. The outer {@code wf:call} frame closes any handles the
 * guest didn't explicitly close via {@code sink-close}.
 *
 * <p>Kept intentionally narrow. The v0.5 WIT surface only exposes
 * open/execute/close; more can be added when a real use case demands it.
 * SQLite is the reference backend; DuckDB / Postgres / SirixDB drop in
 * behind the same trait as their bindings arrive.
 */
public interface Sink extends AutoCloseable {

    /**
     * Execute an opaque query in the backend's native language, with
     * parameters bound via the backend's placeholder syntax (SQL {@code ?}
     * for SQLite/DuckDB, {@code $1}... for Postgres, Brackit-specific for
     * SirixDB). Returns a WIT {@code binding-sets} record:
     * <ul>
     *   <li>DDL / INSERT / UPDATE / DELETE: empty {@code binding-sets}
     *       (vars=[], rows=[]). Row count is not returned — guests that
     *       need it run {@code SELECT changes()} (SQLite) / {@code RETURNING}
     *       (Postgres/DuckDB) as a follow-up.</li>
     *   <li>SELECT: one row per result, one binding per projected column.
     *       {@code name} is the column name from the query; {@code value}
     *       is the WIT variant carrying the cell's typed value.</li>
     * </ul>
     */
    ComponentVal execute(String query, List<ComponentVal> params) throws Exception;

    @Override
    void close();
}

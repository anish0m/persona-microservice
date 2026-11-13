package com.persona.user.repository;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;

/**
 * Stores idempotency keys in PostgreSQL.
 *
 * <p><b>Why this implementation is written in JDBC and shared by both database
 * profiles.</b> Everywhere else in persona, {@code jdbc} and {@code jpa} each get
 * their own repository. Not here, and the reason is the point of the class:
 * {@link #claim} needs {@code INSERT ... ON CONFLICT DO NOTHING RETURNING}, which
 * is one statement that inserts, detects a conflict, and reports the outcome.
 * JPA has no equivalent — {@code persist} either succeeds or throws after the
 * fact, and catching the constraint violation means the transaction is already
 * poisoned (Day-03's SQLSTATE 25P02: the next query inside that catch fails too).
 *
 * <p>So this is a case where the ORM is the wrong tool, and {@code @Profile}
 * naming both database profiles is cheaper than a delegating class that exists
 * only to say so. Note the shape of the decision: the port stays honest — there
 * are still three profiles and the in-memory one still works — but two of them
 * share an implementation because the distinction they encode is irrelevant to
 * this table. A second class would have been symmetry for its own sake.
 */
@Repository
@Profile({"jdbc", "jpa"})
public class JdbcIdempotencyRepository implements IdempotencyRepository {

    private static final String COLUMNS =
            "scope, idempotency_key, status, request_fingerprint, "
                    + "response_status, response_body, created_at";

    private final JdbcClient jdbc;

    public JdbcIdempotencyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>The whole mechanism is in one statement, and every clause is load
     * bearing.</b>
     *
     * <pre>
     *   INSERT INTO idempotency_keys (...) VALUES (...)
     *   ON CONFLICT (scope, idempotency_key) DO NOTHING
     *   RETURNING idempotency_key
     * </pre>
     *
     * <ul>
     *   <li>{@code ON CONFLICT ... DO NOTHING} turns "a duplicate is an error"
     *       into "a duplicate is an answer". Without it the second caller gets a
     *       {@code DuplicateKeyException}, which is recoverable only by catching
     *       an exception on the happy-ish path and, worse, only after the
     *       transaction has been marked rollback-only.</li>
     *   <li>{@code RETURNING} is what makes the result readable. On an insert it
     *       yields one row; on a conflict it yields <b>zero</b>, because the
     *       {@code DO NOTHING} row was never written. So <em>row count is the
     *       verdict</em> — the same device as PayNest's
     *       {@code UPDATE ... WHERE balance >= ?}, where {@code rows == 0} is the
     *       rejection. Ask and act in one statement and there is no gap.</li>
     * </ul>
     *
     * <p><b>Why the losing path then does a SELECT.</b> It looks like the
     * lookup-then-act race this class exists to avoid, and it is not: by the time
     * we are here the constraint has already decided we lost, and we are only
     * fetching the winner's row to report it. The decision was made by the write.
     * The read that follows cannot change it.
     *
     * <p>That SELECT can still come back empty, and the code must survive it —
     * if the winner's transaction has not committed yet, this transaction's
     * snapshot cannot see the row it just collided with. The constraint sees
     * uncommitted rows; {@code READ COMMITTED} does not. A synthetic
     * {@code IN_PROGRESS} is returned for that window, which is the truth: a
     * claim exists and has no answer yet.
     */
    @Override
    public Optional<IdempotencyRecord> claim(String scope, String key, String fingerprint) {
        int inserted = jdbc.sql("""
                        INSERT INTO idempotency_keys
                            (scope, idempotency_key, status, request_fingerprint)
                        VALUES (?, ?, 'IN_PROGRESS', ?)
                        ON CONFLICT (scope, idempotency_key) DO NOTHING
                        """)
                .param(scope)
                .param(key)
                .param(fingerprint)
                .update();

        if (inserted == 1) {
            return Optional.empty();
        }

        return Optional.of(find(scope, key).orElseGet(() ->
                new IdempotencyRecord(scope, key, IdempotencyRecord.IN_PROGRESS,
                        fingerprint, null, null, null)));
    }

    /**
     * {@inheritDoc}
     *
     * <p>The {@code WHERE} clause carries {@code status = 'IN_PROGRESS'} as well
     * as the key, so a record that has already been completed cannot be
     * overwritten by a late or duplicated call. A stored answer is final; this
     * method can only ever move a row <em>out</em> of the claimed state, never
     * between two finished ones.
     */
    @Override
    public void complete(String scope, String key, String status,
                         int responseStatus, String responseBody) {
        jdbc.sql("""
                        UPDATE idempotency_keys
                           SET status = ?, response_status = ?, response_body = ?
                         WHERE scope = ? AND idempotency_key = ?
                           AND status = 'IN_PROGRESS'
                        """)
                .param(status)
                .param(responseStatus)
                .param(responseBody)
                .param(scope)
                .param(key)
                .update();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Also guarded by {@code status = 'IN_PROGRESS'}: releasing a key whose
     * answer is already stored would delete a response that a client may still
     * be retrying for, and turn a settled request back into an open one.
     */
    @Override
    public void release(String scope, String key) {
        jdbc.sql("""
                        DELETE FROM idempotency_keys
                         WHERE scope = ? AND idempotency_key = ?
                           AND status = 'IN_PROGRESS'
                        """)
                .param(scope)
                .param(key)
                .update();
    }

    @Override
    public Optional<IdempotencyRecord> find(String scope, String key) {
        return jdbc.sql("SELECT " + COLUMNS
                        + " FROM idempotency_keys WHERE scope = ? AND idempotency_key = ?")
                .param(scope)
                .param(key)
                .query(JdbcIdempotencyRepository::mapRow)
                .optional();
    }

    private static IdempotencyRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        Integer responseStatus = rs.getInt("response_status");
        if (rs.wasNull()) {
            responseStatus = null;
        }
        Timestamp createdAt = rs.getTimestamp("created_at");
        return new IdempotencyRecord(
                rs.getString("scope"),
                rs.getString("idempotency_key"),
                rs.getString("status"),
                rs.getString("request_fingerprint"),
                responseStatus,
                rs.getString("response_body"),
                createdAt == null ? null : createdAt.toInstant());
    }
}

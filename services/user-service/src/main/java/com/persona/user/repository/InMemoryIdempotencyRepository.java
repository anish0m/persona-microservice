package com.persona.user.repository;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores idempotency keys in a {@link Map}.
 *
 * <p><b>What this class can and cannot prove — read this before trusting a green
 * test that uses it.</b>
 *
 * <p>It <em>can</em> prove the service's logic: that a replay returns the stored
 * response, that a fingerprint mismatch is rejected, that an in-flight claim
 * produces 409, that a failure releases the key instead of bricking it. That is
 * most of the behaviour and it is worth testing fast.
 *
 * <p>It <b>cannot</b> prove the thing the feature exists for, and in this build
 * the gap is not theoretical. {@code claim} here is atomic within this JVM and
 * only within this JVM, because {@link ConcurrentHashMap#putIfAbsent} coordinates
 * threads that share a heap. <b>user-service is designed to run as several
 * instances</b>, and those instances share no heap — so the duplicate request
 * this class is meant to catch is, in production, most likely being handled by a
 * different process entirely. This map would hold one of the two claims and know
 * nothing of the other.
 *
 * <p>That is the same argument V4 made about {@code synchronized} and the version
 * column, and it generalises: <b>any coordination that lives in application
 * memory is scoped to one instance and invisible to the rest.</b> The database is
 * the only thing all the instances can agree on. A test that passes against this
 * class has demonstrated that the code is correct <em>given</em> an atomic claim —
 * it has not demonstrated that the claim is atomic. Only the integration test
 * against Postgres's primary key can do that.
 *
 * <p>This is the same trap as Day-11's fake-shared-object finding, from the other
 * direction: there, a fake made a real bug inexpressible. Here, a fake makes a
 * real guarantee unfalsifiable. In both cases the fix is to know which claims
 * belong to which test, and to put the guarantee's test where the guarantee
 * actually lives.
 */
@Repository
@Profile("test")
public class InMemoryIdempotencyRepository implements IdempotencyRepository {

    /**
     * Keyed by scope and key together, mirroring V5's composite primary key.
     *
     * <p>A nested map would also work and would be worse: two levels mean two
     * operations, and {@code putIfAbsent} on the inner map is not atomic with
     * respect to creating the outer entry. Flattening the key keeps the claim a
     * single atomic call, which is the one property this class has to preserve
     * to be a useful stand-in at all.
     */
    private record Id(String scope, String key) {}

    private final Map<Id, IdempotencyRecord> records = new ConcurrentHashMap<>();

    /**
     * {@inheritDoc}
     *
     * <p>{@code putIfAbsent} is the in-heap counterpart of
     * {@code ON CONFLICT DO NOTHING}: it inserts and reports the pre-existing
     * value in one indivisible step, returning null when nothing was there. A
     * {@code containsKey} followed by a {@code put} would be the same two-step
     * race the port was designed to make unwriteable — and it would pass every
     * single-threaded test.
     */
    @Override
    public Optional<IdempotencyRecord> claim(String scope, String key, String fingerprint) {
        IdempotencyRecord claim = new IdempotencyRecord(
                scope, key, IdempotencyRecord.IN_PROGRESS,
                fingerprint, null, null, Instant.now());

        return Optional.ofNullable(records.putIfAbsent(new Id(scope, key), claim));
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code computeIfPresent} rather than {@code put}, and the body re-checks
     * {@code IN_PROGRESS} — the same guard the SQL carries in its {@code WHERE}
     * clause. The two implementations have to refuse the same things, or a test
     * written against this one certifies behaviour the real one does not have.
     */
    @Override
    public void complete(String scope, String key, String status,
                         int responseStatus, String responseBody) {
        records.computeIfPresent(new Id(scope, key), (id, existing) ->
                IdempotencyRecord.IN_PROGRESS.equals(existing.status())
                        ? new IdempotencyRecord(existing.scope(), existing.key(), status,
                                existing.requestFingerprint(), responseStatus, responseBody,
                                existing.createdAt())
                        : existing);
    }

    @Override
    public void release(String scope, String key) {
        records.computeIfPresent(new Id(scope, key), (id, existing) ->
                IdempotencyRecord.IN_PROGRESS.equals(existing.status()) ? null : existing);
    }

    @Override
    public Optional<IdempotencyRecord> find(String scope, String key) {
        return Optional.ofNullable(records.get(new Id(scope, key)));
    }

    /** Test support: empties the store between cases. */
    public void clear() {
        records.clear();
    }
}

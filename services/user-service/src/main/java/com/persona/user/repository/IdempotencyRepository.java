package com.persona.user.repository;

import java.util.Optional;

/**
 * What the application can ask of the idempotency key store.
 *
 * <p><b>Read {@link #claim} first — it is the only interesting method here, and
 * the shape of this whole interface is an argument about it.</b>
 *
 * <p>The obvious vocabulary for this port would have been {@code find(key)} and
 * {@code insert(record)}, and it would have been wrong in a way that compiles,
 * passes tests, and loses money. Two copies of the same request both call
 * {@code find}, both get empty, both proceed, and both do the work. That is
 * Day-10's lost update in different clothing: a check and an act with a gap
 * between them, where a competitor fits. An interface that offers those two
 * methods makes the race the caller's problem — and makes it *expressible*,
 * which is worse, because every caller has to remember not to write the obvious
 * thing.
 *
 * <p>So the port does not expose them. {@code claim} asks and acts in one call,
 * and its return value <em>is</em> the answer to "did I win?". There is no
 * sequence of calls a caller can assemble that reintroduces the gap. <b>The
 * safety is in the vocabulary, not in the discipline of whoever uses it.</b>
 *
 * <p><b>Why a port at all, when there is one database.</b> The same reason
 * {@link UserRepository} is one: so there is a seam. A {@code JpaRepository}
 * subinterface would not be a port — it would be an announcement that storage is
 * JPA, and nothing that is not a database could implement it. The in-memory
 * implementation is the proof that this interface describes <em>storage</em>
 * rather than <em>Postgres</em>, and it is also what makes the service testable
 * without a container.
 *
 * <p><b>And a warning this interface cannot enforce.</b> The in-memory
 * implementation can make {@code claim} atomic within one JVM. It cannot make it
 * atomic across two. That difference does not matter for a unit test and matters
 * completely in production, so a green in-memory test is not evidence that the
 * claim is safe — only the integration test against Postgres is. The port is a
 * promise about every profile, and this is the clause the fastest profile cannot
 * keep.
 *
 * <p><b>Why this port matters more in a microservice than in a monolith.</b> In
 * the monolith, the duplicate request has one source: the client decided to
 * retry. Here it has three, and two of them are nobody's decision.
 *
 * <ul>
 *   <li>The <b>gateway</b> that arrives on Day-17 retries on timeout. A read
 *       timeout at the gateway does not cancel the request still running behind
 *       it, so the second copy is dispatched while the first is mid-insert.</li>
 *   <li>A <b>load balancer</b> sends that second copy to a different instance,
 *       so the two copies cannot see each other by any means except this
 *       store.</li>
 *   <li>And the client, as before.</li>
 * </ul>
 *
 * <p>So idempotency in a distributed system is not a hardening measure added
 * after the fact. <b>The topology manufactures the duplicates</b>, and any
 * endpoint with a side effect is incorrect without something like this.
 */
public interface IdempotencyRepository {

    /**
     * Attempts to claim a key for the current request, atomically.
     *
     * <p>Exactly one of two things happens, decided by the store and not by the
     * caller:
     *
     * <ul>
     *   <li><b>Empty</b> — the claim succeeded. This caller is the first, a row
     *       now exists in {@code IN_PROGRESS}, and this caller owns the duty of
     *       doing the work and then calling {@link #complete}.</li>
     *   <li><b>Present</b> — someone already claimed it, and the existing record
     *       is returned so the caller can replay its response, reject it as
     *       still in flight, or detect a fingerprint mismatch.</li>
     * </ul>
     *
     * <p><b>The return convention reads backwards on purpose, and it is worth
     * the friction.</b> Empty means success. A boolean would have been prettier
     * and would have forced a second call to fetch the record, reopening the gap
     * this method exists to close. An {@code Optional} that is empty on a win
     * reads oddly exactly once; a two-call API reads fine and races forever.
     *
     * <p>In SQL this is {@code INSERT ... ON CONFLICT DO NOTHING}, and the
     * primary key from V5 is what arbitrates — at write time, with the row
     * locked. No application-level lookup is involved, so there is nothing to
     * race against.
     *
     * @param scope       who the key belongs to; for signup, the email
     * @param key         the client-supplied {@code Idempotency-Key}
     * @param fingerprint hash of the request body, stored for later comparison
     * @return empty if this caller claimed it; the existing record otherwise
     */
    Optional<IdempotencyRecord> claim(String scope, String key, String fingerprint);

    /**
     * Records the outcome of work this caller claimed.
     *
     * <p>Called after the work has committed, never before — see the service for
     * why that ordering is not negotiable. Moves the row out of
     * {@code IN_PROGRESS} and stores the response so a later retry can be
     * answered without redoing anything.
     *
     * @param status one of {@link IdempotencyRecord#COMPLETED} or
     *               {@link IdempotencyRecord#FAILED}
     */
    void complete(String scope, String key, String status,
                  int responseStatus, String responseBody);

    /**
     * Releases a claim so the key may be used again.
     *
     * <p>For one situation only: the work died in a way that is not an answer —
     * an infrastructure failure, a crash, something where the client deserves to
     * retry rather than to be told "no". Leaving such a row {@code IN_PROGRESS}
     * creates a <b>tombstone</b>: every retry sees a claim, gets 409, and the
     * real answer never arrives for anybody. The key is permanently unusable and
     * nothing is wrong with the request.
     *
     * <p>This is the sharp edge of claim-before-work. The claim must outlive a
     * business failure, or "it failed" cannot be recorded; it must <em>not</em>
     * outlive a crash, or the key is bricked. Only the caller can tell those two
     * apart, which is why this is a separate method and not a rollback.
     */
    void release(String scope, String key);

    /** Reads a record without claiming anything. Diagnostics and tests only. */
    Optional<IdempotencyRecord> find(String scope, String key);
}

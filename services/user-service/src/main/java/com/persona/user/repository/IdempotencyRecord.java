package com.persona.user.repository;

import java.time.Instant;

/**
 * One claimed idempotency key and whatever is known about its outcome.
 *
 * <p><b>Why a record rather than an entity.</b> This is not a domain object.
 * Nothing in persona has business rules about an idempotency key — no validation
 * worth writing, no behaviour, no invariant beyond what the table's constraints
 * already enforce. It is a row, read back so the service can decide between
 * "replay this" and "reject this", and a record says exactly that: data, final,
 * with no pretence of being a model. {@link com.persona.user.model.User} earns its
 * class because it validates itself; this does not.
 *
 * <p><b>Why {@code status} is a String and not an enum.</b> It very nearly was an
 * enum, and the reason it is not is worth keeping: the three values live in a
 * {@code CHECK} constraint in V5, and an enum here would be a second, separate
 * list of them that can silently drift from the first. One of the two has to be
 * the authority. The database is, because it is the thing that can actually
 * refuse a bad value — the Java enum can only refuse values the Java code was
 * going to produce anyway. The constants below name them once on this side.
 *
 * <p><b>On the nullable fields.</b> {@code responseStatus} and {@code responseBody}
 * are null for exactly as long as {@code status} is {@code IN_PROGRESS}, which is
 * not a modelling accident — it is the state being described. A claim exists
 * before its answer does. Code that reads the response without checking the
 * status first is making an assumption the table never promised.
 */
public record IdempotencyRecord(
        String scope,
        String key,
        String status,
        String requestFingerprint,
        Integer responseStatus,
        String responseBody,
        Instant createdAt) {

    /** Claimed, work is running or died mid-flight. No response exists yet. */
    public static final String IN_PROGRESS = "IN_PROGRESS";

    /** The work finished and its response is stored for replay. */
    public static final String COMPLETED = "COMPLETED";

    /**
     * The work ran and failed as a business outcome.
     *
     * <p>Stored rather than deleted, and that is a real choice. Deleting the row
     * would let a retry try again — attractive, until you notice it also means a
     * client can retry its way through a rejection, and that the failure response
     * the first caller never received can no longer be replayed. A failure is an
     * answer. It is kept so it can be given again.
     */
    public static final String FAILED = "FAILED";

    /** True when a stored response exists and may be replayed verbatim. */
    public boolean isReplayable() {
        return COMPLETED.equals(status) || FAILED.equals(status);
    }
}

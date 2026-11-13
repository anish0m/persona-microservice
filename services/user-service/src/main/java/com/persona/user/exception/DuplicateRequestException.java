package com.persona.user.exception;

/**
 * Thrown when an idempotency key is reused with a different request body.
 *
 * <p><b>This is the case where doing nothing would lose data silently.</b> The
 * key says "this is the request I already sent"; the body says it is not. One of
 * the two is a lie, and the server cannot tell which — so every automatic
 * resolution is a guess with a victim:
 *
 * <ul>
 *   <li>Replay the stored response, and the second request is <em>discarded</em>
 *       while its sender is told it succeeded. A signup for a different person
 *       vanishes and returns someone else's user.</li>
 *   <li>Run the new body under the old key, and the key has stopped identifying
 *       anything — two distinct operations now share one record and the second
 *       overwrites the first's stored answer.</li>
 * </ul>
 *
 * <p>Refusing is the only option that destroys nothing. It is also the only one
 * that tells the client the truth, which is that its key generation is broken —
 * a bug worth hearing about early, since the same defect will eventually reuse a
 * key for two requests that the server cannot distinguish at all.
 *
 * <p><b>Why 422 and not 400.</b> The request is syntactically fine: the header is
 * present, the body parses, the fields validate. What is wrong is the
 * relationship between this request and an earlier one, which is semantics, not
 * syntax — exactly what 422 Unprocessable Content is for. And it is not
 * retryable: resending the same bytes with the same key produces the same
 * refusal forever, which is the distinction from
 * {@link RequestInProgressException}'s 409.
 */
public class DuplicateRequestException extends RuntimeException {

    private final String idempotencyKey;

    public DuplicateRequestException(String idempotencyKey) {
        super("This Idempotency-Key was already used with a different request body.");
        this.idempotencyKey = idempotencyKey;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }
}

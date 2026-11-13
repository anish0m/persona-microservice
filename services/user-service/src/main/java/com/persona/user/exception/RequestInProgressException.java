package com.persona.user.exception;

/**
 * Thrown when a retry arrives while the original request is still running.
 *
 * <p><b>Why this is a 409 and not a wait.</b> The key has been claimed, so the
 * work is in flight, but no response has been stored yet — there is nothing to
 * replay. That leaves exactly three options and two of them are wrong. Running
 * the work again produces the duplicate the whole mechanism exists to prevent.
 * Blocking until the original finishes holds a server thread for an unbounded
 * time on behalf of a client that has already demonstrated it is impatient, and
 * turns one slow request into two. Answering "not right now, ask again" is the
 * only one that is both true and cheap.
 *
 * <p><b>Why it is not a 202 Accepted.</b> 202 means "I have taken responsibility
 * for this and it will happen" — which is true, but it is true of the
 * <em>original</em> request, not this one, and the client cannot tell the
 * difference. 409 says the request conflicts with the current state of the
 * resource, and a claim held by an in-flight twin is precisely that.
 *
 * <p>Note the contrast with {@link DuplicateRequestException}, which is also
 * about a reused key and is a 422 instead: there the client did something
 * incorrect, here the client did something reasonable at an awkward moment. A
 * 409 invites a retry. A 422 does not.
 */
public class RequestInProgressException extends RuntimeException {

    private final String idempotencyKey;

    public RequestInProgressException(String idempotencyKey) {
        super("A request with this Idempotency-Key is still being processed. "
                + "Retry shortly.");
        this.idempotencyKey = idempotencyKey;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }
}

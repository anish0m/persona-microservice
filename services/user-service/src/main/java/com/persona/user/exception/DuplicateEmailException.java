package com.persona.user.exception;

/**
 * Thrown when a signup uses an email that is already registered.
 *
 * <p>A separate type from {@link UserNotFoundException} because they are opposite
 * failures deserving opposite responses — 409 Conflict here, 404 there. One
 * shared type would force a string comparison in the handler to tell them apart.
 *
 * <p><b>The race condition is worse here than in the monolith, and worth sitting
 * with.</b> A monolith runs one process, so a lock or a synchronized block could
 * paper over "look, then insert". user-service runs as <em>several</em>
 * processes behind a load balancer. Two signups for the same email can land on
 * two different instances, on two different machines, at the same moment. Neither
 * instance can see the other's memory, so no amount of Java locking helps. Both
 * check, both see nothing, both insert.
 *
 * <p>The only thing both instances share is the database. Which means the
 * {@code UNIQUE} constraint added on Day-03 is not a nice-to-have backing up the
 * application check — <b>it is the sole real guarantee</b>, and the check in the
 * repository is just a way to produce a friendlier error most of the time.
 *
 * <p>This is the general shape of the microservice tax: correctness properties
 * that a single process gets for free must be pushed down to something genuinely
 * shared, or given up.
 */
public class DuplicateEmailException extends RuntimeException {

    private final String email;

    public DuplicateEmailException(String email) {
        super("A user is already registered with email: " + email);
        this.email = email;
    }

    public String getEmail() {
        return email;
    }

    /**
     * Whether this failure's response is worth storing for replay.
     * <b>Day-11.</b>
     *
     * <p><b>Why a business failure is recorded and an infrastructure failure is
     * not.</b> A taken email is a settled answer: run the signup again in an
     * hour and it is still taken, so replaying the stored 409 to a retry is both
     * correct and cheaper than redoing the work. A dropped connection is not an
     * answer — the work might well succeed on a second attempt — so there the
     * idempotency key must be released rather than answered.
     *
     * <p><b>And the distinction is less academic in this build than in the
     * monolith.</b> The failures a user-service call can suffer include a whole
     * category the monolith does not have: the gateway timing out, an instance
     * being drained mid-request, a connection reset between hops. Those are
     * transient by nature, and treating them as settled answers would record a
     * network hiccup as a permanent verdict on the request.
     *
     * <p>Note that this method says nothing about <em>what</em> the response is.
     * No status code, no JSON. Whether a failure is final is a domain fact;
     * rendering it as a 409 is the controller's job.
     */
    public boolean isFinal() {
        return true;
    }
}

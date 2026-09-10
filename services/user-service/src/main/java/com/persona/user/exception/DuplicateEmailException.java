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
}

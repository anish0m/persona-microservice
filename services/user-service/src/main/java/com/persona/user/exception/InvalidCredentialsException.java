package com.persona.user.exception;

/**
 * Thrown when a login attempt fails, for <b>either</b> reason: no such email,
 * or the password does not match. <b>Day-06.</b>
 *
 * <p><b>Deliberately one exception for two different causes.</b>
 * {@link UserNotFoundException} already exists and would be the obvious type
 * for "no such email" — reusing it here was rejected on purpose. A response
 * that is more specific for an unregistered email than for a wrong password
 * lets an attacker enumerate every registered address by trying logins and
 * reading which error comes back, one bit of information leaked per attempt.
 * See {@link com.persona.user.controller.AuthController#login} for where the two
 * causes are deliberately collapsed into this single type before the
 * exception is even constructed.
 *
 * <p>No {@code getEmail()} accessor, unlike {@link DuplicateEmailException}
 * or {@link UserNotFoundException}. Those exist so a client can highlight the
 * offending field; this one must not hand the email back in a form a caller
 * could use to confirm which registered addresses exist.
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Invalid email or password");
    }
}

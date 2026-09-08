/**
 * <h2>Failure vocabulary for the user domain.</h2>
 *
 * {@code UserNotFoundException}, {@code DuplicateEmailException} (slice 4).
 *
 * <p>Same classes as the monolith. The difference is what a caller sees when one is
 * thrown: in the monolith the exception propagates up the call stack as a Java
 * object. Across a service boundary it cannot - it must be serialised into an HTTP
 * status plus an error body, and the calling service has to turn that back into
 * something meaningful. Exceptions do not cross the network; contracts do.
 */
package com.persona.user.exception;

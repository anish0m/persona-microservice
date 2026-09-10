package com.persona.user.exception;

/**
 * Thrown when a lookup names a user that does not exist.
 *
 * <p>Same core reasoning as the monolith's version: a dedicated type lets a
 * caller catch exactly this failure instead of catching {@code RuntimeException}
 * and accidentally swallowing real bugs. Unchecked, because nothing between the
 * repository and the controller can do anything useful about a missing user.
 *
 * <p><b>What is different here, and it is the important part.</b> This exception
 * cannot leave user-service. A Java exception is a JVM construct; it does not
 * travel over HTTP. When wallet-service asks user-service about a user who does
 * not exist, what it receives is a <em>404 status code</em>, and it is
 * wallet-service's job to decide what that means to it. This class is how
 * user-service represents the failure internally, right up to the boundary where
 * Day-09 translates it into a status code.
 *
 * <p>That translation is the loss microservices impose. In the monolith the type
 * survives the whole call chain and the compiler helps. Here it is flattened to an
 * integer at the network edge, and every service on the other side has to rebuild
 * meaning from that integer by agreement rather than by compilation.
 */
public class UserNotFoundException extends RuntimeException {

    private final String email;

    public UserNotFoundException(String email) {
        super("No user found with email: " + email);
        this.email = email;
    }

    public String getEmail() {
        return email;
    }
}

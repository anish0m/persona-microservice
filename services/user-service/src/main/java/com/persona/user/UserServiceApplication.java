package com.persona.user;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the <b>user-service</b>.
 *
 * <p>Compare with the monolith's {@code PersonaApplication}: the code is nearly
 * identical. That is the honest lesson of Day-00 - a microservice is not a
 * different kind of program. It is the same Spring Boot app with a narrower
 * responsibility and its own deployment lifecycle.
 *
 * <p>The differences that DO matter show up in the surrounding structure:
 * <pre>
 *   monolith                        microservices
 *   --------                        -------------
 *   com.persona                     com.persona.user      &lt;- service owns a namespace
 *     .model                          .model
 *     .repository                     .repository          each service will later get
 *     .service                        .service             its OWN database
 *     .controller                     .controller
 *
 *   one jar, one port 8080          one jar PER service, one port EACH (8081, 8082...)
 *   call = method call              call = HTTP over the network (Day-17)
 * </pre>
 */
@SpringBootApplication
public class UserServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserServiceApplication.class, args);
    }
}

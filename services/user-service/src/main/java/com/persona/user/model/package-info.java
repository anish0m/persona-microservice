/**
 * <h2>Domain model - owned by user-service alone.</h2>
 *
 * Same {@code User} class as the monolith (slice 2 fills it in).
 *
 * <p>The rule that differs from the monolith: <b>no other service may import this
 * package.</b> In the monolith, any package can reach in and use {@code User}
 * directly. Here, if a future notification-service needs a user's email, it must
 * ask over HTTP or receive an event - it cannot share the class.
 *
 * <p>That constraint feels like pointless friction on Day-00. It is the entire
 * reason microservices can be deployed independently, and you will feel both
 * sides of it on Day-17.
 */
package com.persona.user.model;

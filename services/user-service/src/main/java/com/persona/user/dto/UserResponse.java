package com.persona.user.dto;

import com.persona.user.model.User;

/**
 * What persona sends back when asked about a user.
 *
 * <p><b>Why not return the {@code User}?</b> Because Jackson serialises a User by
 * calling its getters — and {@code getPassword()} is a getter. The response would
 * contain the password, for every user, on every read endpoint.
 *
 * <p>It is worth being precise about why the existing protection does not help
 * here. {@code User.toString()} deliberately omits the password, and there is a
 * test asserting that. <b>Jackson never calls {@code toString()}.</b> That test
 * protects log files. It does nothing whatsoever for the wire.
 *
 * <p>So the password is excluded the only way that actually holds: it is not a
 * field of this record. The difference is between "my API happens not to expose
 * the password" and "my API <em>cannot</em> expose the password". Only the second
 * survives someone editing {@code User} next month.
 *
 * <p>{@code username} is included even though it is not a stored field — it is
 * derived by {@link User#getUsername()}. A DTO is shaped by what the client needs,
 * not by what the table holds; the two are allowed to differ, and this is the
 * first place they do.
 *
 * <p>{@code image} is a plain nullable String here rather than an
 * {@code Optional}: an absent value serialises to JSON {@code null}, which is
 * exactly how JSON already says "absent". Optional is for Java callers who might
 * forget to check. JSON has no such problem.
 */
public record UserResponse(
        String email,
        String firstName,
        String lastName,
        String username,
        String image) {

    /**
     * The one place a {@code User} becomes a {@code UserResponse}.
     *
     * <p>Put this mapping in the controller instead and it gets copy-pasted into
     * every endpoint that returns a user — and then one of the copies gains a
     * field the others do not. Same rule as validation in the model: one route in,
     * one route out.
     *
     * <p>A static factory on the DTO rather than a method on {@code User}, because
     * {@code User} must not know that a web layer exists. The arrows point one
     * way: the DTO may depend on the model; the model may not depend on the DTO.
     */
    public static UserResponse from(User user) {
        return new UserResponse(
                user.getEmail(),
                user.getFirstName(),
                user.getLastName(),
                user.getUsername(),
                user.getImage().orElse(null));
    }
}

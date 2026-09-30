package com.persona.user.dto;

/**
 * What a successful login returns. <b>Day-06.</b>
 *
 * <p>Just the token, and its type. A client that wants the profile makes a
 * second call — {@code GET /users/{email}}, now carrying the token — rather
 * than login trying to also be a profile fetch. Same instinct as
 * {@link UserResponse} versus {@code User}: this class exists to say exactly
 * what may cross the wire, no more.
 *
 * <p>{@code tokenType} is included even though this project only ever issues
 * one kind, {@code "Bearer"}. It costs one field and it is what tells a
 * client which HTTP header format to use — {@code Authorization: Bearer
 * <token>} — without hard-coding that knowledge on the client side.
 */
public record TokenResponse(String token, String tokenType) {

    public static TokenResponse bearer(String token) {
        return new TokenResponse(token, "Bearer");
    }
}

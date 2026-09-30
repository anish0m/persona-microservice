package com.persona.user.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * What a login request is allowed to contain. <b>Day-06.</b>
 *
 * <p>Two fields, both required, and nothing else — the same discipline as
 * {@link CreateUserRequest}: the wire contract is a deliberate, narrow
 * decision rather than whatever the model happens to expose. Note there is
 * no email format check here. {@code @Email} on {@link CreateUserRequest}
 * exists to give a signing-up user a useful message about a typo; here, a
 * malformed email is simply an email nobody is registered under, and the
 * login attempt fails for exactly the same reason a valid-looking but wrong
 * one does — see {@link com.persona.user.controller.AuthController} for why both
 * cases return the identical response.
 */
public record LoginRequest(

        @NotBlank(message = "Email is required")
        String email,

        @NotBlank(message = "Password is required")
        String password) {
}

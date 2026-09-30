package com.persona.user.controller;

import com.persona.user.config.JwtService;
import com.persona.user.config.TokenDenyList;
import com.persona.user.dto.LoginRequest;
import com.persona.user.dto.TokenResponse;
import com.persona.user.model.User;
import com.persona.user.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The web edge of authentication. <b>Day-06.</b> Same rule as
 * {@link UserController}: translation only, no business decision lives here.
 * {@link UserService#login} decides who is allowed in; this class turns that
 * decision into an HTTP response.
 *
 * <p>A separate controller from {@link UserController} rather than a fifth
 * method bolted onto it, because {@code /users} and {@code /auth} answer
 * different questions. {@code /users} is a resource collection — CRUD on
 * people. {@code /auth} is a set of actions on the <em>current</em> session —
 * log in, and soon log out. Mixing them would put a verb-shaped endpoint
 * (login is an action, not "get or set some state of a user resource") in a
 * controller that has been noun-only since Day-02.
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final UserService service;
    private final JwtService jwtService;
    private final TokenDenyList denyList;

    public AuthController(UserService service, JwtService jwtService,
                          TokenDenyList denyList) {
        this.service = service;
        this.jwtService = jwtService;
        this.denyList = denyList;
    }

    /**
     * Log in. {@code POST /auth/login}.
     *
     * <p><b>POST, not GET</b>, even though nothing is created — the reason is
     * not REST purity, it is that a password does not belong on a URL. A GET
     * request's parameters end up in browser history, proxy logs and web
     * server access logs, all in plaintext. Credentials belong in a request
     * body, which is one of the few things GET cannot carry.
     *
     * <p><b>200, not 201.</b> Login does not create a new resource; the token
     * is not "a thing that now exists at some URL", it is a bearer credential
     * handed directly back. No {@code Location} header applies.
     *
     * <p>Note what is NOT here: no {@code try/catch}. {@code service.login}
     * throws {@link com.persona.user.exception.InvalidCredentialsException} on any
     * failure, and {@link GlobalExceptionHandler} turns it into a 401 in one
     * place — the same shape as every other exception in this project.
     */
    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        User user = service.login(request);
        String token = jwtService.issueToken(user.getEmail());
        return TokenResponse.bearer(token);
    }

    /**
     * Log out. {@code POST /auth/logout}.
     *
     * <p><b>This is the endpoint that cannot be written naively.</b> A JWT
     * carries its own validity: the signature verifies and {@code exp} has
     * not passed, so it keeps working no matter what any endpoint returns.
     * Clearing a cookie or telling the client to forget the token is not
     * logout — it is a polite suggestion to a client that may be hostile.
     * The token is still a working credential for anyone who kept a copy.
     *
     * <p>So the token is added to {@link TokenDenyList}, which
     * {@link com.persona.user.config.JwtAuthenticationFilter} consults on every
     * request. That is the only thing here that actually revokes anything,
     * and it costs the statelessness the JWT was chosen for — see that class
     * for the full trade-off.
     *
     * <p><b>POST, not GET</b>, because logging out changes server state, and
     * a GET that changes state gets triggered by a link prefetch, a crawler
     * or a browser preloading the URL. "GET must be safe" is not a style
     * preference; the internet is full of things that follow links
     * speculatively.
     *
     * <p>Reads the header directly rather than taking a {@code Principal}:
     * this method needs the raw token string to deny it, and the principal
     * only carries the email. Note it requires a valid token to reach here at
     * all — {@code anyRequest().authenticated()} covers this path — so there
     * is no "already logged out" case to handle; that returns 401 before the
     * method runs.
     *
     * <p>{@code 204 No Content}: something happened, there is nothing to say
     * about it.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @RequestHeader("Authorization") String authorizationHeader) {

        String token = authorizationHeader.substring("Bearer ".length());
        denyList.deny(token, jwtService.extractExpiry(token));

        return ResponseEntity.noContent().build();
    }
}

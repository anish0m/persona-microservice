package com.persona.user.controller;

import com.persona.user.client.WalletClient;
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

    /**
     * The first dependency in this project that is <b>another running
     * process</b>. <b>Day-07.</b>
     *
     * <p>Every other field here is a bean in this JVM: calling one cannot time
     * out, cannot be refused, and cannot be a different version than expected.
     * This one can do all three, which is why {@link WalletClient} is a hundred
     * lines of timeouts and retry around a single POST.
     *
     * <p>Worth noticing in the constructor signature itself: nothing about the
     * type tells you that. {@code WalletClient} looks exactly like
     * {@code UserService} from here, and that symmetry is the thing to
     * distrust — <b>a network call disguised as a method call is the most
     * common way a distributed system acquires a failure mode nobody
     * planned.</b>
     */
    private final WalletClient walletClient;

    public AuthController(UserService service, JwtService jwtService,
                          TokenDenyList denyList, WalletClient walletClient) {
        this.service = service;
        this.jwtService = jwtService;
        this.denyList = denyList;
        this.walletClient = walletClient;
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

        // Day-07: the token now carries the user's id and role as well as
        // their email. wallet-service needs both, has no users table to look
        // either up in, and the alternative is an HTTP call back to this
        // service on every single request it serves. See JwtService.issueToken
        // for what that buys and what it costs (a stale role for up to one
        // token lifetime).
        String token = jwtService.issueToken(user.getEmail(), user.getId(), user.getRole());

        // ===================================================================
        //  THE WALLET IS OPENED HERE, AT LOGIN — NOT AT SIGNUP.
        //
        //  In the monolith it happens during registration, inside the same
        //  @Transactional as the user INSERT. That is the natural place and it
        //  is NOT AVAILABLE in this build, for a reason that only appears once
        //  the code is actually written:
        //
        //    wallet-service requires an authenticated caller. At signup there
        //    is no token — the user is not logged in yet, that is the entire
        //    point of signing up — so user-service has nothing to present, and
        //    the call is rejected with a 401.
        //
        //  Three ways out, and the trade in each:
        //
        //    (a) make POST /wallets/me public — deletes the authorization on
        //        an endpoint that creates rows. No.
        //    (b) service-to-service credentials, so user-service authenticates
        //        AS ITSELF rather than on behalf of a user. This is the right
        //        answer and it needs a credential-issuing mechanism that does
        //        not exist yet. Day-17, with the gateway.
        //    (c) open the wallet at first login, when a token exists.
        //
        //  (c) is what is implemented, and it is a COMPROMISE rather than a
        //  design: it means a registered user who never logs in has no wallet,
        //  and it moves a side effect into an endpoint whose job is
        //  authentication. Both are wrong in a small way, and the alternative
        //  was wrong in a large way.
        //
        //  WHAT THIS IS REALLY SHOWING: the monolith's ordering was free
        //  because a method call needs no permission. The moment the call
        //  crosses a service boundary it needs authorization, and the
        //  authorization is not available at the point the business logic
        //  wants to make the call. That is a genuinely new class of problem,
        //  it has nothing to do with wallets, and no amount of care in the
        //  domain model prevents it.
        //
        //  Idempotent on the far side, so logging in a hundred times opens one
        //  wallet. The return value is deliberately ignored: a failure here
        //  must not fail a login that has already succeeded. WalletClient logs
        //  it, loudly.
        // ===================================================================
        walletClient.createWalletFor(user.getId(), token);

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

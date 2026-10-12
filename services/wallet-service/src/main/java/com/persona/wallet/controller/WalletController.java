package com.persona.wallet.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.persona.wallet.config.JwtAuthenticationFilter;
import com.persona.wallet.dto.DepositRequest;
import com.persona.wallet.dto.WalletResponse;
import com.persona.wallet.service.WalletService;

/**
 * HTTP for wallets. Day-07, microservice build.
 *
 * <h2>Addressed by user id, where the monolith addresses by email</h2>
 *
 * <p>The monolith's {@code WalletController} takes a {@code Principal}, reads
 * the email, and calls {@code userService.getByEmail(...)} to turn it into an
 * id. That first call is <b>a method call into another class in the same
 * process</b> — effectively free, and invisible.
 *
 * <p>Here the same translation would be an <b>HTTP request to
 * user-service</b>: a network hop on every wallet read, a second service that
 * must be up for this one to answer, and a latency floor nobody can optimise
 * away. The obvious design is the wrong one.
 *
 * <p>So the id travels in the token instead, is extracted once by
 * {@link JwtAuthenticationFilter}, and is read here from a request attribute.
 * <b>Zero network calls to serve a wallet request</b> — which is the property
 * that makes this service independently deployable in any useful sense. A
 * service that must call another service to answer anything has the other
 * one's availability as a ceiling on its own.
 *
 * <p>The cost, stated so it is not discovered later: this service cannot
 * render anything about a user beyond their id and email. An endpoint
 * returning "wallet, with owner's full name" is not available without the call
 * that was just avoided. That is a real product constraint arising from an
 * architectural choice, and it is why API composition (or a gateway that
 * stitches responses together) shows up in microservice systems — Day-17.
 *
 * <h2>Still no business rules in here</h2>
 *
 * <p>Unchanged from the monolith and worth re-stating: not one method below
 * checks a status or an amount. Those live on {@code Wallet}, where changing
 * the balance IS calling the guarded method. A controller that checked
 * {@code status == FROZEN} would protect this endpoint rather than the money,
 * and the next entry point would not be protected at all.
 */
@RestController
@RequestMapping("/wallets")
public class WalletController {

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    /**
     * The authenticated caller's user id, published by the filter.
     *
     * <p>Never null on a route reaching this class: the filter sets it only
     * after a signature check, and {@code anyRequest().authenticated()} means
     * an unauthenticated request never gets here. The cast is safe for the
     * same reason — the attribute has exactly one writer.
     *
     * <p>Note this is the microservice equivalent of the monolith's
     * {@code Principal} plus a database lookup, with the lookup deleted.
     */
    private Long callerId(HttpServletRequest request) {
        return (Long) request.getAttribute(JwtAuthenticationFilter.USER_ID_ATTRIBUTE);
    }

    /**
     * Opens a wallet for a user. <b>Called by user-service, not by a browser.</b>
     *
     * <p>This endpoint is the replacement for one line in the monolith:
     *
     * <pre>
     *     walletService.createFor(user);   // inside &#64;Transactional
     * </pre>
     *
     * <p>There, the wallet insert joined the same transaction as the user
     * insert and the pair was atomic. Here it is a separate HTTP request to a
     * separate database, and <b>nothing makes the two atomic.</b> What exists
     * instead is {@link WalletService#createFor} being idempotent, so that a
     * retry after a lost response does not produce a second wallet.
     *
     * <p>Returns <b>200, not 201</b>, and the choice is deliberate. 201 Created
     * asserts that this request created something; on a retry that assertion is
     * false, and an idempotent endpoint should give the same answer whether it
     * did the work or found it already done. 200 with the wallet is true in
     * both cases.
     *
     * <p>⚠️ <b>The authorization here is wrong for production and right for
     * today.</b> Any authenticated caller may open a wallet for themselves.
     * What this endpoint actually needs is service-to-service authentication —
     * a credential proving the caller IS user-service, not merely some logged
     * in user. That is a real gap, named rather than papered over; it belongs
     * with the gateway work on Day-17.
     */
    @PostMapping("/me")
    @ResponseStatus(HttpStatus.OK)
    public WalletResponse openMyWallet(HttpServletRequest request) {
        return WalletResponse.from(walletService.createFor(callerId(request)));
    }

    /**
     * The caller's own wallet.
     *
     * <p>No {@code @PreAuthorize}: the route has no parameter naming a wallet,
     * so there is no way to ask for one that is not yours. Readable while
     * FROZEN and while CLOSED, deliberately — a user whose wallet is suspended
     * must still be able to see their own money.
     */
    @GetMapping("/me")
    public WalletResponse myWallet(HttpServletRequest request) {
        return WalletResponse.from(walletService.getByUserId(callerId(request)));
    }

    /**
     * Adds dummy money to the caller's own wallet.
     *
     * <p>{@code @Valid} is what makes {@link DepositRequest}'s constraints run.
     * Without it they are decoration — present, compiled, and read by nobody.
     *
     * <p>A POST rather than a PATCH on the balance: "deposit 50" is an
     * operation, while "the balance is now 50" is what a PATCH would mean, and
     * that would be an unguarded way to set money to an arbitrary value.
     */
    @PostMapping("/me/deposit")
    public WalletResponse deposit(HttpServletRequest request,
                                  @Valid @RequestBody DepositRequest body) {
        return WalletResponse.from(
                walletService.deposit(callerId(request), body.amount()));
    }

    /**
     * Any user's wallet. Admin only.
     *
     * <p>Addressed by {@code userId} rather than by email, because this service
     * has no way to resolve an email — that is user-service's fact. An admin UI
     * therefore has to do the lookup itself and pass an id, which is API
     * composition arriving in its smallest possible form.
     *
     * <p>The {@code @PreAuthorize} is the entire protection on this route,
     * which makes it the one place in this class where a missing annotation is
     * a breach rather than untidiness — and why it must be tested with a
     * <b>USER</b> token, not only an ADMIN one. A route only ever exercised
     * with credentials that pass proves nothing about the ones that should not.
     *
     * <p>Note the role comes from the token's claim, so it is a snapshot: an
     * admin demoted a minute ago still passes this check until their token
     * expires. The monolith re-read the role from the database on every
     * request and had no such window.
     */
    @GetMapping("/{userId}")
    @PreAuthorize("hasRole('ADMIN')")
    public WalletResponse walletOf(@PathVariable Long userId) {
        return WalletResponse.from(walletService.getByUserId(userId));
    }

    /** Suspends a user's wallet. Admin only. */
    @PostMapping("/{userId}/freeze")
    @PreAuthorize("hasRole('ADMIN')")
    public WalletResponse freeze(@PathVariable Long userId) {
        return WalletResponse.from(walletService.freeze(userId));
    }

    /** Reverses {@link #freeze}. Admin only. */
    @PostMapping("/{userId}/unfreeze")
    @PreAuthorize("hasRole('ADMIN')")
    public WalletResponse unfreeze(@PathVariable Long userId) {
        return WalletResponse.from(walletService.unfreeze(userId));
    }
}

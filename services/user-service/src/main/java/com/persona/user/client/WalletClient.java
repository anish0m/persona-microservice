package com.persona.user.client;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Calls wallet-service to open a wallet for a new user. <b>Day-07.</b>
 *
 * <h2>This entire class is what one annotation used to do</h2>
 *
 * <p>In the monolith, user registration opens a wallet like this:
 *
 * <pre>
 *     &#64;Transactional
 *     public User register(CreateUserRequest request) {
 *         User user = repository.save(...);
 *         walletService.createFor(user);   // same process, same transaction
 *         return user;
 *     }
 * </pre>
 *
 * <p>Both writes commit together or neither does. The question "what if the
 * second one fails?" has a one-word answer: rollback.
 *
 * <p>Here that method call becomes a network request to another process
 * holding another database, and <b>no transaction can span the two.</b> This
 * class, its timeouts, its retry, its logging and its documented failure
 * window are the replacement. They are not an equivalent — they are a weaker
 * arrangement that has to be designed, and this file is where the cost of the
 * split is actually paid.
 *
 * <h2>The three failure modes, and which ones are handled</h2>
 *
 * <ol>
 *   <li><b>The call fails outright</b> (wallet-service is down, the connection
 *       is refused). Retried once, then given up on. The user is still
 *       created — see {@code UserService.register} for why that is the chosen
 *       behaviour rather than failing the signup.</li>
 *   <li><b>The call succeeds but the RESPONSE is lost</b> (timeout after the
 *       remote commit). This is the one people miss. From here it is
 *       indistinguishable from case 1, so the retry fires and wallet-service
 *       receives the request <em>twice</em>. Nothing in this class can prevent
 *       that; what makes it safe is that {@code WalletService.createFor} over
 *       there is <b>idempotent</b> — it returns the existing wallet rather than
 *       creating a second. <b>A retry is only correct if the far side is
 *       idempotent, which is a property of the callee, not the caller.</b></li>
 *   <li><b>The call is never made</b> because this process died between the
 *       user INSERT committing and this method running. Nothing here can see
 *       that, let alone fix it. The recovery is a reconciliation job that finds
 *       users with no wallet and opens one — slower than a transaction, after
 *       the fact, and able to be wrong for a while. persona does not have that
 *       job yet; it is named rather than hidden.</li>
 * </ol>
 *
 * <h2>Why RestClient and not RestTemplate or WebClient</h2>
 *
 * <p>{@code RestTemplate} is in maintenance mode. {@code WebClient} is
 * reactive and would pull a non-blocking stack into a service that is
 * otherwise entirely blocking, which is a large concept for one call.
 * {@code RestClient} is the modern synchronous client — same fluent shape as
 * {@code WebClient}, blocking semantics that match the rest of this service.
 */
@Component
public class WalletClient {

    private static final Logger log = LoggerFactory.getLogger(WalletClient.class);

    private final RestClient restClient;

    /**
     * Timeouts are set explicitly, and leaving them at the default would be
     * the single most dangerous omission in this class.
     *
     * <p>With no read timeout, a hung wallet-service does not fail this call —
     * it <b>holds the thread serving a signup request, indefinitely</b>. Enough
     * of those and user-service's thread pool is exhausted and it stops
     * answering requests that have nothing to do with wallets. One slow
     * dependency becomes a total outage, which is the classic distributed
     * failure and the reason circuit breakers exist.
     *
     * <p>Two seconds to connect, three to read. Short on purpose: this call is
     * on the signup path, and a user waiting on a form should not pay for a
     * sick downstream service.
     */
    public WalletClient(@Value("${persona.wallet-service.url}") String baseUrl) {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(3));

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    /**
     * Asks wallet-service to open a wallet for the given user.
     *
     * <p>Returns {@code true} if a wallet is known to exist afterwards,
     * {@code false} if the call could not be completed. <b>Never throws</b> —
     * the caller has already committed a user, and an exception propagating
     * from here would turn a partially-complete signup into an HTTP 500 for a
     * user who actually was created.
     *
     * <p>⚠️ <b>The token is forwarded, which is a real weakness.</b> This call
     * carries the caller's own JWT because wallet-service requires
     * authentication and has no other way to know who this is. That means
     * wallet-service cannot distinguish "user-service asking on behalf of a
     * user" from "that user asking directly" — which is why
     * {@code POST /wallets/me} over there is open to any authenticated caller.
     * The correct arrangement is service-to-service credentials (a client
     * certificate, or a token minted for user-service itself). Named rather
     * than papered over; it belongs with the gateway work on Day-17.
     *
     * <p>Note the signup path has no token at all — a user signing up is not
     * logged in yet — so {@code bearerToken} is null there and wallet-service
     * would reject the call. That is the honest state of this build today: the
     * wallet is opened on first login rather than at signup. See
     * {@code UserService.register}.
     */
    public boolean createWalletFor(Long userId, String bearerToken) {
        try {
            return attempt(userId, bearerToken);
        } catch (ResourceAccessException e) {
            // Connection refused, or a timeout. Retried ONCE.
            //
            // Once, not three times with exponential backoff, because this is
            // on a user-facing path: three retries at a three-second read
            // timeout is a nine-second signup. Background work can afford
            // patience; a form submission cannot.
            //
            // THE RETRY IS ONLY SAFE BECAUSE THE FAR SIDE IS IDEMPOTENT. If
            // createFor there threw on a duplicate — as the monolith's version
            // correctly does — this line would create a second wallet or a
            // spurious error whenever a response was merely slow.
            log.warn("wallet-service unreachable for user {} ({}), retrying once",
                    userId, e.getMessage());
            try {
                return attempt(userId, bearerToken);
            } catch (RuntimeException retryFailure) {
                log.error("WALLET NOT CREATED for user {} after retry. The user "
                        + "exists and cannot hold money until reconciled.",
                        userId, retryFailure);
                return false;
            }
        } catch (RuntimeException e) {
            // A 4xx or 5xx. Not retried: a 400 will be a 400 again, and
            // retrying a deterministic failure only multiplies load on a
            // service that is already unhappy.
            log.error("WALLET NOT CREATED for user {}: {}", userId, e.getMessage());
            return false;
        }
    }

    private boolean attempt(Long userId, String bearerToken) {
        restClient.post()
                .uri("/wallets/me")
                .headers(headers -> {
                    if (bearerToken != null) {
                        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken);
                    }
                })
                .retrieve()
                .toBodilessEntity();
        return true;
    }
}

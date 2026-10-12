package com.persona.wallet.exception;

/**
 * Thrown when a user's wallet is looked up and is not there. Day-07.
 *
 * <p>Unchecked, for the transaction-rollback reason on
 * {@link WalletNotActiveException}. Maps to <b>404</b>, not 409: the resource
 * genuinely does not exist, as opposed to existing and refusing.
 *
 * <h2>Why this means something different here than in the monolith</h2>
 *
 * <p>Worth reading alongside the monolith's version of this class, because the
 * two carry opposite messages from identical code.
 *
 * <p><b>In the monolith</b>, this exception should be impossible. Registration
 * creates the user and the wallet in one transaction, so a user without a
 * wallet cannot be committed. Seeing it in a log there is the signature of a
 * broken {@code @Transactional} — an alarm, not a 404.
 *
 * <p><b>Here it is a normal, expected outcome.</b> There is no transaction
 * spanning the two services. user-service commits the user and then asks
 * wallet-service to open a wallet; between those two moments — and for however
 * long a retry takes if the second call fails — <b>a user legitimately exists
 * with no wallet.</b> That window is not a bug. It is the eventual-consistency
 * cost of the split, and it is exactly what the monolith's one-line
 * {@code @Transactional} was buying.
 *
 * <p>So the same 404 means "something is broken" in one build and "ask again in
 * a moment" in the other. The caller cannot tell the difference from the status
 * code, which is why a distributed system needs retries where a monolith needed
 * none.
 *
 * <p>Concretely, the recovery path a mature system adds and persona does not
 * yet have: a background job that finds users with no wallet and opens one.
 * That job is the replacement for the transaction — slower, after the fact, and
 * able to be wrong for a while.
 */
public class WalletNotFoundException extends RuntimeException {

    private final Long userId;

    public WalletNotFoundException(Long userId) {
        super("no wallet for user " + userId);
        this.userId = userId;
    }

    public Long getUserId() {
        return userId;
    }
}

package com.persona.wallet.service;

import java.math.BigDecimal;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.persona.wallet.exception.WalletNotFoundException;
import com.persona.wallet.model.Wallet;
import com.persona.wallet.repository.WalletRepository;

/**
 * Business operations on wallets. Day-07, microservice build.
 *
 * <p>The rules are not here — they are on {@link Wallet}, where changing the
 * balance IS calling the guarded method, so no code path can skip them. This
 * class finds the aggregate and defines the transaction boundary around one
 * operation, which is all a service genuinely owns.
 *
 * <h2>Every transaction here is local, and that is the whole story</h2>
 *
 * <p>{@code @Transactional} on the methods below works exactly as it does in
 * the monolith, because each one touches only {@code persona_wallet}. What
 * cannot exist in this build is a transaction spanning <em>a user and a
 * wallet</em> — those live in two databases, and no annotation reaches across.
 *
 * <p>So the monolith's single {@code @Transactional} on
 * {@code UserService.register} has no counterpart here. What replaces it is
 * {@link #createFor} being <b>idempotent</b>, plus a caller willing to retry.
 * See that method.
 */
@Service
public class WalletService {

    private final WalletRepository repository;

    public WalletService(WalletRepository repository) {
        this.repository = repository;
    }

    /**
     * Opens a wallet for a user. <b>Idempotent, and that is what replaces the
     * transaction.</b>
     *
     * <p>In the monolith, {@code UserService.register} wrote the user and the
     * wallet inside one transaction: both committed or neither did, and the
     * question "what if the second write fails?" had a one-word answer —
     * rollback.
     *
     * <p>Here the two writes are in different databases, reached over HTTP, and
     * there is no rollback available. The failure modes multiply:
     *
     * <ol>
     *   <li>user-service commits the user; the call to this service never
     *       arrives. <b>A user exists with no wallet.</b></li>
     *   <li>The call arrives, this service commits the wallet, and the
     *       <em>response</em> is lost. user-service believes it failed and
     *       retries. <b>A second wallet would be created</b> — if this method
     *       were not idempotent.</li>
     *   <li>The call arrives and genuinely fails. user-service must decide
     *       whether to retry, and for how long.</li>
     * </ol>
     *
     * <p>Case 2 is the one people miss, and it is why this method returns the
     * existing wallet instead of throwing when one is already present. <b>A
     * retried request must be safe to apply twice.</b> The monolith's version
     * of this method throws {@code IllegalStateException} on a duplicate,
     * which is correct there — a second call means a bug — and would be wrong
     * here, where a second call means a dropped network packet.
     *
     * <p>That is a real behavioural difference between the two builds, not a
     * stylistic one, and it is worth stating plainly: <b>idempotency is the
     * price of not having a transaction.</b> Day-11's idempotency-key work
     * generalises this to operations where "do it again" is not naturally
     * safe — a transfer, where the monolith's transaction would also have been
     * doing real work.
     *
     * <p>Case 1 has no defence in this code at all. The honest answer is that
     * persona does not yet have one: a production system adds a reconciliation
     * job that finds users with no wallet and opens one, which is slower than
     * a transaction and able to be wrong for a while. Named rather than hidden.
     */
    @Transactional
    public Wallet createFor(Long userId) {
        return repository.findByUserId(userId)
                .orElseGet(() -> repository.save(new Wallet(userId)));
    }

    /**
     * The wallet belonging to a user, or an exception.
     *
     * <p>{@code readOnly = true} lets Hibernate skip dirty-checking snapshots
     * and flags the transaction read-only at the driver. The practical effect
     * at persona's size is nil; it documents intent in a way a comment cannot.
     */
    @Transactional(readOnly = true)
    public Wallet getByUserId(Long userId) {
        return repository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException(userId));
    }

    /**
     * Adds money to a user's wallet.
     *
     * <p><b>{@code @Transactional} is not optional here even though this looks
     * like one write.</b> It reads the wallet, mutates it, and relies on
     * Hibernate's dirty checking to issue the UPDATE at commit. Without a
     * transaction spanning the method there is no persistence context, the
     * entity is detached, {@code credit} mutates a plain Java object, and
     * nothing is written — silently, with a green test if the test asserts on
     * the returned object rather than on the database.
     *
     * <p>No {@code save()} call, deliberately: under JPA the mutation IS the
     * write. Adding one would be redundant and would teach the wrong model.
     */
    @Transactional
    public Wallet deposit(Long userId, BigDecimal amount) {
        Wallet wallet = repository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException(userId));

        wallet.credit(amount);

        return wallet;
    }

    /** Suspends a wallet. Admin-facing. */
    @Transactional
    public Wallet freeze(Long userId) {
        Wallet wallet = repository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException(userId));
        wallet.freeze();
        return wallet;
    }

    /** Reverses {@link #freeze}. Admin-facing. */
    @Transactional
    public Wallet unfreeze(Long userId) {
        Wallet wallet = repository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException(userId));
        wallet.unfreeze();
        return wallet;
    }
}

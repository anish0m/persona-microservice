package com.persona.wallet.exception;

import com.persona.wallet.model.WalletStatus;

/**
 * Thrown when an operation needs a wallet that can transact, and the wallet
 * cannot. Day-07.
 *
 * <h2>Why this extends RuntimeException, and why that is load-bearing</h2>
 *
 * <p>Every exception in this package is unchecked, which until today was a
 * style decision. On Day-07 it becomes a correctness one, because
 * {@code @Transactional} treats the two kinds differently <b>by default</b>:
 *
 * <pre>
 *     RuntimeException / Error  ──► ROLLBACK
 *     checked Exception         ──► COMMIT   ← yes, really
 * </pre>
 *
 * <p>The reasoning is historical — a checked exception is "expected", part of
 * a method's declared contract — but the effect is a half-finished transaction
 * committed on an error path. Had this been a checked exception, a failure
 * thrown midway through a multi-write operation would leave the earlier writes
 * committed.
 *
 * <p>persona is safe here by inheritance rather than by annotation, which is
 * the more robust arrangement: nobody has to remember
 * {@code @Transactional(rollbackFor = ...)} on each method. The rule to carry:
 * <b>domain exceptions extend RuntimeException, and the reason is the
 * transaction manager, not convenience.</b>
 *
 * <h2>409, not 400 and not 500</h2>
 *
 * <p>A frozen wallet is not a malformed request — the JSON was perfect, every
 * field validated, so it is not a 400. It is not a server fault either, so it
 * is not a 500. It is <b>409 Conflict</b>: the request is well-formed but
 * conflicts with the current state of the resource. The same family as
 * {@link DuplicateEmailException}, and mapped the same way in
 * {@code GlobalExceptionHandler}.
 */
public class WalletNotActiveException extends RuntimeException {

    private final Long walletId;
    private final WalletStatus status;

    /**
     * @param walletId the wallet that refused — may be {@code null} for a
     *                 wallet that has not been persisted yet, which is why the
     *                 message below handles that case rather than printing
     *                 "null"
     * @param status   the state it was actually in
     */
    public WalletNotActiveException(Long walletId, WalletStatus status) {
        super(buildMessage(walletId, status));
        this.walletId = walletId;
        this.status = status;
    }

    /**
     * The status IS named in the message, and that is a deliberate difference
     * from {@link InvalidCredentialsException}, which deliberately says nothing.
     *
     * <p>The distinction is who learns what. A login error that distinguishes
     * "no such email" from "wrong password" leaks registered addresses to
     * anybody who asks. A wallet error is only ever shown to someone already
     * authenticated as that wallet's owner, and telling them "your wallet is
     * frozen" rather than "operation failed" is the difference between a
     * support call they can act on and one they cannot.
     *
     * <p><b>The rule is not "always be vague" but "never tell a stranger
     * something about a third party".</b>
     */
    private static String buildMessage(Long walletId, WalletStatus status) {
        String which = walletId == null ? "this wallet" : "wallet " + walletId;
        return which + " is " + status + " and cannot transact";
    }

    public Long getWalletId() {
        return walletId;
    }

    public WalletStatus getStatus() {
        return status;
    }
}

package com.persona.wallet.exception;

import java.math.BigDecimal;

/**
 * Thrown when a debit would take a wallet below zero. Day-07.
 *
 * <p>Unchecked, for the transaction-rollback reason documented at length on
 * {@link WalletNotActiveException}.
 *
 * <h2>Why the message uses toPlainString()</h2>
 *
 * <p>{@code BigDecimal.toString()} can emit scientific notation at some scales
 * — {@code 1E+2} rather than {@code 100}. Not for the range persona deals in,
 * but an exception message is read by a human under time pressure, and
 * {@code toPlainString()} costs nothing and removes the possibility entirely.
 *
 * <h2>What this exception does NOT guarantee</h2>
 *
 * <p>Worth stating, because the class's existence can create a false sense of
 * safety. The check that throws this is a read-then-write in application code:
 * two concurrent debits can both observe a sufficient balance before either
 * writes. This exception catches the honest case — a user genuinely spending
 * more than they have — and does not prevent the raced one.
 *
 * <p>What prevents the raced case is the database's
 * {@code wallets_balance_non_negative} CHECK constraint, which cannot be raced
 * because Postgres serialises it. The pairing is the lesson: <b>the application
 * check buys a good error message; the constraint buys the guarantee.</b> A
 * concurrency day replaces the application half with a conditional write.
 */
public class InsufficientFundsException extends RuntimeException {

    private final Long walletId;
    private final BigDecimal balance;
    private final BigDecimal requested;

    public InsufficientFundsException(Long walletId, BigDecimal balance, BigDecimal requested) {
        super("wallet " + walletId + " holds " + balance.toPlainString()
                + " but " + requested.toPlainString() + " was requested");
        this.walletId = walletId;
        this.balance = balance;
        this.requested = requested;
    }

    public Long getWalletId() {
        return walletId;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public BigDecimal getRequested() {
        return requested;
    }
}

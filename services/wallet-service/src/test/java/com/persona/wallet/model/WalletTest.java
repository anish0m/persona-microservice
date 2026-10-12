package com.persona.wallet.model;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.persona.wallet.exception.InsufficientFundsException;
import com.persona.wallet.exception.WalletNotActiveException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the wallet's own rules. <b>Day-07.</b>
 *
 * <p>No Spring, no database, no mocks — a {@code Wallet} is constructible with
 * {@code new}, which is the property that makes the rules testable at all. That
 * is a consequence of the rules living on the entity rather than in a service:
 * had {@code credit} been a {@code WalletService} method, every test below
 * would need a repository.
 *
 * <p><b>What these tests cannot prove</b>, stated up front so the green is not
 * over-read: nothing here touches a database, so the {@code NUMERIC(19,4)}
 * scale, the {@code UNIQUE} on {@code user_id} and the non-negative CHECK are
 * all absent. {@code WalletIT} covers those.
 *
 * <p>This file is otherwise identical to the monolith's {@code WalletTest},
 * and that is information rather than laziness: <b>every rule tested below
 * survived the service split untouched.</b> Money arithmetic, status
 * transitions and amount validation are local invariants — they need no
 * knowledge of users, so a boundary between wallets and users costs them
 * nothing. What the split actually broke was the relational guarantees
 * (the foreign key) and the transactional one, and neither is testable here.
 */
class WalletTest {

    /**
     * <b>The one line that differs from the monolith's copy of this file.</b>
     *
     * <p>There: {@code new Wallet(new User(email, first, last, hash))}. Here a
     * bare {@code Long}, because wallet-service has no {@code User} class and
     * cannot have one — see {@link Wallet} and the pom.
     *
     * <p>Notice what that does to the TEST rather than to the production code:
     * this fixture is simpler and needs no user at all. The split made the
     * wallet genuinely independent of the user, and a test fixture is usually
     * the first place that shows up.
     */
    private Wallet newWallet() {
        return new Wallet(42L);
    }

    @Test
    void aNewWalletIsActiveAndEmpty() {
        Wallet wallet = newWallet();

        assertEquals(WalletStatus.ACTIVE, wallet.getStatus());

        // compareTo, not equals — see theZeroScaleTrap below for why this
        // distinction is not pedantry.
        assertEquals(0, wallet.getBalance().compareTo(BigDecimal.ZERO));
    }

    @Test
    void aWalletCannotExistWithoutAnOwner() {
        assertThrows(IllegalArgumentException.class, () -> new Wallet(null));
    }

    // =====================================================================
    //  The BigDecimal traps. These three tests exist to make the traps
    //  visible rather than to defend much behaviour — each one would have
    //  been a silent bug.
    // =====================================================================

    /**
     * The trap that fails a CORRECT test, documented as an executable fact.
     *
     * <p>This is not testing persona's code; it is pinning a property of
     * {@code BigDecimal} that the rest of the suite depends on. If someone
     * later "simplifies" an assertion from {@code compareTo} to
     * {@code assertEquals}, this test is the explanation waiting for them.
     *
     * <p>The real-world version: Postgres returns a {@code NUMERIC(19,4)}
     * column at scale 4, so a zero balance arrives as {@code 0.0000}.
     * {@code assertEquals(BigDecimal.ZERO, balance)} compares that against
     * scale 0 and fails, on a wallet that is perfectly correct.
     */
    @Test
    void theZeroScaleTrap_equalsComparesScale_compareToDoesNot() {
        BigDecimal scaleOne = new BigDecimal("0.0");
        BigDecimal scaleFour = new BigDecimal("0.0000");

        assertNotEquals(scaleOne, scaleFour,
                "equals() compares scale as well as value — this is the trap");
        assertEquals(0, scaleOne.compareTo(scaleFour),
                "compareTo() compares numeric value only — use this for money");
    }

    /**
     * The constructor trap, also pinned as an executable fact.
     *
     * <p>{@code new BigDecimal(0.1)} faithfully preserves the error already
     * present in the double. The assertion is deliberately phrased as "these
     * are not equal", because that is the surprising part.
     */
    @Test
    void theDoubleConstructorTrap_stringAndDoubleDisagree() {
        BigDecimal fromDouble = new BigDecimal(0.1);
        BigDecimal fromString = new BigDecimal("0.1");

        assertNotEquals(0, fromDouble.compareTo(fromString),
                "new BigDecimal(double) carries the binary-fraction error");
        assertTrue(fromDouble.toPlainString().startsWith("0.1000000000000000055"),
                "actual value was " + fromDouble.toPlainString());
    }

    /**
     * The immutability trap, proven against persona's own entity.
     *
     * <p>If {@code credit} had been written {@code this.balance.add(amount)}
     * without the assignment, it would compile, run, compute the right answer,
     * discard it, and report nothing. This test is what turns that from silent
     * to red — the mutation check for {@code Wallet.credit}.
     */
    @Test
    void creditActuallyAssigns_notJustComputes() {
        Wallet wallet = newWallet();

        wallet.credit(new BigDecimal("10.50"));

        assertEquals(0, wallet.getBalance().compareTo(new BigDecimal("10.50")),
                "balance did not change — was the result of add() discarded?");
    }

    // =====================================================================
    //  Arithmetic
    // =====================================================================

    @Test
    void creditsAccumulateExactly() {
        Wallet wallet = newWallet();

        // The canonical floating-point demonstration: as doubles these sum to
        // 0.30000000000000004. As BigDecimal they sum to exactly 0.3.
        wallet.credit(new BigDecimal("0.1"));
        wallet.credit(new BigDecimal("0.2"));

        assertEquals(0, wallet.getBalance().compareTo(new BigDecimal("0.3")),
                "exact decimal arithmetic failed — actual "
                        + wallet.getBalance().toPlainString());
    }

    @Test
    void debitReducesTheBalance() {
        Wallet wallet = newWallet();
        wallet.credit(new BigDecimal("100.00"));

        wallet.debit(new BigDecimal("30.25"));

        assertEquals(0, wallet.getBalance().compareTo(new BigDecimal("69.75")));
    }

    @Test
    void debitingMoreThanTheBalanceIsRefused() {
        Wallet wallet = newWallet();
        wallet.credit(new BigDecimal("10.00"));

        assertThrows(InsufficientFundsException.class,
                () -> wallet.debit(new BigDecimal("10.01")));

        assertEquals(0, wallet.getBalance().compareTo(new BigDecimal("10.00")),
                "a refused debit must not change the balance");
    }

    /**
     * The boundary case, because off-by-one on a balance check is how an
     * account goes one cent negative.
     */
    @Test
    void debitingExactlyTheWholeBalanceIsAllowed() {
        Wallet wallet = newWallet();
        wallet.credit(new BigDecimal("10.00"));

        wallet.debit(new BigDecimal("10.00"));

        assertEquals(0, wallet.getBalance().compareTo(BigDecimal.ZERO));
    }

    // =====================================================================
    //  Amount validation
    // =====================================================================

    @Test
    void zeroIsNotAValidAmount() {
        Wallet wallet = newWallet();
        assertThrows(IllegalArgumentException.class,
                () -> wallet.credit(BigDecimal.ZERO));
    }

    /**
     * The one that matters: without it, a deposit of -100 is a withdrawal with
     * no authorization check on it.
     */
    @Test
    void negativeIsNotAValidAmount() {
        Wallet wallet = newWallet();
        assertThrows(IllegalArgumentException.class,
                () -> wallet.credit(new BigDecimal("-100.00")));
    }

    @Test
    void nullIsNotAValidAmount() {
        Wallet wallet = newWallet();
        assertThrows(IllegalArgumentException.class, () -> wallet.credit(null));
    }

    // =====================================================================
    //  Status rules
    // =====================================================================

    @Test
    void aFrozenWalletRefusesDeposits() {
        Wallet wallet = newWallet();
        wallet.freeze();

        WalletNotActiveException e = assertThrows(WalletNotActiveException.class,
                () -> wallet.credit(new BigDecimal("10.00")));

        assertEquals(WalletStatus.FROZEN, e.getStatus());
    }

    /**
     * The decision that is easy to get wrong in the permissive direction —
     * see {@link WalletStatus} for why letting money INTO a frozen wallet is
     * not the kind thing it looks like.
     */
    @Test
    void aFrozenWalletRefusesWithdrawalsToo() {
        Wallet wallet = newWallet();
        wallet.credit(new BigDecimal("50.00"));
        wallet.freeze();

        assertThrows(WalletNotActiveException.class,
                () -> wallet.debit(new BigDecimal("10.00")));
    }

    @Test
    void freezingIsReversible() {
        Wallet wallet = newWallet();
        wallet.freeze();
        wallet.unfreeze();

        assertEquals(WalletStatus.ACTIVE, wallet.getStatus());
        wallet.credit(new BigDecimal("1.00"));   // must not throw
    }

    @Test
    void freezingIsIdempotent() {
        Wallet wallet = newWallet();
        wallet.freeze();
        wallet.freeze();   // must not throw — the caller's intent is satisfied

        assertEquals(WalletStatus.FROZEN, wallet.getStatus());
    }

    @Test
    void closingIsTerminal_aClosedWalletCannotBeReopened() {
        Wallet wallet = newWallet();
        wallet.close();

        assertEquals(WalletStatus.CLOSED, wallet.getStatus());
        assertThrows(WalletNotActiveException.class, wallet::unfreeze);
        assertThrows(WalletNotActiveException.class, wallet::freeze);
    }

    @Test
    void aClosedWalletRefusesAllMovement() {
        Wallet wallet = newWallet();
        wallet.close();

        assertThrows(WalletNotActiveException.class,
                () -> wallet.credit(new BigDecimal("10.00")));
        assertThrows(WalletNotActiveException.class,
                () -> wallet.debit(new BigDecimal("10.00")));
    }

    /**
     * Closing a wallet with money in it would strand funds in a state that can
     * never transact again.
     */
    @Test
    void aWalletHoldingMoneyCannotBeClosed() {
        Wallet wallet = newWallet();
        wallet.credit(new BigDecimal("0.01"));

        assertThrows(IllegalStateException.class, wallet::close);
        assertEquals(WalletStatus.ACTIVE, wallet.getStatus(),
                "a refused close must not change the status");
    }

    /**
     * Guards the enum's own helper, because {@code allowsMovement} is the
     * single place a fourth state would have to be considered.
     */
    @Test
    void onlyActiveAllowsMovement() {
        assertTrue(WalletStatus.ACTIVE.allowsMovement());
        assertTrue(!WalletStatus.FROZEN.allowsMovement());
        assertTrue(!WalletStatus.CLOSED.allowsMovement());
    }

    /**
     * The balance must never appear in a string that reaches a log file.
     *
     * <p>Same instinct as the Day-05 test asserting {@code User.toString()}
     * omits the password hash — and carrying the same caveat, which is worth
     * repeating because it was a real lesson: <b>this protects log files and
     * does nothing for the wire.</b> Jackson never calls {@code toString()}.
     * What keeps the balance correctly formatted in JSON is
     * {@code WalletResponse}, tested separately.
     */
    @Test
    void toStringDoesNotLeakTheBalance() {
        Wallet wallet = newWallet();
        wallet.credit(new BigDecimal("1234.56"));

        assertTrue(!wallet.toString().contains("1234.56"),
                "balance leaked into toString(): " + wallet);
    }
}

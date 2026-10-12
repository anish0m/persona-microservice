package com.persona.wallet.model;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.persona.wallet.exception.InsufficientFundsException;
import com.persona.wallet.exception.WalletNotActiveException;

/**
 * A user's money. Day-07, microservice build.
 *
 * <h2>Read this next to the monolith's Wallet — the diff IS the lesson</h2>
 *
 * <p>{@code anthropic-persona-monolithic/.../model/Wallet.java} is the same
 * class doing the same job. The money rules, the status rules, the
 * {@code BigDecimal} handling and every guard below are identical. One thing
 * differs, and everything about the architecture follows from it:
 *
 * <pre>
 *   MONOLITH                            HERE
 *   ─────────────────────────────────   ─────────────────────────────────
 *   &#64;OneToOne(fetch = LAZY,              private Long userId;
 *             optional = false)
 *   &#64;JoinColumn(name = "user_id",
 *               unique = true)
 *   private User user;                   // that is the whole difference
 * </pre>
 *
 * <p>An object reference became a number. What that costs:
 *
 * <ul>
 *   <li><b>No navigation.</b> {@code wallet.getUser().getEmail()} is not
 *       expressible. Getting an owner's email is an HTTP call to
 *       user-service, which can be slow, can fail, and can answer
 *       differently than it would have a moment ago.</li>
 *   <li><b>No referential integrity.</b> {@code userId} can hold 99 when no
 *       user 99 exists. The database cannot refuse it — see V1's comment.
 *       In the monolith that was impossible.</li>
 *   <li><b>No type safety.</b> A {@code Long} is a {@code Long}: nothing stops
 *       a caller passing a wallet id, an order id, or a count where a user id
 *       belongs. The compiler caught that for free when the field was a
 *       {@code User}. (A {@code UserId} value type would restore some of it,
 *       and is the usual answer in a mature codebase.)</li>
 * </ul>
 *
 * <p><b>What is gained:</b> this class compiles with no knowledge of
 * user-service whatsoever. No shared jar, no shared entity, no rebuild when
 * {@code User} changes. The two services can be deployed, scaled and broken
 * independently — which is the entire reason to accept everything in the list
 * above.
 *
 * <p>The trap the pom warned about is worth restating here, because JPA will
 * not stop you: <b>an {@code @Entity} may only describe tables THIS service
 * owns.</b> Writing {@code @ManyToOne User} in this file would compile
 * perfectly and then fail at runtime against a database where no
 * {@code users} table exists. The annotation cannot tell you that your
 * architecture is wrong.
 */
@Entity
@Table(name = "wallets")
public class Wallet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * The owner's id in user-service. <b>A plain number, deliberately.</b>
     *
     * <p>{@code updatable = false}: a wallet does not change hands. The column
     * is {@code UNIQUE} in the schema, so one user has at most one wallet —
     * that half of the old foreign key survives the split, because it is a
     * statement about this table alone.
     *
     * <p>The other half does not survive. Nothing here or in the database can
     * confirm that this id refers to a real user. <b>The trust comes from the
     * JWT:</b> the id arrives inside a token this service verifies by
     * signature, so it was issued by user-service to someone who authenticated.
     * That is weaker than a foreign key in a specific way worth naming — it
     * proves the user existed <em>when the token was minted</em>, not that they
     * exist now.
     */
    @Column(name = "user_id", nullable = false, unique = true, updatable = false)
    private Long userId;

    /**
     * The money. {@code BigDecimal}, and {@code precision}/{@code scale} must
     * match the DDL's {@code NUMERIC(19,4)} or {@code ddl-auto=validate}
     * refuses to start.
     *
     * <p>Two traps that do not announce themselves: never
     * {@code new BigDecimal(double)} (it preserves the binary-fraction error),
     * and {@code equals} compares SCALE as well as value, so
     * {@code new BigDecimal("0.0").equals(new BigDecimal("0.00"))} is
     * {@code false}. Use {@code compareTo(...) == 0} for money.
     */
    @Column(name = "balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal balance;

    /**
     * {@code EnumType.STRING}, never the default ORDINAL — see
     * {@link WalletStatus} for why ORDINAL silently rewrites history the day
     * somebody reorders the enum.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private WalletStatus status;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    /** Required by JPA; {@code protected} so application code cannot use it. */
    protected Wallet() {
    }

    /**
     * Opens a new wallet, with a zero balance and ACTIVE status.
     *
     * <p>Takes a {@code Long} where the monolith takes a {@code User}. There
     * is deliberately no constructor accepting an opening balance: money
     * appears through {@link #credit}, which is guarded.
     */
    public Wallet(Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("a wallet must have an owner");
        }
        this.userId = userId;
        this.balance = BigDecimal.ZERO;
        this.status = WalletStatus.ACTIVE;
    }

    /**
     * Adds money. The guarded path, and the ONLY way the balance increases.
     *
     * <p>{@code this.balance = this.balance.add(amount)} and not
     * {@code this.balance.add(amount)} — {@code BigDecimal} is immutable, so
     * the second form computes the right answer, discards it, and reports
     * nothing.
     */
    public void credit(BigDecimal amount) {
        requirePositive(amount);

        if (!status.allowsMovement()) {
            throw new WalletNotActiveException(id, status);
        }

        this.balance = this.balance.add(amount);
    }

    /**
     * Removes money.
     *
     * <p>The balance check is a read-then-write and therefore races; what
     * stops a race from corrupting data is the database's
     * {@code wallets_balance_non_negative} CHECK, which Postgres serialises.
     * <b>Never let the only copy of a correctness guarantee live in
     * application code.</b>
     */
    public void debit(BigDecimal amount) {
        requirePositive(amount);

        if (!status.allowsMovement()) {
            throw new WalletNotActiveException(id, status);
        }

        if (balance.compareTo(amount) < 0) {
            throw new InsufficientFundsException(id, balance, amount);
        }

        this.balance = this.balance.subtract(amount);
    }

    /** Suspends the wallet. Idempotent; refuses a CLOSED wallet. */
    public void freeze() {
        if (status == WalletStatus.CLOSED) {
            throw new WalletNotActiveException(id, status);
        }
        this.status = WalletStatus.FROZEN;
    }

    /** Reverses {@link #freeze}. Also refuses to resurrect a CLOSED wallet. */
    public void unfreeze() {
        if (status == WalletStatus.CLOSED) {
            throw new WalletNotActiveException(id, status);
        }
        this.status = WalletStatus.ACTIVE;
    }

    /**
     * Shuts the wallet permanently. There is no {@code reopen()}.
     *
     * <p>Refuses while money remains — closing a wallet with a balance would
     * strand funds in a state that can never transact again.
     */
    public void close() {
        if (balance.compareTo(BigDecimal.ZERO) != 0) {
            throw new IllegalStateException(
                    "cannot close a wallet holding " + balance.toPlainString());
        }
        this.status = WalletStatus.CLOSED;
    }

    /** {@code compareTo}, not {@code equals} — scale would break the latter. */
    private static void requirePositive(BigDecimal amount) {
        if (amount == null) {
            throw new IllegalArgumentException("amount is required");
        }
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(
                    "amount must be positive, was " + amount.toPlainString());
        }
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    /**
     * No setter, by design: the balance is the result of guarded operations,
     * not a property to assign. A {@code setBalance} would make every rule in
     * this class optional.
     */
    public BigDecimal getBalance() {
        return balance;
    }

    /** Also no setter — transitions go through freeze/unfreeze/close. */
    public WalletStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** Deliberately omits the balance: this string reaches log files. */
    @Override
    public String toString() {
        return "Wallet{id=" + id + ", userId=" + userId + ", status=" + status + "}";
    }
}

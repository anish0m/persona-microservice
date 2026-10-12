package com.persona.wallet;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import com.persona.wallet.model.Wallet;
import com.persona.wallet.model.WalletStatus;
import com.persona.wallet.repository.WalletRepository;
import com.persona.wallet.service.WalletService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * wallet-service against its own real PostgreSQL database. <b>Day-07.</b>
 *
 * <p>The counterpart to the monolith's {@code UserRegistrationIT}, and the
 * differences between the two files are the whole point of building persona
 * twice:
 *
 * <ul>
 *   <li>That file tests a <b>transaction boundary</b> spanning two tables.
 *       This one cannot — there is no second table here, and no transaction
 *       could span the two databases anyway.</li>
 *   <li>That file proves a <b>foreign key</b> refuses a wallet for a
 *       non-existent user. This one proves the opposite: <b>nothing refuses
 *       it.</b> See {@link #aWalletCanBeCreatedForAUserWhoDoesNotExist}, which
 *       is the most important test in this file precisely because it asserts
 *       that a guarantee is GONE.</li>
 * </ul>
 *
 * <p>A test that documents the absence of a safety property is unusual and
 * worth writing. It means the gap is deliberate and reviewed, rather than
 * something nobody noticed — and the day somebody adds a cross-service
 * integrity mechanism, this test is where its arrival gets recorded.
 */
@SpringBootTest
@ActiveProfiles("wallet-it")
@EnabledIfSystemProperty(named = "it", matches = "true")
class WalletIT {

    @Autowired private WalletService walletService;
    @Autowired private WalletRepository walletRepository;
    @Autowired private JdbcClient jdbc;

    /**
     * One statement, where the monolith's equivalent needs two in a specific
     * order (wallets before users, because of the foreign key).
     *
     * <p>Nothing to order here: this database has exactly one table. The
     * cleanup being trivial is the service boundary paying out — there is no
     * referential graph to unwind, because there are no references.
     */
    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM wallets").update();
    }

    // =====================================================================
    //  What the split REMOVED. These are the tests that matter.
    // =====================================================================

    /**
     * <b>A wallet can be opened for a user id that refers to nobody.</b>
     *
     * <p>In the monolith this is impossible — {@code wallets.user_id
     * REFERENCES users(id)} and PostgreSQL refuses the INSERT. The database
     * enforces "no wallet without an owner" for free, on every code path,
     * including ones written years later by people who never read this test.
     *
     * <p>Here there is no such column constraint, because users live in a
     * different database and PostgreSQL has no cross-database foreign key.
     * {@code 999999999} is not a user. The row is created anyway.
     *
     * <p><b>This test asserts the absence of a guarantee, which is why it is
     * phrased as a success rather than a failure.</b> What replaces the
     * foreign key is weaker and lives elsewhere: wallet-service trusts the id
     * because it arrived in a signature-verified JWT, which proves the user
     * existed <em>when the token was minted</em> — not that they exist now.
     *
     * <p>The cost is concrete: delete a user in user-service and their wallet
     * is orphaned, holding a balance nothing points at. The monolith's refusal
     * of {@code ON DELETE CASCADE} was protecting money; that protection is
     * gone and nothing here replaces it.
     */
    @Test
    void aWalletCanBeCreatedForAUserWhoDoesNotExist() {
        Wallet orphan = walletService.createFor(999_999_999L);

        assertNotNull(orphan.getId(),
                "the insert succeeded — there is no foreign key to refuse it");

        Long count = jdbc.sql("SELECT count(*) FROM wallets WHERE user_id = ?")
                .param(999_999_999L).query(Long.class).single();
        assertEquals(1L, count,
                "a wallet exists for a user that does not. This is the cost of "
                        + "the service split, asserted deliberately.");
    }

    /**
     * There is no users table in this database at all.
     *
     * <p>Not a configuration choice — a structural fact. A query that tries to
     * join users fails with {@code relation "users" does not exist}, which is
     * the correct and useful failure: it tells a developer at the first
     * attempt that the data they want belongs to another service.
     *
     * <p>Asserted rather than assumed because "the services have separate
     * databases" is the claim the entire architecture rests on, and a claim
     * that is never checked is a claim that quietly stops being true — someone
     * points both datasource URLs at one database to simplify local
     * development, every test still passes, and the split is now a fiction.
     */
    @Test
    void thisDatabaseHasNoUsersTable() {
        Long tables = jdbc.sql(
                "SELECT count(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name = 'users'")
                .query(Long.class).single();

        assertEquals(0L, tables,
                "a users table exists in persona_wallet — are both services "
                        + "pointing at the same database? The split is a fiction if so.");
    }

    /**
     * {@code user_id} carries no foreign key, confirmed against the catalogue
     * rather than inferred from behaviour.
     *
     * <p>The test above proves an orphan row can be inserted; this one proves
     * <em>why</em>, by asking PostgreSQL directly what constraints exist. The
     * pair is deliberate — behaviour and mechanism, so a future change that
     * made orphans fail for some unrelated reason would not read as "the
     * foreign key is back".
     */
    @Test
    void userIdHasNoForeignKeyConstraint() {
        Long fks = jdbc.sql(
                "SELECT count(*) FROM information_schema.table_constraints tc "
                        + "JOIN information_schema.key_column_usage kcu "
                        + "  ON tc.constraint_name = kcu.constraint_name "
                        + "WHERE tc.table_name = 'wallets' "
                        + "  AND tc.constraint_type = 'FOREIGN KEY' "
                        + "  AND kcu.column_name = 'user_id'")
                .query(Long.class).single();

        assertEquals(0L, fks,
                "a foreign key on wallets.user_id — which cannot point at "
                        + "another database, so what is it pointing at?");
    }

    // =====================================================================
    //  What SURVIVED the split. Local invariants are untouched.
    // =====================================================================

    /**
     * {@code UNIQUE} on {@code user_id} still works, and the contrast with the
     * tests above is the lesson.
     *
     * <p>The foreign key was doing two jobs: "this id refers to a real user"
     * (needs the users table — gone) and "one row per user id" (needs only
     * this table — survives). <b>Local invariants survive a service split;
     * relational ones do not.</b> That distinction is most of what a boundary
     * does to a schema, and it is a useful test for where to draw one.
     */
    @Test
    void aSecondWalletForTheSameUserIsStillRefused() {
        walletService.createFor(7L);

        assertThrows(Exception.class, () -> {
            walletRepository.save(new Wallet(7L));
            walletRepository.flush();
        }, "the UNIQUE constraint on wallets.user_id did not fire");
    }

    /** The non-negative CHECK is a rule about one row, so it survives too. */
    @Test
    void theDatabaseStillRefusesANegativeBalance() {
        walletService.createFor(8L);

        assertThrows(Exception.class,
                () -> jdbc.sql("UPDATE wallets SET balance = -0.01 WHERE user_id = 8").update(),
                "wallets_balance_non_negative did not fire");
    }

    @Test
    void theDatabaseStillRefusesAnUnknownStatus() {
        walletService.createFor(9L);

        assertThrows(Exception.class,
                () -> jdbc.sql("UPDATE wallets SET status = 'SUSPENDED' WHERE user_id = 9").update(),
                "wallets_status_valid did not fire");
    }

    // =====================================================================
    //  Idempotency — what replaces the transaction.
    // =====================================================================

    /**
     * <b>Calling createFor twice opens ONE wallet.</b>
     *
     * <p>This is the single most important behavioural difference from the
     * monolith, where the same method throws {@code IllegalStateException} on
     * a duplicate — correct there, because a second call means a bug.
     *
     * <p>Here a second call means a dropped network packet. user-service
     * posts to this service, this service commits, and the <em>response</em>
     * is lost; from the caller's side that is indistinguishable from a total
     * failure, so it retries. Without idempotency the retry creates a second
     * wallet, or fails and leaves the caller believing no wallet exists.
     *
     * <p><b>Idempotency is the price of not having a transaction.</b> The
     * monolith's rollback made "did it happen?" answerable; here nobody can
     * answer it, so the operation has to be safe to repeat instead.
     */
    @Test
    void createForIsIdempotent() {
        Wallet first = walletService.createFor(11L);
        Wallet second = walletService.createFor(11L);

        assertEquals(first.getId(), second.getId(),
                "a retry created a SECOND wallet — createFor is not idempotent, "
                        + "and a lost response would duplicate a user's wallet");

        Long count = jdbc.sql("SELECT count(*) FROM wallets WHERE user_id = 11")
                .query(Long.class).single();
        assertEquals(1L, count, "two rows for one user");
    }

    // =====================================================================
    //  Storage behaviour — identical to the monolith, verified anyway.
    // =====================================================================

    /**
     * A new wallet is ACTIVE with a zero balance at scale 4.
     *
     * <p>{@code "0.0000"} rather than {@code "0"} is the {@code NUMERIC(19,4)}
     * column's doing, and is exactly the value that fails an
     * {@code assertEquals(BigDecimal.ZERO, ...)} — the scale trap observed
     * against real storage rather than argued about.
     *
     * <p>{@code "ACTIVE"} rather than {@code 0} proves
     * {@code @Enumerated(EnumType.STRING)} took effect. An integer here would
     * mean the annotation defaulted to ORDINAL, and every stored status would
     * be a position in a Java file rather than a meaning.
     */
    @Test
    void aNewWalletIsActiveWithAZeroBalanceAtScaleFour() {
        walletService.createFor(12L);

        String balance = jdbc.sql("SELECT balance::text FROM wallets WHERE user_id = 12")
                .query(String.class).single();
        String status = jdbc.sql("SELECT status FROM wallets WHERE user_id = 12")
                .query(String.class).single();

        assertEquals("0.0000", balance);
        assertEquals("ACTIVE", status);

        // The scale trap, demonstrated rather than described: the value the
        // database just returned is NOT equals() to BigDecimal.ZERO.
        BigDecimal fromDb = new BigDecimal(balance);
        assertNotEquals(BigDecimal.ZERO, fromDb,
                "scale differs, so equals() is false — this is why money "
                        + "assertions use compareTo");
        assertEquals(0, fromDb.compareTo(BigDecimal.ZERO));
    }

    @Test
    void statusIsStoredAsTextNotAnOrdinal() {
        String dataType = jdbc.sql(
                "SELECT data_type FROM information_schema.columns "
                        + "WHERE table_name = 'wallets' AND column_name = 'status'")
                .query(String.class).single();

        assertEquals("character varying", dataType,
                "status column is not textual — @Enumerated(EnumType.STRING) missing?");
    }

    /**
     * A deposit is really persisted — dirty checking, not a {@code save}.
     *
     * <p>Read back with {@code JdbcClient} rather than through the repository:
     * asking Hibernate whether Hibernate wrote something can be answered from
     * the persistence context without a round trip. If {@code @Transactional}
     * were missing from {@code deposit}, the entity would be detached,
     * {@code credit} would mutate a plain object, nothing would be written —
     * and a test asserting on the RETURNED object would still be green.
     */
    @Test
    void aDepositIsWrittenToTheDatabase() {
        walletService.createFor(13L);

        walletService.deposit(13L, new BigDecimal("10.50"));

        String balance = jdbc.sql("SELECT balance::text FROM wallets WHERE user_id = 13")
                .query(String.class).single();

        assertEquals("10.5000", balance,
                "deposit not persisted — is @Transactional missing from deposit()?");
    }

    /**
     * Exact decimal arithmetic survives a round trip through PostgreSQL.
     *
     * <p>Two deposits that sum to {@code 0.30000000000000004} as doubles. The
     * assertion is on the string the database returns, so there is no Java
     * arithmetic in the comparison at all.
     */
    @Test
    void repeatedDepositsAccumulateExactly() {
        walletService.createFor(14L);

        walletService.deposit(14L, new BigDecimal("0.10"));
        walletService.deposit(14L, new BigDecimal("0.20"));

        String balance = jdbc.sql("SELECT balance::text FROM wallets WHERE user_id = 14")
                .query(String.class).single();

        assertEquals("0.3000", balance, "exact arithmetic lost across the round trip");
    }

    /**
     * A frozen wallet refuses a deposit, and the money does not move.
     *
     * <p>The second half earns its keep: a 409 that nonetheless credited the
     * account is indistinguishable from a correct 409 if only the exception is
     * asserted. Status codes are not evidence about data.
     */
    @Test
    void aFrozenWalletRefusesADepositAndTheBalanceDoesNotMove() {
        walletService.createFor(15L);
        walletService.deposit(15L, new BigDecimal("25.00"));
        walletService.freeze(15L);

        assertThrows(Exception.class,
                () -> walletService.deposit(15L, new BigDecimal("5.00")));

        String balance = jdbc.sql("SELECT balance::text FROM wallets WHERE user_id = 15")
                .query(String.class).single();

        assertEquals("25.0000", balance, "the deposit was refused but the money moved anyway");
        assertEquals(WalletStatus.FROZEN, walletService.getByUserId(15L).getStatus());
    }

    /**
     * Flyway applied this service's own migration history.
     *
     * <p>Asserted as "nothing failed" plus a lower bound rather than an exact
     * count, so adding V2 does not require editing this test — a hardcoded
     * number is a maintenance tax that eventually gets paid by bumping it
     * without thinking, at which point it asserts nothing.
     *
     * <p>The {@code success = false} half is the part that matters: Flyway
     * records a failed migration rather than deleting the row, so a schema can
     * be half-applied while the application starts anyway.
     *
     * <p>Note this history is <b>independent of user-service's</b>. Its V1
     * creates users; this V1 creates wallets. Different databases, different
     * {@code flyway_schema_history} tables, no global version of persona —
     * which also means the deployment order between the two services is
     * something humans reason about rather than something Flyway can enforce.
     */
    @Test
    void everyMigrationAppliedSuccessfully() {
        Integer failed = jdbc.sql(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = false")
                .query(Integer.class).single();
        assertEquals(0, failed, "a migration is recorded as FAILED");

        Integer applied = jdbc.sql(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = true")
                .query(Integer.class).single();
        assertTrue(applied >= 1, "expected at least V1, found " + applied);
    }
}

package com.persona.user.repository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.persona.user.model.User;

/**
 * Two profile updates racing for one row. <b>Day-10.</b>
 *
 * <p>Every other test in this service asks whether ONE request produces the
 * right answer. This one asks a question none of them can: what happens when
 * two requests touch the same row at the same instant. The defect it hunts —
 * the LOST UPDATE — does not exist inside either request. It exists only in the
 * relationship between them, which is why 90 green tests said nothing about it.
 *
 * <p><b>Two threads here stand in for two INSTANCES.</b> user-service is built
 * to be scaled horizontally behind the Day-17 gateway, so in production the two
 * racing requests may be handled by different JVMs that share nothing but this
 * database. That is impractical to reproduce in a unit test and does not need
 * to be: from the database's point of view two threads and two instances are
 * the same thing — two connections, two transactions, one row. What the test
 * could NOT prove with threads is any guard held in application memory, which
 * is exactly why the guard is a column.
 *
 * <h2>Why this class is NOT {@code @Transactional}, unlike its two siblings</h2>
 *
 * <p>{@link JpaUserRepositoryIT} and {@link JdbcUserRepositoryIT} are both
 * annotated {@code @Transactional}, so each test rolls back and cleans up for
 * free. Copying that header here would have been the natural thing to do and
 * would have quietly destroyed the test.
 *
 * <p>A race needs TWO transactions that commit independently. Under
 * {@code @Transactional} the test method owns a transaction, and work done on
 * the test thread joins it — so the two "concurrent" updates would be one
 * transaction writing twice, which cannot conflict with itself. The test would
 * pass whether or not the application is safe: a green result proving nothing,
 * the same failure shape as a rollback test that never rolled back.
 *
 * <p>The price of being honest about that is real cleanup, so {@link #cleanUp()}
 * deletes what the test inserted. A committing suite must tidy after itself or
 * it leaves rows for unrelated suites to trip over.
 *
 * <h2>How two threads are made to actually collide</h2>
 *
 * <p>Starting two threads is not enough: the first can finish before the second
 * begins, and then there is no race to observe. A {@link CountDownLatch} holds
 * both threads after they have READ the row and releases them together, which
 * forces the interleaving that the lost update requires:
 *
 * <pre>
 * both threads: read user (version 0)
 *               ... wait on the latch ...
 * latch opens:  both write
 * </pre>
 */
@SpringBootTest
@ActiveProfiles({"jpa", "jpa-it"})
@EnabledIfSystemProperty(named = "it", matches = "true")
class UserConcurrencyIT {

    private static final String EMAIL = "race@persona.test";

    @Autowired
    private UserRepository repository;

    @Autowired
    private JdbcClient jdbc;

    /**
     * Runs a unit of work in its own real transaction.
     *
     * <p>This is the piece that makes the test honest. Each thread needs a
     * transaction that begins and COMMITS independently of the test thread's,
     * and {@code TransactionTemplate} is the programmatic way to get one —
     * {@code @Transactional} would be useless here because it is applied by a
     * proxy at a method boundary, and these units of work are lambdas.
     */
    @Autowired
    private TransactionTemplate tx;

    @BeforeEach
    void seedOneUser() {
        deleteTestRow();
        repository.save(new User(EMAIL, "Race", "Original", "$2a$10$" + "x".repeat(53)));
    }

    @AfterEach
    void cleanUp() {
        deleteTestRow();
    }

    private void deleteTestRow() {
        jdbc.sql("DELETE FROM users WHERE email = ?").param(EMAIL).update();
    }

    /**
     * The whole point of Day-10, expressed as an assertion.
     *
     * <p>Two people update the same profile at the same moment. Each reads the
     * row, changes ONE field, and writes. Without optimistic locking both writes
     * succeed and the second silently overwrites the first — not because either
     * is wrong, but because the second computed its UPDATE from a value that was
     * already stale by the time it was written.
     *
     * <p>With {@code @Version} on {@link User}, the second write's UPDATE carries
     * {@code AND version = 0}, matches zero rows, and Hibernate raises
     * {@link ObjectOptimisticLockingFailureException}. The anomaly is not
     * prevented — it is DETECTED, which is the distinction that matters: a
     * silent wrong answer has been converted into a loud failure.
     *
     * <p>Note what is asserted. Not merely "an exception was thrown" — also that
     * EXACTLY ONE of the two threads failed, and that the surviving row holds
     * the winner's value rather than a mixture. A test asserting only that
     * something threw would pass if BOTH threads failed, which would be a
     * different and equally broken application.
     */
    @Test
    void twoSimultaneousProfileUpdatesDoNotSilentlyOverwriteEachOther() throws Exception {
        CountDownLatch bothHaveRead = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);

        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            pool.submit(updateFirstNameTo("Alice", bothHaveRead, release, firstFailure));
            pool.submit(updateFirstNameTo("Bob", bothHaveRead, release, secondFailure));

            // Both threads have now READ the row and are holding version 0.
            assertTrue(bothHaveRead.await(10, TimeUnit.SECONDS),
                    "both threads should have read the row before either writes");
            release.countDown();

            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS),
                    "both threads should finish");
        } finally {
            pool.shutdownNow();
        }

        Throwable a = firstFailure.get();
        Throwable b = secondFailure.get();

        // Exactly one must fail. Zero means the lost update happened silently;
        // two means neither write landed, which is a different bug.
        long failures = (a == null ? 0 : 1) + (b == null ? 0 : 1);
        assertEquals(1, failures,
                "exactly one of the two concurrent updates must be rejected, but "
                        + "first=" + describe(a) + " second=" + describe(b));

        Throwable loser = a != null ? a : b;
        assertInstanceOf(ObjectOptimisticLockingFailureException.class, rootSpringCause(loser),
                "the losing write must be rejected by optimistic locking, not by "
                        + "some unrelated failure");

        // The winner's value survived intact — not a blend of the two.
        String stored = jdbc.sql("SELECT first_name FROM users WHERE email = ?")
                .param(EMAIL).query(String.class).single();
        assertTrue(stored.equals("Alice") || stored.equals("Bob"),
                "stored name should be one writer's value, but was: " + stored);

        // And the version moved exactly once, because exactly one write committed.
        Long version = jdbc.sql("SELECT version FROM users WHERE email = ?")
                .param(EMAIL).query(Long.class).single();
        assertEquals(1L, version,
                "exactly one commit should have incremented the version");
    }

    /**
     * The control case: the same two writes, run one after the other, must BOTH
     * succeed.
     *
     * <p>Without this test the one above could be satisfied by an application
     * that rejects every second update regardless of timing — which would be
     * "safe" and useless. This pins down that the rejection is caused by the
     * CONFLICT and not by the update itself.
     */
    @Test
    void sequentialUpdatesBothSucceedAndEachIncrementsTheVersion() {
        tx.executeWithoutResult(status -> {
            User user = repository.getByEmail(EMAIL);
            user.setFirstName("First");
            repository.update(user);
        });

        tx.executeWithoutResult(status -> {
            User user = repository.getByEmail(EMAIL);
            user.setFirstName("Second");
            repository.update(user);
        });

        String stored = jdbc.sql("SELECT first_name FROM users WHERE email = ?")
                .param(EMAIL).query(String.class).single();
        assertEquals("Second", stored, "the later sequential write should win");

        Long version = jdbc.sql("SELECT version FROM users WHERE email = ?")
                .param(EMAIL).query(Long.class).single();
        assertEquals(2L, version, "two sequential commits should bump the version twice");
    }

    /**
     * A new row starts at version 0, supplied by the database default rather
     * than by Java.
     *
     * <p>Worth its own assertion because V4 and the {@code @Version} field are
     * two separate claims about the same column, and they can disagree. The
     * migration's {@code DEFAULT 0} covers rows written by the JDBC profile,
     * which does not know the field exists.
     */
    @Test
    void aFreshlySavedUserStartsAtVersionZero() {
        Long version = jdbc.sql("SELECT version FROM users WHERE email = ?")
                .param(EMAIL).query(Long.class).single();
        assertEquals(0L, version, "a newly inserted user should start at version 0");
    }

    private Runnable updateFirstNameTo(String newName,
                                       CountDownLatch bothHaveRead,
                                       CountDownLatch release,
                                       AtomicReference<Throwable> failure) {
        return () -> {
            try {
                tx.executeWithoutResult(status -> {
                    User user = repository.getByEmail(EMAIL);
                    user.setFirstName(newName);

                    // Read done, holding this transaction's snapshot of the
                    // version. Wait here so the other thread reaches the same
                    // point before either of us writes.
                    bothHaveRead.countDown();
                    awaitQuietly(release);

                    repository.update(user);
                });
            } catch (Throwable t) {
                failure.set(t);
                // The latch must still be released on the failure path, or a
                // thread that dies early would hang the other one for the full
                // await timeout and the test would fail for the wrong reason.
                bothHaveRead.countDown();
            }
        };
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch was never released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * Spring wraps the Hibernate exception, and the wrapping depth differs
     * depending on where the flush happens — at {@code update()} or at commit.
     * Walking the chain makes the assertion about the CAUSE rather than about
     * which layer happened to catch it.
     */
    private static Throwable rootSpringCause(Throwable t) {
        Throwable current = t;
        while (current != null) {
            if (current instanceof ObjectOptimisticLockingFailureException) {
                return current;
            }
            current = current.getCause();
        }
        return t;
    }

    private static String describe(Throwable t) {
        return t == null ? "ok" : t.getClass().getSimpleName();
    }
}

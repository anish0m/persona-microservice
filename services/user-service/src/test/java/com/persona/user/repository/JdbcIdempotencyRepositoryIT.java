package com.persona.user.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The claim, executed against a real PostgreSQL.
 *
 * <p><b>This class exists for one assertion that no other test in the project
 * can make.</b> {@code InMemoryIdempotencyRepository} implements {@code claim}
 * with {@code ConcurrentHashMap.putIfAbsent}, which is atomic — so a test
 * against it proves the service's logic is correct <em>given</em> an atomic
 * claim, and says nothing about whether the claim is actually atomic. The
 * atomicity is a property of V5's primary key, and only a database has one.
 *
 * <p>Put the other way round: if {@code ON CONFLICT DO NOTHING} were deleted and
 * replaced with a {@code SELECT} followed by an {@code INSERT}, every unit test
 * in this project would stay green. {@link #concurrentClaimsForOneKeyProduceExactlyOneWinner()}
 * is the only thing that would go red.
 */
@SpringBootTest
@ActiveProfiles({"jdbc", "jdbc-it"})
@EnabledIfSystemProperty(named = "it", matches = "true")
class JdbcIdempotencyRepositoryIT {

    @Autowired
    private IdempotencyRepository repository;

    @Autowired
    private JdbcClient jdbc;

    private static final String SCOPE = "khi0ne@example.com";
    private static final String KEY = "key-1";
    private static final String FINGERPRINT = "abc123";

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM idempotency_keys").update();
    }

    @Test
    void firstClaimWinsAndReturnsEmpty() {
        assertTrue(repository.claim(SCOPE, KEY, FINGERPRINT).isEmpty(),
                "empty means this caller claimed the key");

        IdempotencyRecord stored = repository.find(SCOPE, KEY).orElseThrow();
        assertEquals(IdempotencyRecord.IN_PROGRESS, stored.status());
        assertEquals(FINGERPRINT, stored.requestFingerprint());
        assertNull(stored.responseStatus(), "no answer exists yet");
    }

    @Test
    void secondClaimLosesAndSeesTheFirstRecord() {
        repository.claim(SCOPE, KEY, FINGERPRINT);

        Optional<IdempotencyRecord> loser = repository.claim(SCOPE, KEY, FINGERPRINT);

        assertTrue(loser.isPresent(), "present means someone else holds the key");
        assertEquals(IdempotencyRecord.IN_PROGRESS, loser.get().status());
    }

    @Test
    void theSameKeyUnderADifferentScopeIsADifferentKey() {
        repository.claim(SCOPE, KEY, FINGERPRINT);

        assertTrue(repository.claim("someone.else@example.com", KEY, FINGERPRINT).isEmpty(),
                "keys are scoped; one client's key-1 must not block another's");
    }

    @Test
    void completeStoresTheResponseForReplay() {
        repository.claim(SCOPE, KEY, FINGERPRINT);

        repository.complete(SCOPE, KEY, IdempotencyRecord.COMPLETED, 201, "{\"email\":\"x\"}");

        IdempotencyRecord stored = repository.find(SCOPE, KEY).orElseThrow();
        assertEquals(IdempotencyRecord.COMPLETED, stored.status());
        assertEquals(201, stored.responseStatus());
        assertEquals("{\"email\":\"x\"}", stored.responseBody());
        assertTrue(stored.isReplayable());
    }

    /**
     * A stored answer is final.
     *
     * <p>Without the {@code status = 'IN_PROGRESS'} guard in the UPDATE, a late
     * or duplicated settle would overwrite a response a client may still be
     * retrying for — turning a recorded 201 into a 409 after the fact.
     */
    @Test
    void completeCannotOverwriteAnAnswerThatIsAlreadyStored() {
        repository.claim(SCOPE, KEY, FINGERPRINT);
        repository.complete(SCOPE, KEY, IdempotencyRecord.COMPLETED, 201, "first");

        repository.complete(SCOPE, KEY, IdempotencyRecord.FAILED, 409, "second");

        IdempotencyRecord stored = repository.find(SCOPE, KEY).orElseThrow();
        assertEquals(201, stored.responseStatus(), "the first answer must survive");
        assertEquals("first", stored.responseBody());
    }

    @Test
    void releaseFreesAClaimThatNeverProducedAnAnswer() {
        repository.claim(SCOPE, KEY, FINGERPRINT);

        repository.release(SCOPE, KEY);

        assertTrue(repository.find(SCOPE, KEY).isEmpty(), "the key must be usable again");
        assertTrue(repository.claim(SCOPE, KEY, FINGERPRINT).isEmpty());
    }

    /**
     * Releasing must not delete a settled answer.
     *
     * <p>The guard matters because {@code release} is called from a catch block.
     * An exception thrown <em>after</em> the outcome was recorded — anything in
     * the response-writing path — would otherwise erase the stored response and
     * reopen a request that had already been answered.
     */
    @Test
    void releaseCannotDeleteAStoredAnswer() {
        repository.claim(SCOPE, KEY, FINGERPRINT);
        repository.complete(SCOPE, KEY, IdempotencyRecord.COMPLETED, 201, "body");

        repository.release(SCOPE, KEY);

        assertTrue(repository.find(SCOPE, KEY).isPresent(), "a settled answer is not releasable");
    }

    /**
     * <b>The assertion this whole class exists for.</b>
     *
     * <p>Sixteen threads claim one key simultaneously. Exactly one must win.
     *
     * <p><b>Why a latch and not just sixteen sequential calls.</b> Sequential
     * calls would pass against a {@code SELECT}-then-{@code INSERT}
     * implementation too, because the first insert commits before the second
     * reads. The race only exists when the reads happen before any write, which
     * is what the latch forces: every thread arrives, blocks, and is released
     * together.
     *
     * <p><b>Why the count is the assertion and not the absence of an
     * exception.</b> A broken implementation does not necessarily throw — with
     * {@code ON CONFLICT DO NOTHING} removed in favour of a lookup, several
     * threads would simply all believe they won, quietly, and the work would run
     * several times. So the test counts winners (must be 1) and losers (must be
     * 15), because a status, a colour, or a silence cannot tell those apart.
     */
    @Test
    void concurrentClaimsForOneKeyProduceExactlyOneWinner() throws InterruptedException {
        int threads = 16;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        AtomicInteger winners = new AtomicInteger();
        AtomicInteger losers = new AtomicInteger();
        List<Throwable> errors = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            Thread.ofPlatform().start(() -> {
                try {
                    ready.countDown();
                    go.await();
                    if (repository.claim(SCOPE, KEY, FINGERPRINT).isEmpty()) {
                        winners.incrementAndGet();
                    } else {
                        losers.incrementAndGet();
                    }
                } catch (Throwable t) {
                    synchronized (errors) {
                        errors.add(t);
                    }
                } finally {
                    done.countDown();
                }
            });
        }

        assertTrue(ready.await(10, TimeUnit.SECONDS), "threads did not start");
        go.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "threads did not finish");

        assertTrue(errors.isEmpty(), () -> "claim threw: " + errors);
        assertEquals(1, winners.get(), "exactly one caller may do the work");
        assertEquals(threads - 1, losers.get(), "every other caller must be told it lost");

        Integer rows = jdbc.sql("SELECT count(*) FROM idempotency_keys WHERE scope = ?")
                .param(SCOPE).query(Integer.class).single();
        assertEquals(1, rows, "the constraint must have permitted exactly one row");
    }

    /**
     * A loser must never be told it won, even while the winner is uncommitted.
     *
     * <p>This is the window the JDBC implementation's javadoc warns about: the
     * primary key sees uncommitted rows, but a {@code READ COMMITTED} snapshot
     * does not, so the losing transaction can collide with a row it then cannot
     * SELECT. The implementation must report a loss anyway — synthesising an
     * {@code IN_PROGRESS} record rather than returning empty, because returning
     * empty would mean "you won" and two callers would do the work.
     */
    @Test
    void aLoserIsStillALoserWhenTheWinnerHasNotCommitted() throws Exception {
        CountDownLatch winnerClaimed = new CountDownLatch(1);
        CountDownLatch loserFinished = new CountDownLatch(1);
        AtomicInteger winnersSeen = new AtomicInteger();

        // Holds an open transaction with an uncommitted claim in it.
        Thread holder = Thread.ofPlatform().start(() -> {
            jdbc.sql("BEGIN").update();
            try {
                jdbc.sql("""
                                INSERT INTO idempotency_keys
                                    (scope, idempotency_key, status, request_fingerprint)
                                VALUES (?, ?, 'IN_PROGRESS', ?)
                                """)
                        .param(SCOPE).param(KEY).param(FINGERPRINT).update();
                winnerClaimed.countDown();
                loserFinished.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                jdbc.sql("COMMIT").update();
            }
        });

        assertTrue(winnerClaimed.await(10, TimeUnit.SECONDS));

        Thread loser = Thread.ofPlatform().start(() -> {
            if (repository.claim(SCOPE, KEY, FINGERPRINT).isEmpty()) {
                winnersSeen.incrementAndGet();
            }
            loserFinished.countDown();
        });

        loser.join(TimeUnit.SECONDS.toMillis(30));
        holder.join(TimeUnit.SECONDS.toMillis(30));

        assertEquals(0, winnersSeen.get(),
                "a second caller must never be told it claimed a key that is already held");
        assertFalse(repository.find(SCOPE, KEY).isEmpty());
    }
}

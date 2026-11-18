package com.persona.user.service;

import com.persona.user.dto.CreateUserRequest;
import com.persona.user.dto.UpdateProfileRequest;
import com.persona.user.dto.UserResponse;
import com.persona.user.model.User;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import jakarta.persistence.EntityManagerFactory;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cache, proved by counting queries rather than by reading answers.
 * <b>Day-12.</b>
 *
 * <h2>Why every assertion here is a count</h2>
 *
 * <p><b>A cache that is switched off returns exactly the right answer.</b>
 * That one sentence dictates the whole shape of this file. Delete
 * {@code @EnableCaching} from {@code CacheConfig}, or misspell a cache name,
 * or remove the Redis dependency so the annotations fall back to an
 * in-process map — and a test that calls {@code getProfile} twice and asserts
 * the name is "Ada" passes in every one of those cases. It would be a green
 * test proving only that PostgreSQL works.
 *
 * <p>So the subject under test is not the value, it is <b>the number of trips
 * to the database</b>. Hibernate's {@link Statistics} is the instrument, and
 * the profile {@code cache-it} exists to switch it on.
 *
 * <p>This is the same discipline as Day-09's N+1 work: a count is the only
 * observable that distinguishes "correct" from "correct and doing the work it
 * was supposed to skip". Reasoning about whether a cache is being consulted
 * is unreliable in a way counting is not.
 *
 * <h2>Why this class is NOT {@code @Transactional}</h2>
 *
 * <p>The usual test convenience — annotate the class, let every method roll
 * back, inherit a clean database — would make the central test in this file
 * <b>unable to fail</b>.
 *
 * <p>{@link ProfileCacheInvalidator} is bound to
 * {@code TransactionPhase.AFTER_COMMIT}. Inside a {@code @Transactional}
 * test, the surrounding transaction is rolled back at the end of the method
 * and <em>never commits</em> — so the listener never runs, no matter how
 * correct it is. Worse, the service's own {@code @Transactional} would join
 * the test's transaction rather than opening its own, so there is no commit
 * at any point. A test asserting "the cache was evicted" would fail against
 * working code, and a test asserting the opposite would pass against broken
 * code.
 *
 * <p>So this suite commits for real, and cleans up after itself in
 * {@link #cleanUp()}. That obligation is the price of being able to observe a
 * commit at all — and leaving it out is how a committing suite breaks
 * unrelated suites with foreign-key errors they did not cause.
 *
 * <h2>What this suite CANNOT prove in this architecture</h2>
 *
 * <p>Worth stating plainly, because the gap is exactly where the microservice
 * caching argument lives. Every test here runs <b>one instance</b> of
 * user-service. The failure that justifies choosing Redis over a
 * {@code ConcurrentHashMap} — replica A evicting its own copy while replica B
 * serves a stale one — is therefore not reachable from this file: with one
 * replica there is no second copy to go stale, so an in-process cache would
 * pass every assertion below.
 *
 * <p>That is not an argument for a weaker claim; it is an argument about what
 * a test can be. Proving it needs two processes sharing a database and a
 * gateway in front of them, which is Day-17's integration work rather than a
 * unit of this suite. What this file pins instead is everything that must
 * hold <em>per instance</em> — the entry really is in Redis rather than on the
 * heap ({@link #theEntryIsStoredInRedisUnderAReadableKey}), the key carries
 * the service-naming prefix that makes a shared key space navigable, and the
 * eviction is bound to the commit.
 *
 * <p><b>The Redis assertion is the one standing in for the untestable
 * part.</b> A shared store is the mechanism by which two replicas agree; if
 * the value is provably in Redis under a known key, the multi-replica
 * behaviour follows from Redis rather than from this service. Which is the
 * useful shape of the trade: move the guarantee somewhere a single-process
 * test can observe it.
 */
@SpringBootTest
@ActiveProfiles({"jpa", "cache-it"})
@EnabledIfSystemProperty(named = "it", matches = "true")
class UserCacheIT {

    @Autowired
    private UserService service;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    /** Unique per test, so a leftover row can never make a test pass. */
    private String email;

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    /** Query count since the last {@code clear()}. */
    private long queries() {
        return statistics().getPrepareStatementCount();
    }

    @BeforeEach
    void setUp() {
        email = "cache-" + UUID.randomUUID() + "@example.com";
        service.register(new CreateUserRequest(email, "Ada", "Lovelace", "password123"));

        // Start every test from an empty cache AND a zeroed counter.
        //
        // Without the evict, register() having warmed nothing is an
        // assumption rather than a fact — and an entry left by a previous
        // test under the same key would make the first getProfile a HIT,
        // reading zero queries, which is the number a passing test expects.
        // The test would pass for the wrong reason, which is the specific
        // failure this whole file is built to avoid.
        cacheManager.getCache(UserService.USERS_CACHE).evict(email);
        statistics().clear();
    }

    @AfterEach
    void cleanUp() {
        // This suite COMMITS, so it must clean up. Both stores, and in this
        // order for no deep reason — they are independent.
        jdbc.sql("DELETE FROM users WHERE email = ?").param(email).update();
        cacheManager.getCache(UserService.USERS_CACHE).evict(email);
    }


    /**
     * Reads a cached document, tolerating a few milliseconds of
     * read-after-write delay. <b>Day-12.</b>
     *
     * <p>This helper exists because of a flake that took eight runs of the
     * full suite to localise, and it is worth recording precisely, because
     * the obvious diagnoses were all wrong.
     *
     * <p>The symptom: {@code deletingAUserInvalidatesTheCachedCopy} failed on
     * its <b>precondition</b> — the {@code assertNotNull} proving the profile
     * is cached, before the assertion the test is actually for — in roughly
     * three runs out of eight. Diagnostic output showed
     * {@code first=false retry=true}: the identical {@code GET}, issued
     * microseconds later, returned the document. Other probes ruled out the
     * plausible causes one at a time — {@code TYPE} was {@code string} so it
     * was not the {@code WRONGTYPE} trap; {@code TTL} read 3 and the gap was
     * 4ms, so the 3-second expiry was nowhere near it; every test uses a
     * fresh UUID email, so no other test's eviction could reach this key; and
     * the key was <em>present in {@code KEYS *}</em> in the same breath as
     * {@code GET} returning null.
     *
     * <p>What is left is read-after-write visibility between two different
     * paths to Redis: {@code RedisCacheManager} writes the entry through its
     * own connection, and {@code StringRedisTemplate} reads it through
     * another. Redis itself is single-threaded and strictly ordered, so the
     * ordering guarantee people reach for — "my command is atomic against
     * every other" — is about commands once they <em>arrive</em>. It says
     * nothing about when a client's write has been flushed from a netty
     * buffer, and these are two clients.
     *
     * <p><b>It is a bug in the test, not in persona.</b> No production code
     * reads a cache entry through a second connection a microsecond after
     * writing it; the application reads through the same cache abstraction
     * that wrote it. Only a test that inspects Redis directly — which is
     * exactly what makes this suite able to prove anything — is exposed.
     *
     * <p>So the wait is short, bounded, and only ever lengthens a run that
     * would otherwise fail: it returns on the first successful read. A bare
     * retry would have hidden the finding; a {@code Thread.sleep} before
     * every read would slow every test to pay for one. <b>Note what this
     * helper must never be used for: asserting that something is ABSENT.</b>
     * Waiting for a key to appear is safe, because the wait ends when it
     * does; waiting for one to vanish would turn "the eviction never
     * happened" into a timeout and then into a pass, which is the
     * vacuous-green failure this entire file is built against. The two
     * eviction assertions therefore read Redis directly, with no retry.
     */
    private String awaitCached(String key) {
        for (int attempt = 0; attempt < 50; attempt++) {
            String value = redis.opsForValue().get(key);
            if (value != null) {
                return value;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }
        return null;
    }

    /**
     * THE CONTROL. Proves the instrument works before anything relies on it.
     *
     * <p>Every other test in this file concludes something from a count that
     * <b>stays the same</b>. That inference is only valid if the counter is
     * capable of going up — and a counter that is silently switched off
     * reports zero for everything, which is indistinguishable from a perfect
     * cache. {@code generate_statistics} is exactly the kind of property that
     * is easy to put in the wrong file (Day-04 lost an afternoon to that).
     *
     * <p>So: one uncached read must cost at least one query. If this test
     * fails, no other assertion in this class means anything, which is why it
     * is first.
     */
    @Test
    void theQueryCounterActuallyCounts() {
        long before = queries();
        service.getProfile(email);
        long after = queries();

        assertTrue(after > before,
                "Hibernate statistics are not collecting — every count in this suite "
                + "would read 0, which is indistinguishable from a perfect cache. "
                + "Check hibernate.generate_statistics in application-cache-it.properties. "
                + "before=" + before + " after=" + after);
    }

    /**
     * The hit. Two reads, one query.
     *
     * <p>This is the test that fails if {@code @EnableCaching} is removed,
     * and it is the only kind of test that does — the returned user is
     * correct either way.
     */
    @Test
    void secondReadIsServedFromTheCache() {
        UserResponse first = service.getProfile(email);
        long afterFirst = queries();
        assertTrue(afterFirst > 0, "the first read should have hit the database");

        UserResponse second = service.getProfile(email);
        long afterSecond = queries();

        assertEquals(afterFirst, afterSecond,
                "the second read queried the database again — the cache is not being "
                + "consulted. Most likely @EnableCaching is missing from CacheConfig, "
                + "or the cache name on @Cacheable does not match USERS_CACHE.");
        assertEquals(first.email(), second.email());
        assertEquals("Ada", second.firstName());
        assertEquals("@ada-lovelace", second.username(),
                "a derived field did not survive the round trip through Redis — "
                + "UserResponse.username is computed by User.getUsername(), so it has "
                + "to be present IN the cached document; a record reconstructed from "
                + "JSON cannot recompute it");
    }

    /**
     * The value really is in Redis, not in a local map.
     *
     * <p>Distinct from the test above, which passes against an in-process
     * {@code ConcurrentHashMap} just as happily. Only looking in Redis
     * distinguishes a shared cache from a per-instance one — and that
     * distinction is the entire reason for the Redis dependency, since a map
     * is faster and would be the better choice if correctness across
     * instances did not matter.
     *
     * <p>The key is asserted in its full, readable form on purpose. Redis has
     * one flat key space, so the key naming is the only schema there is; a
     * prefix that silently stops being applied would put profiles in the root
     * namespace where nothing groups them and no prefixed invalidation would
     * find them.
     */
    @Test
    void theEntryIsStoredInRedisUnderAReadableKey() {
        service.getProfile(email);

        String key = "user-service-test:" + UserService.USERS_CACHE + "::" + email;
        String stored = awaitCached(key);

        assertNotNull(stored,
                "nothing in Redis at " + key + " — either the cache is an in-process "
                + "map (is spring-boot-starter-data-redis on the classpath?) or the "
                + "key prefix is not being applied. NOTE: a custom "
                + "RedisCacheConfiguration @Bean REPLACES the spring.cache.redis.* "
                + "properties rather than composing with them, which is exactly how "
                + "the prefix went missing once already — see CacheConfig.");
        assertTrue(stored.contains("Ada"),
                "the stored value is not readable JSON, so the serializer in "
                + "CacheConfig is not being applied. A JDK-serialized value would be "
                + "binary here. stored=" + stored);
    }

    /**
     * THE SECURITY ASSERTION. No credential may be in the cache.
     *
     * <p>Not hypothetical. {@code @Cacheable} was first placed on
     * {@code getByEmail}, which returns the {@link User} entity, and the
     * stored document was:
     *
     * <pre>
     *   {"email":"...","firstName":"Ada",
     *    "passwordHash":"$2a$10$jnh0ADlWhT7xE9H55Z0RJ...", ...}
     * </pre>
     *
     * <p>Every BCrypt hash for every user who had been read, in plain text,
     * in a Redis with no password. <b>Nothing failed.</b> The cache worked,
     * the endpoints were correct, and five of six tests in this file passed —
     * the leak was visible only because a debugging print happened to be in
     * this method at the time.
     *
     * <p>So it is an assertion now, because the thing that caught it was
     * luck. {@code UserResponseTest} already proves the password is absent
     * from the HTTP response; <b>Redis is a second wire</b>, and until today
     * nothing checked it. A future change to what {@code getProfile} returns
     * — switching it back to the entity, or adding a field to
     * {@code UserResponse} — fails here instead of shipping.
     *
     * <p>Asserted against the raw stored bytes rather than the returned
     * object, deliberately. The object is reconstructed from the document, so
     * a field the record does not declare is invisible in the object and
     * still present in Redis. Only reading what was actually written can see
     * it.
     */
    @Test
    void theCachedDocumentContainsNoCredential() {
        service.getProfile(email);

        String key = "user-service-test:" + UserService.USERS_CACHE + "::" + email;
        String stored = awaitCached(key);
        assertNotNull(stored, "precondition: the profile is cached");

        assertFalse(stored.contains("passwordHash"),
                "A PASSWORD HASH IS IN REDIS. The cached value is the User entity "
                + "rather than UserResponse — see UserService.getProfile. stored="
                + stored);
        assertFalse(stored.contains("$2a$"),
                "a BCrypt hash is in the cached document under some other field name. "
                + "stored=" + stored);
        assertTrue(stored.contains("Ada"),
                "precondition: the document is the profile we think it is");
    }

    /**
     * THE CENTRAL TEST. An update must not leave a stale profile readable.
     *
     * <p>This is the one that fails if the {@code publishEvent} line is
     * deleted from {@code attemptUpdateProfile}, and it is worth being
     * precise about what it would look like: the update itself succeeds, the
     * database is correct, the returned object is correct, and
     * <b>subsequent reads return the old name</b> until the TTL expires.
     * Nothing throws.
     *
     * <p>It also fails if the eviction is done with a plain
     * {@code @CacheEvict} and the window happens to be hit — but not
     * reliably, which is the honest limitation of this test and the reason
     * {@link ProfileCacheInvalidator}'s javadoc carries the argument that the
     * test cannot. A race that needs a pathological interleaving is not
     * something a single-threaded test reproduces; the defence against it is
     * the phase being correct by construction.
     */
    @Test
    void updatingAProfileInvalidatesTheCachedCopy() {
        UserResponse cached = service.getProfile(email);
        assertEquals("Ada", cached.firstName());

        service.updateProfile(email, new UpdateProfileRequest("Grace", "Hopper", null));

        long beforeReread = queries();
        UserResponse reread = service.getProfile(email);
        long afterReread = queries();

        assertEquals("Grace", reread.firstName(),
                "a STALE profile was served after a successful update. The cached entry "
                + "was not evicted — check that attemptUpdateProfile still publishes "
                + "ProfileChangedEvent, and that ProfileCacheInvalidator is a @Component "
                + "whose listener phase is AFTER_COMMIT.");
        assertEquals("Hopper", reread.lastName());

        assertTrue(afterReread > beforeReread,
                "the re-read did not query the database, so it was served from the cache. "
                + "It returned the right name, which means the cache was UPDATED rather "
                + "than evicted — see ProfileCacheInvalidator on why a cache-aside write "
                + "path deletes instead of writing.");
    }

    /**
     * Deletion must invalidate too, and this is the sharper of the two write
     * paths.
     *
     * <p>A stale <em>name</em> is a cosmetic wrong answer. A stale
     * <em>existence</em> is not: without the eviction, a deleted account
     * keeps answering reads with a full profile for the whole TTL. This test
     * is what makes the {@code @Transactional} on {@code deleteByEmail}
     * load-bearing — remove it and the {@code AFTER_COMMIT} listener silently
     * does not run, with nothing else in the suite noticing.
     */
    @Test
    void deletingAUserInvalidatesTheCachedCopy() {
        service.getProfile(email);

        String key = "user-service-test:" + UserService.USERS_CACHE + "::" + email;
        assertNotNull(awaitCached(key), "precondition: the profile is cached");

        service.deleteByEmail(email);

        assertEquals(null, redis.opsForValue().get(key),
                "the cached profile outlived the user. A deleted account would keep "
                + "answering GET /users/{email} with 200 and a full profile until the "
                + "TTL expired. Is deleteByEmail still @Transactional? Without a "
                + "transaction the AFTER_COMMIT listener does not run at all.");
    }

    /**
     * The TTL expires an entry that nothing evicted.
     *
     * <p>Not a correctness mechanism and tested anyway, because it is the
     * backstop for every eviction that never happened — an unreachable Redis,
     * a failed post-commit listener, a race. The bound on how wrong an answer
     * may be is this number and nothing else, so it is worth knowing it is
     * actually applied rather than silently defaulted.
     *
     * <p>The entry is removed <b>behind the application's back</b>, with raw
     * SQL, precisely so no eviction is triggered. Going through
     * {@code service.updateProfile} would publish an event and evict the key,
     * and the test would then pass with the TTL set to a year.
     *
     * <p>3 seconds comes from {@code application-cache-it.properties}, and a
     * sleep in a test is a cost accepted deliberately here: the thing under
     * test is the passage of time.
     */
    @Test
    void anEntryExpiresOnItsTtl() throws InterruptedException {
        service.getProfile(email);
        String key = "user-service-test:" + UserService.USERS_CACHE + "::" + email;
        assertNotNull(awaitCached(key), "precondition: the profile is cached");

        Long ttl = redis.getExpire(key);
        assertNotNull(ttl);
        assertNotEquals(-1L, ttl.longValue(),
                "the key has NO expiry (-1). Redis returns -1 for a key that exists "
                + "with no TTL and -2 for a key that does not exist — so a cache with "
                + "no TTL at all reads as a successfully cached value. "
                + "Is spring.cache.redis.time-to-live set in this profile?");
        assertTrue(ttl <= 3, "the TTL should be the 3s configured for this profile, not " + ttl);

        // Change the row underneath the cache, with no eviction of any kind.
        jdbc.sql("UPDATE users SET first_name = 'Expired' WHERE email = ?")
                .param(email).update();

        Thread.sleep(3500);

        assertEquals(null, redis.opsForValue().get(key),
                "the entry survived its TTL");
        assertEquals("Expired", service.getProfile(email).firstName(),
                "after expiry the read should have gone back to PostgreSQL and seen the "
                + "row as it now is");
    }
}

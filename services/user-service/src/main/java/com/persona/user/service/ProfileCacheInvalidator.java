package com.persona.user.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Deletes a user's cached profile once the write that changed it has
 * committed. <b>Day-12.</b>
 *
 * <h2>Why this class exists instead of an annotation</h2>
 *
 * <p>The obvious implementation is one line on the update method:
 *
 * <pre>
 *   &#64;Transactional
 *   &#64;CacheEvict(cacheNames = "users", key = "#email")
 *   public User attemptUpdateProfile(String email, UpdateProfileRequest request)
 * </pre>
 *
 * <p>It is wrong, and it is wrong in a way that passes every test. Both
 * annotations are implemented by proxies, and the cache interceptor sits
 * <b>inside</b> the transaction interceptor — so the eviction happens when
 * the method returns, which is <em>before</em> the commit:
 *
 * <pre>
 *   BEGIN                                       (transaction proxy)
 *     UPDATE users SET first_name = 'Grace'
 *     DEL user-service:users::ada@example.com   (cache proxy, method returned)
 *     ...  &lt;-- ANOTHER REQUEST READS HERE
 *   COMMIT                                      (transaction proxy)
 * </pre>
 *
 * <p>A read arriving in that window misses the cache, queries PostgreSQL, and
 * finds the row <b>as it was before the uncommitted update</b> — the new value
 * is invisible outside its own transaction until commit. It then caches that
 * old value. The eviction has already happened and no further write is
 * coming, so the stale entry is not corrected for the full TTL.
 *
 * <p>Note the shape of that bug: a window of a millisecond or two produces
 * <em>ten minutes</em> of wrong answers, from a race that will not reproduce
 * under a debugger. {@code beforeInvocation = true} does not help — it moves
 * the delete even earlier. There is no ordering of "update the database" and
 * "delete the cache key" inside one transaction that closes it, because the
 * problem is not the order of the two statements; it is that one of them is
 * invisible to everyone else until the commit.
 *
 * <p><b>And in this architecture the window is wider than in the monolith.</b>
 * The reader that loses this race need not be another thread in this JVM — it
 * can be any replica of user-service, reading the same shared Redis and the
 * same PostgreSQL. More replicas means more readers in the window, so the
 * probability scales with the thing you do to make the service faster.
 *
 * <p>{@code AFTER_COMMIT} is the fix, and it is the only one that moves the
 * delete to a point where the new value is actually readable.
 *
 * <h2>The mirror of Day-11</h2>
 *
 * <p>Day-11 needed {@code REQUIRES_NEW} so an idempotency record would
 * <b>survive</b> a rollback — a write that had to escape the transaction's
 * fate. This is the same mechanism pointed the other way: an eviction that
 * must not <b>precede</b> a commit. Both are the lesson that
 * {@code @Transactional} draws a boundary in time, and that a side effect
 * placed on the wrong side of it is silently wrong rather than broken.
 *
 * <h2>Why it is also the right answer for the retry</h2>
 *
 * <p>{@link UserService#updateProfile} retries up to three times after an
 * optimistic-locking conflict. A {@code @CacheEvict} on the attempt method
 * would fire on <em>every</em> attempt, including the two that rolled back and
 * changed nothing — three deletions for one change, two of them evicting a
 * perfectly valid entry and causing a needless query. Because this listener is
 * bound to the commit, a losing attempt publishes nothing: its event is
 * discarded with its transaction. <b>One commit, one eviction.</b>
 *
 * <h2>What moves to a broker, and what does not</h2>
 *
 * <p>This class is the local half of invalidation and it stays local: the
 * {@code users} cache belongs to user-service, and the service that owns a
 * value is the one that can evict it the instant it changes. What Day-14 adds
 * is a <em>second</em> subscriber to the same {@link ProfileChangedEvent} —
 * one that publishes to a topic, so services holding their own copy of a
 * profile can invalidate theirs too.
 *
 * <p>Worth being clear about the asymmetry that creates. This eviction is
 * synchronous with the commit and effectively immediate. A broker-delivered
 * one is asynchronous and at-least-once, so a consumer's copy is stale for
 * however long delivery takes and the consumer may be told twice. <b>An
 * eviction is idempotent, which is what makes at-least-once delivery an
 * acceptable answer</b> — deleting an already-deleted key is a no-op. That is
 * not a lucky accident; it is the second payoff of cache-aside deleting
 * rather than updating, and it is why a write-through cache would be far
 * harder to distribute.
 */
@Component
public class ProfileCacheInvalidator {

    private static final Logger log =
            LoggerFactory.getLogger(ProfileCacheInvalidator.class);

    /**
     * The cache manager rather than a {@code @CacheEvict} annotation, because
     * an annotation on this method would be evaluated against <em>this</em>
     * method's own (absent) transaction, and is not what reads clearly here
     * anyway: the deletion is the whole body, so stating it as code rather
     * than as metadata is honest about what happens.
     */
    private final CacheManager cacheManager;

    public ProfileCacheInvalidator(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    /**
     * Invalidates the cached profile for a committed change.
     *
     * <p><b>{@code AFTER_COMMIT} is the default phase</b> and is written out
     * anyway. The alternatives are not exotic mistakes: {@code BEFORE_COMMIT}
     * reintroduces exactly the race this class was created to close, and
     * {@code AFTER_COMPLETION} would also fire after a <em>rollback</em> —
     * evicting a valid entry because an update failed, which is harmless but
     * wasteful, and obscures the invariant. The phase is the entire point of
     * the class, so it is spelled out rather than inherited.
     *
     * <p><b>A listener in this phase cannot fail usefully, and that must be
     * accepted rather than worked around.</b> The transaction is over; there
     * is nothing left to roll back. If this throws, the profile is updated in
     * PostgreSQL and stale in Redis, and the caller has already been told the
     * update succeeded — which is true. So the exception is caught here: an
     * uncaught one would propagate into the commit-completion machinery and
     * turn a successful update into an error response for a write that already
     * happened, which is the worse of the two wrong answers.
     *
     * <p>The stale entry then expires on its TTL. That is the backstop earning
     * its place: this is the second of the two routes to a missed eviction
     * (the first being an unreachable Redis, handled in {@code CacheConfig}),
     * and in both the bound on how wrong an answer may be is the TTL and
     * nothing else.
     *
     * <p><b>What this class does not solve.</b> A read that misses between the
     * commit and this eviction still queries PostgreSQL, gets the NEW value,
     * and caches it — correct. But a read that missed <em>just before</em> the
     * commit may still be in flight and write its old value into the cache
     * after this delete runs. {@code AFTER_COMMIT} narrows the window from
     * "the whole transaction" to "the scheduling gap", which is the difference
     * between a bug that happens and one that needs a pathological
     * interleaving — it does not close it. Closing it entirely needs delayed
     * double deletion, versioned keys, or invalidation driven off the
     * replication log, and all three cost more than a ten-minute TTL is worth
     * on a profile. <b>The honest statement of what this endpoint promises is
     * "recent", not "exact".</b>
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onProfileChanged(ProfileChangedEvent event) {
        try {
            var cache = cacheManager.getCache(UserService.USERS_CACHE);
            if (cache != null) {
                cache.evict(event.email());
            }
        } catch (RuntimeException failure) {
            log.error("Post-commit eviction failed for {} — the cached profile is "
                    + "stale and will be served until its TTL expires",
                    event.email(), failure);
        }
    }
}

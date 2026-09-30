package com.persona.user.config;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Tokens that have been logged out but have not yet expired. <b>Day-06.</b>
 *
 * <h2>Why this class has to exist at all</h2>
 *
 * <p>A JWT is valid because its signature verifies and its {@code exp} has
 * not passed — both facts are carried <em>inside the token</em>. There is no
 * server-side record of it, which is the whole point of statelessness and is
 * exactly what makes logout hard: deleting something that was never stored is
 * not possible. Without this class, {@code POST /auth/logout} could return
 * 200 while the token it "revoked" kept working until it expired. <b>An
 * endpoint that lies about what it did is worse than one that does not
 * exist.</b>
 *
 * <p>So logout is implemented as the only thing that actually works:
 * remembering which tokens are no longer accepted, and checking that list on
 * every request. Note what this costs — it reintroduces server-side state,
 * the precise thing the JWT was chosen to avoid. That is not a flaw in the
 * implementation; it is the real trade-off, and any design that appears to
 * avoid it is either not really revoking or is shortening {@code exp} until
 * the window is small enough to ignore.
 *
 * <h2>What is deliberately wrong with this implementation</h2>
 *
 * <p>It is an in-memory map, so it is lost on restart (every logged-out token
 * becomes valid again) and it is per-instance (a second application instance
 * does not know about it). Both are acceptable here and neither would be in
 * production, where this belongs in Redis — one shared store, with per-key
 * expiry doing the eviction below for free. Recorded as dated debt rather
 * than hidden: the correct shape is the same, only the storage differs.
 */
@Component
public class TokenDenyList {

    /**
     * Token to the moment it would have expired anyway.
     *
     * <p>Storing the expiry — rather than just the token — is what keeps this
     * map from growing forever. A denied token only needs to be remembered
     * until its own {@code exp} passes, because after that the signature check
     * rejects it regardless. Without the eviction below, a long-running
     * server accumulates every token ever logged out, and a map that only
     * grows is a memory leak with extra steps.
     *
     * <p>{@link ConcurrentHashMap} rather than a plain {@code HashMap}: this
     * bean is a singleton shared by every concurrent request, and a plain
     * HashMap resized by two threads at once can corrupt into an infinite
     * loop on read. Same reasoning as {@code UserService}'s fields being
     * final — a shared object needs to be safe for the sharing it will get.
     */
    private final Map<String, Instant> denied = new ConcurrentHashMap<>();

    /** Marks a token as no longer acceptable, until {@code expiresAt}. */
    public void deny(String token, Instant expiresAt) {
        evictExpired();
        denied.put(token, expiresAt);
    }

    public boolean isDenied(String token) {
        Instant expiry = denied.get(token);
        if (expiry == null) {
            return false;
        }
        // Already past its own expiry: the signature check would reject it
        // anyway, so stop tracking it and answer honestly.
        if (expiry.isBefore(Instant.now())) {
            denied.remove(token);
            return false;
        }
        return true;
    }

    /**
     * Drops entries whose tokens have expired on their own.
     *
     * <p>Called on write rather than on a schedule, which keeps this class
     * free of a {@code @Scheduled} dependency at the cost of the map only
     * shrinking when somebody logs out. Fine for the volume persona will ever
     * see; a real deployment gets this for free from Redis key expiry and
     * would not implement it here at all.
     */
    private void evictExpired() {
        Instant now = Instant.now();
        denied.entrySet().removeIf(entry -> entry.getValue().isBefore(now));
    }
}

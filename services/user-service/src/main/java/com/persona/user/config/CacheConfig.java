package com.persona.user.config;

import com.persona.user.dto.UserResponse;
import com.persona.user.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.SimpleCacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Turns Spring's caching abstraction on, and fixes the defaults that are
 * wrong for user-service. <b>Day-12.</b>
 *
 * <h2>Why this class has to exist at all</h2>
 *
 * <p>{@code @EnableCaching} is the first of Day-12's silent failures.
 * Without it, every {@code @Cacheable} and {@code @CacheEvict} in this
 * service is <b>inert metadata</b>: the annotations compile, the application
 * starts, the endpoints answer correctly, every test passes, and nothing is
 * ever cached. No warning is logged, because from Spring's point of view
 * nothing has gone wrong — it was never asked to look.
 *
 * <p>That is this project's recurring failure mode — present but not
 * participating — and it is the reason {@code UserCacheIT} asserts on <b>a
 * query count</b> rather than on a returned value: a cache that is switched
 * off returns exactly the right answer. Only counting the trips to PostgreSQL
 * distinguishes a cache that works from a cache that is not there.
 *
 * <h2>Why Redis, and why the argument is stronger here than in the monolith</h2>
 *
 * <p>{@code spring-boot-starter-cache} alone would give a
 * {@code ConcurrentHashMap} cache manager — faster than Redis on every single
 * operation, since a heap lookup beats a network round trip and always will.
 * It is still wrong, and in this architecture it is wrong <em>today</em>
 * rather than eventually:
 *
 * <pre>
 *   in-process cache, user-service scaled to two replicas
 *     replica A  [ ada -> {name: "Ada"} ]
 *     replica B  [ ada -> {name: "Ada"} ]      two copies
 *
 *   PUT /users/ada  routed by the gateway to replica A
 *     A writes PostgreSQL, A evicts ITS OWN entry
 *     B still holds {name: "Ada"}              and nobody told it
 *
 *   the user refreshes, and the answer ALTERNATES
 *   depending on which replica the gateway picked
 * </pre>
 *
 * <p>The monolith's copy of this file makes the same argument as a prediction
 * about Day-17. Here it is a description. <b>A service is a unit of scaling,
 * and the first thing anyone does to a slow service is run more of it</b> —
 * so an in-process cache in a microservice is a bug with a deployment-shaped
 * trigger, waiting for a {@code replicas: 2} that nobody thinks of as a code
 * change. It does not reproduce on one replica, it is intermittent in
 * production, and it looks like a bug in the update, which is the one place
 * it is not.
 *
 * <p>Redis removes it by removing the second copy. One shared cache, so an
 * eviction is an eviction for every replica, and scaling out stays a
 * deployment decision rather than a correctness one.
 *
 * <h2>What this class deliberately does NOT do</h2>
 *
 * <p>It caches only data this service <b>owns</b>. That constraint is the
 * whole of Day-12's distributed-caching lesson, and it costs nothing today
 * because user-service is the only service: everything in the {@code users}
 * cache came out of its own PostgreSQL.
 *
 * <p>The rule matters the moment a second service caches a profile it fetched
 * <em>from</em> user-service. Then this service holds the authoritative copy
 * and someone else holds a stale one, and <b>user-service cannot invalidate a
 * cache it does not know exists</b> — there is no eviction to write, because
 * the key is in another process's namespace under another service's
 * conventions. The three available answers, in rising order of coupling: let
 * the consumer's TTL bound the staleness; <b>publish an event on change</b>,
 * which is the standard answer and exactly what Day-14's Kafka work builds;
 * or have the consumer read through this shared cache rather than keeping its
 * own. Note the middle option is {@link com.persona.user.service.ProfileCacheInvalidator}
 * with the listener moved into another process — the mechanism is already
 * here, and only its address changes.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

    /**
     * Replaces the value serializer with typed JSON, and keeps the string key
     * serializer because it is already right.
     *
     * <h2>The default is JDK serialization, and it is a trap in three ways</h2>
     *
     * <ul>
     *   <li><b>It requires {@code Serializable}.</b> {@link UserResponse} is a
     *       record that has never needed to be, and adding an interface to a
     *       DTO to satisfy a caching library is the library dictating the
     *       model. The failure is also at <em>runtime</em>, on the first cache
     *       write, not at compile time.</li>
     *   <li><b>The bytes are opaque.</b> {@code GET user-service:users::ada@example.com}
     *       in {@code redis-cli} returns binary noise, so the one debugging
     *       move a cache makes available — look at what is actually stored —
     *       is unavailable exactly when it is needed. In a system of services
     *       that is worse than an inconvenience: a cache is often the only
     *       place to see what one service believes about another's data.</li>
     *   <li><b>It is a deserialization attack surface.</b> Java
     *       deserialization constructs arbitrary types from bytes, so anything
     *       that can write to Redis can potentially execute code in this JVM.
     *       "Only we can write to Redis" is doing a lot of work in that
     *       sentence when the instance is shared and has no password.</li>
     * </ul>
     *
     * <h2>A TYPED serializer, and why the generic one could not work</h2>
     *
     * <p>{@code JacksonJsonRedisSerializer(mapper, UserResponse.class)} is
     * bound to one type. The obvious choice was
     * {@code GenericJacksonJsonRedisSerializer}, which handles any type — and
     * in the monolith it produced {@code ClassCastException: LinkedHashMap
     * cannot be cast to UserResponse} on every cache <em>hit</em>. Writes were
     * fine; only reads failed.
     *
     * <p>The cause is in the class itself: {@code RedisCache} calls
     * {@code deserialize(byte[])}, which delegates to
     * {@code deserialize(bytes, Object.class)}, and the generic serializer's
     * own assertion message reads <em>"Please provide Object.class to make use
     * of Jackson3 default typing"</em>. With no type hint in the document and
     * {@code Object} as the target, Jackson does the only thing it can and
     * builds a {@code Map}.
     *
     * <p>So the generic serializer needs <b>default typing</b> — a
     * {@code @class} property naming the Java class to construct — which was
     * rejected because it is the deserialization surface above in a second
     * form: a stored {@code @class} is an instruction to this JVM about what
     * type to instantiate. It would also prevent the cache from using the
     * application's own {@code ObjectMapper}, since enabling default typing
     * there would put {@code @class} into HTTP responses.
     *
     * <p>Binding to the concrete type removes the question: the target comes
     * from configuration rather than from the data, so the document stays
     * plain JSON that instructs nothing. <b>And a plain JSON document is the
     * right thing to leave in a shared cache for a second reason</b> — a
     * {@code @class} of {@code com.persona.user.dto.UserResponse} is a Java
     * class name in a store other services can read, which couples them to
     * this service's package layout. A cached value should be data, not a
     * Java type.
     *
     * <h2>Jackson 3, not Jackson 2 — the missing "2" in the class name</h2>
     *
     * <p>Spring Data Redis 4.1.1 ships each serializer <b>twice</b>, under
     * names differing by one character:
     *
     * <pre>
     *   Jackson2JsonRedisSerializer   /  GenericJackson2JsonRedisSerializer
     *       bind com.fasterxml.jackson   (2.x)
     *   JacksonJsonRedisSerializer    /  GenericJacksonJsonRedisSerializer
     *       bind tools.jackson           (3.x)
     * </pre>
     *
     * <p>This project is on Jackson 3 — {@code tools.jackson 3.1.5}, via
     * {@code spring-boot-starter-jackson} — so the unnumbered names are
     * correct and the "2" names belong to a different library that happens to
     * be present. Verified by decompiling the class and reading which packages
     * it references, not by the name.
     *
     * <p><b>The "2" version compiles.</b> It compiles because
     * {@code jackson-databind 2.21.5} is on the classpath at {@code runtime}
     * scope as a transitive of {@code jjwt-jackson} — a JWT library dragging
     * in a JSON library that is then available to bind against by accident.
     * In the monolith the failure arrived as six
     * {@code SerializationException}s naming {@code java.time.Instant} and
     * asking for {@code jackson-datatype-jsr310}; adding that module would
     * have "fixed" it by committing the cache to Jackson 2 while the rest of
     * the service uses Jackson 3. Day-11 lost time to the same transitive in
     * {@code UserController}. <b>On this classpath a Jackson 2 import always
     * compiles and is always wrong.</b>
     *
     * <h2>A customizer, NOT a {@code RedisCacheConfiguration} bean</h2>
     *
     * <p>Declaring {@code @Bean RedisCacheConfiguration} silently discards
     * every {@code spring.cache.redis.*} property. Confirmed by decompiling
     * Boot's own {@code RedisCacheConfiguration} rather than by reasoning
     * about it, because reasoning is what produced the bug:
     *
     * <pre>
     *   ObjectProvider&lt;RedisCacheConfiguration&gt;.getIfAvailable(
     *       () -&gt; createConfiguration(properties, classLoader))
     * </pre>
     *
     * <p>The property-reading code is the <b>fallback supplier</b>. Supply the
     * bean and it never runs, so a custom configuration and the properties are
     * mutually exclusive rather than additive — and the half that loses is
     * silent. In the monolith this showed up as keys written without their
     * prefix; the TTL and null-value handling were being dropped by the same
     * mechanism, with nothing to say so.
     *
     * <p>A {@link RedisCacheManagerBuilderCustomizer} composes instead: it
     * runs <em>after</em> the builder has been populated from the properties,
     * so the TTL, prefix and null handling are in place and this replaces only
     * the serializers.
     */
    @Bean
    public RedisCacheManagerBuilderCustomizer cacheSerializerCustomizer(
            tools.jackson.databind.ObjectMapper objectMapper) {
        // The ObjectMapper is a REQUIRED constructor argument on the Jackson 3
        // serializer — there is no no-arg version — and that is an improvement
        // rather than an inconvenience. Injecting the application's own
        // mapper, the same bean UserController uses to render idempotency
        // replays, means the cache and the HTTP layer serialise by one set of
        // rules. A private mapper built here would drift silently: a date
        // format configured for the API would not apply to cached documents,
        // so a value would round-trip through Redis and come back subtly
        // different from the one the endpoint returned.
        return builder -> builder.withCacheConfiguration(
                UserService.USERS_CACHE,
                builder.cacheDefaults()
                        .serializeKeysWith(RedisSerializationContext.SerializationPair
                                .fromSerializer(new StringRedisSerializer()))
                        .serializeValuesWith(RedisSerializationContext.SerializationPair
                                .fromSerializer(
                                        new org.springframework.data.redis.serializer
                                                .JacksonJsonRedisSerializer<>(
                                                        objectMapper,
                                                        UserResponse.class))));
        // withCacheConfiguration rather than cacheDefaults(...), because the
        // serializer above is bound to one type and so is only valid for the
        // one cache that holds it. A second cache holding something else needs
        // its own entry — a real limit, and the right one: a cache whose value
        // type is written down cannot silently start storing something else.
        //
        // Note cacheDefaults() is overloaded as both the getter (no args) and
        // the setter (one arg); this reads the property-derived defaults and
        // builds on them. Starting from RedisCacheConfiguration
        // .defaultCacheConfig() here would discard the TTL and the prefix —
        // reintroducing the exact bug the javadoc above describes, in a new
        // place.
    }

    /**
     * Makes a Redis failure degrade this service instead of breaking it.
     *
     * <h2>The default turns a cache outage into a service outage</h2>
     *
     * <p>{@link SimpleCacheErrorHandler} rethrows, so if Redis is unreachable
     * the exception propagates out of the cache interceptor, through
     * {@code UserService}, into the controller, and the client gets a 500. The
     * data was in PostgreSQL the whole time, the database was healthy, and
     * user-service failed because an <em>optimisation</em> was unavailable.
     *
     * <p>That is strictly worse than having no cache at all, and it is the
     * direct consequence of the rule this class opened with: if deleting the
     * entire Redis instance must make this service slow and never wrong, then
     * a Redis that is merely unreachable cannot be allowed to produce a wrong
     * answer either — and a 500 for a user who exists is a wrong answer.
     *
     * <p><b>In this architecture the stakes are higher than a bad response.</b>
     * A 500 from user-service is not the end of the story: whatever called it
     * now has a failed dependency, and if that caller treats the failure as
     * its own it returns 500 too. One unreachable cache becomes a cascading
     * failure across services that never touched Redis — the classic
     * distributed-systems amplification, caused here by a library default. Log
     * and continue: a miss that cannot be served from cache is a miss, and a
     * miss already has a correct code path.
     *
     * <h2>The one case that is not harmless</h2>
     *
     * <p>Swallowing an <b>eviction</b> failure is not the same as swallowing a
     * read failure. A failed {@code get} costs a query. A failed
     * {@code evict} leaves a stale value in a cache that is now reachable
     * again, with no further write coming to clear it — so it is served until
     * the TTL expires.
     *
     * <p>Which is the job the TTL in {@code application.properties} exists to
     * do, and why that number is ten minutes rather than a day. The failure
     * cannot be prevented from here, so it is made self-correcting and
     * bounded — the same shape as Day-11's answer to a duplicate request: do
     * not try to make the bad event impossible, make its consequence expire.
     * Logging at {@code error} is what keeps it from being invisible.
     */
    @Bean
    public CacheErrorHandler cacheErrorHandler() {
        return new SimpleCacheErrorHandler() {

            @Override
            public void handleCacheGetError(RuntimeException exception,
                                            Cache cache, Object key) {
                // The cheap one: this degrades to a miss, and a miss is a code
                // path that already works.
                log.warn("Cache read failed, falling through to the database. "
                        + "cache={} key={}", cache.getName(), key, exception);
            }

            @Override
            public void handleCachePutError(RuntimeException exception,
                                            Cache cache, Object key, Object value) {
                // Also cheap. The value is not cached, so the next read is
                // another miss. Nothing is wrong, only slow.
                log.warn("Cache write failed, the value will not be cached. "
                        + "cache={} key={}", cache.getName(), key, exception);
            }

            @Override
            public void handleCacheEvictError(RuntimeException exception,
                                              Cache cache, Object key) {
                // THE EXPENSIVE ONE, at a higher level for that reason: a
                // stale entry now survives until the TTL, and this log line is
                // the only record of why a user saw an old profile.
                log.error("EVICTION FAILED — a stale entry may be served for up "
                        + "to the configured TTL. cache={} key={}",
                        cache.getName(), key, exception);
            }

            @Override
            public void handleCacheClearError(RuntimeException exception, Cache cache) {
                log.error("Cache clear failed. cache={}", cache.getName(), exception);
            }
        };
    }
}

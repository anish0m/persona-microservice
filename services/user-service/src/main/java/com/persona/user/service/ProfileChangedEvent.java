package com.persona.user.service;

/**
 * Published when a user's stored profile has changed, so the cache entry for
 * it can be invalidated <em>after</em> the change is committed. <b>Day-12.</b>
 *
 * <p>A record carrying one field, which looks like ceremony around a
 * {@code String} and is not. The type is what lets
 * {@link ProfileCacheInvalidator} subscribe to <em>this</em> fact rather than
 * to "something happened", and what makes the publisher's intent reviewable:
 * {@code publishEvent(new ProfileChangedEvent(email))} says what changed,
 * where {@code publishEvent(email)} says only that an email was involved.
 *
 * <p><b>It carries the email and nothing else — deliberately not the
 * {@code User}.</b> The listener's job is to delete a key, and the email is
 * the key. Putting the entity in the event would invite a listener to read
 * from it, and by the time an {@code AFTER_COMMIT} listener runs the
 * transaction is over: the persistence context is closed, the object is
 * detached, and touching a lazy association throws
 * {@code LazyInitializationException} — in a listener, where the stack trace
 * points nowhere near the code that caused it. An event that cannot carry
 * stale state cannot be misread.
 *
 * <h2>Why this is a Spring event and not yet a Kafka message</h2>
 *
 * <p>Worth being explicit in this architecture, because the two look alike
 * and are not. This is an <b>in-process</b> event: published and consumed
 * inside one JVM, delivered synchronously after the commit, and gone. It
 * crosses no network, needs no broker, and nothing outside user-service can
 * subscribe to it.
 *
 * <p>Which is correct for what it does today — invalidate a cache entry that
 * this service owns. The moment a <em>second</em> service caches a profile it
 * fetched from here, that service needs to hear about this change too, and an
 * in-process event cannot reach it. That is the point at which this type
 * becomes a published message with a schema, a topic and at-least-once
 * delivery, and {@link ProfileCacheInvalidator} becomes one subscriber among
 * several. Day-14 does that work.
 *
 * <p>The shape is deliberately already right for it: a small immutable fact
 * naming what changed, with no object graph and no behaviour. <b>An event
 * that would survive being serialised is one that can later leave the
 * process.</b>
 *
 * @param email the user whose cached profile is now out of date
 */
public record ProfileChangedEvent(String email) {
}

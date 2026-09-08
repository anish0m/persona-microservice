/**
 * <h2>Business logic for the user domain only.</h2>
 *
 * "Email must be unique", "username is derived from the name". Identical rules to
 * the monolith's {@code com.persona.service}.
 *
 * <p>What changes here later: when logic needs data this service does not own, the
 * monolith would inject another service and call a method - fast, in-process, and
 * inside one transaction. Here that becomes a network call, which can be slow, can
 * fail, and cannot be rolled back by {@code @Transactional}. That single difference
 * is the source of most of Days 10-15 (idempotency, retries, eventual consistency).
 */
package com.persona.user.service;

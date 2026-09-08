/**
 * <h2>Persistence - and, later, a database this service owns exclusively.</h2>
 *
 * In-memory {@code Map} for now (slice 4), PostgreSQL from Day-04.
 *
 * <p>The microservice-specific rule is <b>database-per-service</b>: when Postgres
 * arrives, user-service gets its own schema and no other service is given the
 * credentials. Cross-service data is fetched via API, never via a shared JOIN.
 *
 * <p>The monolith has the opposite freedom - one database, and a report query can
 * JOIN users to wallets in a single statement. Simpler, until the day two teams
 * want to change the same table.
 */
package com.persona.user.repository;

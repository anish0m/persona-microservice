package com.persona.user.repository;

import com.persona.user.exception.DuplicateEmailException;
import com.persona.user.exception.UserNotFoundException;
import com.persona.user.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The SQL, executed against a real PostgreSQL.
 *
 * <p><b>Why this file has to exist.</b> Every other test in this project uses the
 * in-memory repository, which means not one of them executes a single line of
 * {@link JdbcUserRepository}. A typo in a column name, a wrong placeholder count,
 * a mapping that reads {@code first_name} into {@code lastName} — all of it would
 * sail through a fully green suite and fail on the first real request.
 *
 * <p>That is the Day-02 lesson generalised: <em>a green test that does not execute
 * the thing it is named after proves nothing.</em> Mocks test the code you wrote
 * around the database. Only a database tests the SQL.
 *
 * <p><b>IT, not Test.</b> The name matters mechanically: Maven's Surefire plugin
 * runs {@code *Test} during {@code mvn test}, so an ordinary build stays fast and
 * needs nothing installed. This class is also guarded by
 * {@code @EnabledIfSystemProperty}, so it is skipped unless explicitly asked for:
 *
 * <pre>
 *     docker compose up -d persona-monolith-db
 *     mvn test -Dit=true
 * </pre>
 *
 * <p>Day-19 replaces the guard with Testcontainers, which starts a throwaway
 * Postgres from inside the test itself — no manual step and no shared state. It is
 * deliberately not used today: it would add a library and a concept on a day that
 * already has Flyway, JDBC and SQL in it.
 */
@SpringBootTest
/*
 * DAY-04 CHANGE — two profiles now, where one used to be enough.
 *
 * This said @ActiveProfiles("jdbc-it") while JdbcUserRepository was
 * @Profile("!test"): the bean matched because "jdbc-it" is not "test", which was
 * true by accident rather than by intent. Now that the implementation names
 * itself positively as "jdbc", the profile that SELECTS the bean and the profile
 * that CONFIGURES the datasource are two different things and both must be named.
 *
 *     jdbc     -> selects JdbcUserRepository           (the bean)
 *     jdbc-it  -> loads application-jdbc-it.properties (the database)
 *
 * The old form worked as long as nobody looked at it. That is the recurring
 * hazard of negations: they are satisfied by everything not yet invented.
 */
@ActiveProfiles({"jdbc", "jdbc-it"})
@EnabledIfSystemProperty(named = "it", matches = "true")
class JdbcUserRepositoryIT {

    @Autowired
    private UserRepository repository;

    @Autowired
    private JdbcClient jdbc;

    /**
     * Each test starts from an empty table.
     *
     * <p>Tests that share state pass or fail depending on the order they run in,
     * which is the most expensive kind of flaky: it looks like a real bug and
     * reproduces only sometimes. Note this deletes rather than dropping — Flyway
     * created the schema once at startup, and recreating it per test would be slow
     * and would stop testing the migration that actually ships.
     */
    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM users").update();
    }

    private static User anishom() {
        return new User("khi0ne@example.com", "Anishom", "Frost", "Pass1234#");
    }

    /**
     * The whole point of {@code RETURNING}: the object that comes back carries
     * values that did not exist in the object that went in.
     */
    @Test
    void saveAssignsAnIdAndTimestampFromTheDatabase() {
        User toSave = anishom();
        assertNull(toSave.getId(), "unsaved user must have no id");

        User saved = repository.save(toSave);

        assertNotNull(saved.getId(), "the database assigns the id");
        assertNotNull(saved.getCreatedAt(), "created_at DEFAULT now() fills this");
        assertEquals("khi0ne@example.com", saved.getEmail());
    }

    /**
     * Proves the UNIQUE constraint is real and that the driver's failure is
     * translated into this application's own exception type.
     *
     * <p>This is the guarantee the service's {@code if} could only approximate. The
     * service check is not even reached here — the repository is called directly.
     */
    @Test
    void aDuplicateEmailIsRejectedByTheDatabase() {
        repository.save(anishom());

        DuplicateEmailException e =
                assertThrows(DuplicateEmailException.class, () -> repository.save(anishom()));

        assertEquals("khi0ne@example.com", e.getEmail());
        assertEquals(1, repository.count(), "the second insert must not have landed");
    }

    /**
     * Round-trips every column, which is what catches a mapRow that reads the right
     * types from the wrong columns — an error the compiler cannot see, because
     * {@code first_name} and {@code last_name} are both Strings.
     *
     * <p>Third time this hazard has appeared in three days: the compiler checks
     * types, not meanings.
     */
    @Test
    void everyFieldSurvivesTheRoundTrip() {
        repository.save(anishom());

        User found = repository.getByEmail("khi0ne@example.com");

        assertEquals("Anishom", found.getFirstName());
        assertEquals("Frost", found.getLastName());
        assertEquals("Pass1234#", found.getPassword());
        assertEquals("@anishom-frost", found.getUsername());
        assertTrue(found.getImage().isEmpty(), "image was never set, so the column is NULL");
    }

    /** Empty is not an error — the SQL counterpart of {@code 200 []}. */
    @Test
    void findByEmailReturnsEmptyForAnUnknownEmail() {
        assertEquals(Optional.empty(), repository.findByEmail("nobody@example.com"));
    }

    @Test
    void getByEmailThrowsForAnUnknownEmail() {
        assertThrows(UserNotFoundException.class, () -> repository.getByEmail("nobody@example.com"));
    }

    @Test
    void deleteRemovesTheRow() {
        repository.save(anishom());
        repository.deleteByEmail("khi0ne@example.com");

        assertEquals(0, repository.count());
        assertTrue(repository.findByEmail("khi0ne@example.com").isEmpty());
    }

    /**
     * Deleting nothing throws rather than succeeding quietly — the repository
     * reports what happened, and the controller turns it into a 404.
     */
    @Test
    void deletingAMissingUserThrows() {
        assertThrows(UserNotFoundException.class,
                () -> repository.deleteByEmail("nobody@example.com"));
    }

    /**
     * THE TEST THAT JUSTIFIES THE WHOLE DAY.
     *
     * <p>Data written by one connection is found by another, because it is in
     * PostgreSQL rather than in this JVM's heap. The ConcurrentHashMap could never
     * have passed this, and the browser showing {@code []} after a restart was the
     * same fact in a different costume.
     */
    @Test
    void dataIsVisibleToAQueryThisRepositoryDidNotMake() {
        repository.save(anishom());

        Long id = jdbc.sql("SELECT id FROM users WHERE email = ?")
                .param("khi0ne@example.com")
                .query(Long.class)
                .single();

        assertNotNull(id);
    }

    /**
     * Flyway ran, and ran exactly once.
     *
     * <p>{@code flyway_schema_history} is how Flyway knows what has already been
     * applied. Restarting the application does not re-run V1 — it reads this table,
     * sees version 1 is present, and does nothing.
     */
    @Test
    void flywayRecordedTheMigration() {
        Integer applied = jdbc.sql(
                        "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success = true")
                .query(Integer.class)
                .single();

        assertEquals(1, applied);
        assertFalse(applied > 1, "a migration must never be applied twice");
    }
}

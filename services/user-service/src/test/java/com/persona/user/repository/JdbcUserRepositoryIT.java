package com.persona.user.repository;

import com.persona.user.exception.DuplicateEmailException;
import com.persona.user.exception.UserNotFoundException;
import com.persona.user.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Optional;
import java.util.Set;

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
        // The repository stores whatever hash it is handed and does not hash
        // anything itself — hashing is the service's job (Day-05).
        assertEquals("Pass1234#", found.getPasswordHash());
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

    // =================================================================
    //  Day-05 — update(), which in THIS implementation is a real UPDATE.
    // =================================================================

    /**
     * The edit reaches the database.
     *
     * <p>Trivial-looking, and it is the test that distinguishes the two
     * implementations. Under JPA, {@code update} does nothing at all and the
     * write happens through dirty checking. Here there is no persistence
     * context, so without the UPDATE statement this assertion fails — the Java
     * object would change and the row would not.
     *
     * <p>The row is read back with raw SQL rather than through the repository,
     * for the Day-04 reason: asking the component under test whether it worked
     * lets a cache answer on the database's behalf.
     */
    @Test
    void updateWritesTheChangeToTheRow() {
        repository.save(anishom());

        User stored = repository.getByEmail("khi0ne@example.com");
        stored.setFirstName("Turhan");
        stored.setLastName("Winter");
        repository.update(stored);

        String firstName = jdbc.sql("SELECT first_name FROM users WHERE email = ?")
                .param("khi0ne@example.com")
                .query(String.class)
                .single();

        assertEquals("Turhan", firstName);
    }

    /**
     * update() must not touch the password column.
     *
     * <p>The UPDATE statement names three columns and password_hash is not one
     * of them. Asserted rather than trusted, because the failure — a profile
     * edit quietly resetting a password — produces no error and would be found
     * by a locked-out user rather than by a test.
     */
    @Test
    void updateDoesNotTouchThePasswordHash() {
        repository.save(anishom());
        String before = jdbc.sql("SELECT password_hash FROM users WHERE email = ?")
                .param("khi0ne@example.com").query(String.class).single();

        User stored = repository.getByEmail("khi0ne@example.com");
        stored.setFirstName("Turhan");
        repository.update(stored);

        String after = jdbc.sql("SELECT password_hash FROM users WHERE email = ?")
                .param("khi0ne@example.com").query(String.class).single();

        assertEquals(before, after);
    }

    /**
     * Updating a row that is not there throws rather than succeeding quietly.
     *
     * <p>An UPDATE matching zero rows is a perfectly successful statement in
     * SQL. Without the row-count check in the implementation, this method would
     * return normally and the caller would believe the edit landed.
     */
    @Test
    void updatingAMissingUserThrows() {
        User ghost = new User("nobody@example.com", "No", "Body", "hash");

        assertThrows(UserNotFoundException.class, () -> repository.update(ghost));
    }

    /**
     * Both migrations ran, in order.
     *
     * <p>Widened from the original Day-03 assertion, which only checked V1.
     * V2 renamed the password column, and a test that counts only the first
     * migration would still pass against a database that never received the
     * rename — the query below would then be selecting a column that does not
     * exist, which is precisely the failure worth catching.
     */
    /**
     * Every migration on disk ran, and every one of them succeeded.
     *
     * <p><b>Day-07 — this assertion used to hardcode 2</b> and failed today
     * with {@code expected: <2> but was: <3>}, because V3 added roles.
     *
     * <p>The test was right to notice and wrong in how it asked. A hardcoded
     * count must be hand-edited on every migration, which means it is a
     * maintenance tax eventually paid by bumping the number without thinking —
     * at which point it asserts nothing. The question worth asking is not "are
     * there three?" but <b>"did everything that exists apply, and did any of
     * it fail?"</b>, which stays true for V4 without an edit.
     *
     * <p>The {@code success = false} half earns its keep: Flyway records a
     * failed migration rather than deleting the row, so a schema can be
     * half-applied while the application starts anyway. Counting only
     * successes misses that entirely.
     *
     * <p>⚠️ <b>Each service owns its own schema and its own
     * {@code flyway_schema_history}</b>, so counts here are not comparable with
     * another service's — a second service starts again at V1. There is no
     * global "version N of persona", which also means the deployment order
     * between services is something humans reason about rather than something
     * Flyway can enforce.
     */
    @Test
    void everyMigrationAppliedSuccessfully() {
        Integer failed = jdbc.sql(
                        "SELECT COUNT(*) FROM flyway_schema_history WHERE success = false")
                .query(Integer.class)
                .single();

        assertEquals(0, failed, "a migration is recorded as FAILED in flyway_schema_history");

        Integer applied = jdbc.sql(
                        "SELECT COUNT(*) FROM flyway_schema_history WHERE success = true")
                .query(Integer.class)
                .single();

        // Lower bound, not an exact count. V1 creates users, V2 renames the
        // password column, V3 adds role — the three this suite's other
        // assertions actually depend on.
        assertTrue(applied >= 3,
                "expected at least V1-V3 to be applied, found " + applied);
    }

    // ---- Day-09: paging against real SQL --------------------------------

    private void saveUsers(int count) {
        for (int i = 0; i < count; i++) {
            repository.save(new User(
                    "user" + i + "@example.com", "First", "Last", "Pass1234#"));
        }
    }

    /**
     * {@code LIMIT} bounds the rows; the separate {@code COUNT(*)} describes the
     * table.
     *
     * <p>This is the assertion that fails if the count is ever taken from the
     * limited query instead of its own statement — {@code totalElements} would
     * come back as 2 and {@code getTotalPages()} would report 1, with nothing
     * throwing.
     */
    @Test
    void aPageIsLimitedButTheTotalDescribesTheWholeTable() {
        saveUsers(5);

        Page<User> page = repository.findAll(PageRequest.of(0, 2));

        assertEquals(2, page.getContent().size());
        assertEquals(5, page.getTotalElements());
        assertEquals(3, page.getTotalPages());
    }

    /**
     * {@code OFFSET} skips, and the ordering makes "skip" mean something.
     *
     * <p>Ordered by {@code id}, which the database assigns in insertion order,
     * so page 1 holds the third and fourth users saved. Without the
     * {@code ORDER BY} this assertion would be undefined behaviour that happens
     * to pass on small tables — which is exactly how this bug survives into
     * production.
     */
    @Test
    void offsetSkipsEarlierRowsInIdOrder() {
        saveUsers(5);

        List<String> secondPage = repository.findAll(PageRequest.of(1, 2))
                .getContent().stream().map(User::getEmail).toList();

        assertEquals(List.of("user2@example.com", "user3@example.com"), secondPage);
    }

    /**
     * <b>The {@code ORDER BY} is load-bearing, and this is the test that proves
     * it.</b>
     *
     * <p>Written because deleting {@code ORDER BY id} from the paged query left
     * every other test in this class green. Freshly inserted rows sit in the heap
     * in insertion order, so an unordered {@code LIMIT}/{@code OFFSET} returns
     * them in id order by luck — the assertions passed while asserting nothing.
     * That is precisely the bug reaching production: correct on the developer's
     * five rows, wrong once the table has seen updates.
     *
     * <p>The setup breaks the coincidence. An {@code UPDATE} in PostgreSQL does
     * not edit in place — MVCC writes a new row version and leaves the old one
     * dead, so the updated row moves to the <em>end</em> of the heap. Updating
     * the first user therefore makes physical order and id order disagree, and
     * only a query that really sorts can still answer correctly.
     */
    @Test
    void pagingStillOrdersByIdAfterAnUpdateMovesARowInTheHeap() {
        saveUsers(5);

        // Rewrites user0's row version and relocates it to the end of the heap.
        User first = repository.getByEmail("user0@example.com");
        first.setFirstName("Moved");
        repository.update(first);

        List<String> firstPage = repository.findAll(PageRequest.of(0, 2))
                .getContent().stream().map(User::getEmail).toList();

        // Without ORDER BY, user0 now comes back last and this page would be
        // user1 + user2.
        assertEquals(List.of("user0@example.com", "user1@example.com"), firstPage);
    }

    @Test
    void everyRowIsSeenExactlyOnceAcrossAllPages() {
        saveUsers(5);

        List<String> seen = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            repository.findAll(PageRequest.of(i, 2))
                    .forEach(u -> seen.add(u.getEmail()));
        }

        assertEquals(5, seen.size());
        assertEquals(5, Set.copyOf(seen).size());
    }

    /** Past the end PostgreSQL returns no rows; the total still describes the table. */
    @Test
    void aPageBeyondTheEndIsEmpty() {
        saveUsers(3);

        Page<User> page = repository.findAll(PageRequest.of(99, 10));

        assertTrue(page.getContent().isEmpty());
        assertEquals(3, page.getTotalElements());
    }

    @Test
    void anEmptyTablePagesToAnEmptyPage() {
        Page<User> page = repository.findAll(PageRequest.of(0, 20));

        assertTrue(page.getContent().isEmpty());
        assertEquals(0, page.getTotalElements());
    }

    /**
     * Paging does not change what a row contains.
     *
     * <p>Worth asserting because the paged query is a <em>second</em> hand-written
     * SELECT in this class, with its own column list. A column dropped from it
     * would leave every other test passing and silently null out a field only on
     * the list endpoint.
     */
    @Test
    void aPagedRowCarriesEveryFieldJustAsFindByEmailDoes() {
        repository.save(anishom());

        User fromPage = repository.findAll(PageRequest.of(0, 10))
                .getContent().getFirst();

        assertEquals("khi0ne@example.com", fromPage.getEmail());
        assertEquals("Anishom", fromPage.getFirstName());
        assertEquals("Frost", fromPage.getLastName());
        assertEquals("USER", fromPage.getRole());
        assertNotNull(fromPage.getId());
        assertNotNull(fromPage.getCreatedAt());
    }
}

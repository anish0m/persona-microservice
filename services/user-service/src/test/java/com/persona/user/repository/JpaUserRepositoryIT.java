package com.persona.user.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.persona.user.exception.DuplicateEmailException;
import com.persona.user.exception.UserNotFoundException;
import com.persona.user.model.User;

/**
 * {@link JpaUserRepository} against a real PostgreSQL database. <b>Day-04.</b>
 *
 * <p>The deliberate twin of {@link JdbcUserRepositoryIT}: same database, same
 * Flyway migrations, same assertions about behaviour. Two implementations proved
 * interchangeable by being subjected to the same questions — which is what the
 * {@link UserRepository} interface claimed and what nothing had yet checked.
 *
 * <p><b>The most valuable assertion in this file is the one nobody wrote.</b>
 * With {@code ddl-auto: validate}, this context cannot start unless every
 * {@code @Column} claim on {@link User} is true of the real table: every name,
 * every length, every nullability. A wrong {@code length = 100} or a mistyped
 * column fails the whole suite at startup, naming the column. The test method
 * bodies test behaviour; the ACT OF STARTING tests the mapping.
 *
 * <p>That is why {@code create-drop} would be worse than useless here. It would
 * build the schema from the entities, so the entities would trivially match it
 * and V1 would never be consulted — a green suite proving nothing about what
 * ships. Same failure shape as Day-01's test that registered once and asserted
 * a duplicate was rejected.
 *
 * <p>{@code @Transactional} rolls each test back, as in the JDBC suite. One
 * addition worth naming: the rollback now also discards the PERSISTENCE CONTEXT,
 * so entities cannot leak between tests. It also means everything here runs
 * inside one transaction, which is exactly why {@link #findByEmailTwiceReturnsTheSameObject}
 * can demonstrate the first-level cache at all.
 */
@SpringBootTest
@ActiveProfiles({"jpa", "jpa-it"})
@Transactional
@EnabledIfSystemProperty(named = "it", matches = "true")
class JpaUserRepositoryIT {

    /**
     * The field type is the interface. This test asks for "whatever implements
     * {@link UserRepository}" and the profile decides which — so nothing in the
     * body below names an implementation, and the file would be nearly identical
     * for any of the three.
     */
    @Autowired
    private UserRepository repository;

    /**
     * Raw SQL alongside the ORM, on purpose. When the question is "what is
     * actually in the table?", asking Hibernate is asking the thing under test.
     */
    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private SpringDataUserRepository springData;

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM users").update();
    }

    private static User anishom() {
        return new User("khi0ne@example.com", "Anishom", "Frost", "Pass1234#");
    }

    /**
     * The database generates {@code id} and {@code created_at}, and {@code save}
     * hands them back — the JPA equivalent of Day-03's {@code RETURNING} clause.
     *
     * <p>Asserting null FIRST makes this a claim about the transition rather than
     * about the returned object. It is also what proves {@code IDENTITY} is
     * really in play: the id did not exist until the INSERT ran.
     */
    @Test
    void savingAssignsAnIdAndTimestamp() {
        User toSave = anishom();
        assertNull(toSave.getId(), "unsaved user must have no id");

        User saved = repository.save(toSave);

        assertNotNull(saved.getId());
        assertNotNull(saved.getCreatedAt());
    }

    /**
     * The round trip.
     *
     * <p>Fields are asserted individually, deliberately. {@code User.equals}
     * compares email only, so {@code assertEquals(user, found)} would pass even
     * if the mapping had swapped first name and last name — both are
     * {@code VARCHAR(100)}, and <b>the framework checks names, not meanings.</b>
     * This is the same reasoning as the JDBC suite, where five of {@code mapRow}'s
     * seven arguments were String. The mechanism changed; the hazard did not.
     */
    @Test
    void aSavedUserCanBeFoundByEmail() {
        repository.save(anishom());

        User found = repository.findByEmail("khi0ne@example.com").orElseThrow();

        assertEquals("Anishom", found.getFirstName());
        assertEquals("Frost", found.getLastName());
        assertEquals("khi0ne@example.com", found.getEmail());
    }

    /**
     * The UNIQUE constraint fires, and {@code save} translates it.
     *
     * <p>This test exists because the code it covers was WRONG on the first
     * attempt, and nothing else would have caught it. The original catch block
     * asked the database {@code existsByEmail(...)} to decide whether the
     * violation was a duplicate. That query triggered an auto-flush of the failed
     * entity, which still had a null id, and Hibernate threw
     * {@code AssertionFailure: Entry for instance of User has a null identifier}
     * — burying the real error under one from its own internals.
     *
     * <p>Two Day-03 lessons, arriving together: a failed statement poisons the
     * whole transaction so a SELECT cannot work, and catching an exception in
     * Java does not undo it in the database. The persistence context is unusable
     * afterwards too. The fix is to read the exception that was thrown rather
     * than ask the dead transaction a question.
     *
     * <p><b>Why there is no {@code count()} assertion here</b> — the same reason
     * as the JDBC suite: it would be refused with SQL state 25P02. The constraint
     * is what guarantees the second row did not land; the exception is the proof
     * it fired.
     */
    @Test
    void aDuplicateEmailIsRejected() {
        repository.save(anishom());

        DuplicateEmailException e = assertThrows(DuplicateEmailException.class,
                () -> repository.save(anishom()));

        assertEquals("khi0ne@example.com", e.getEmail());
    }

    /** Absence is not an error — the counterpart to {@code getByEmail} throwing. */
    @Test
    void anUnknownEmailIsEmpty() {
        assertTrue(repository.findByEmail("nobody@example.com").isEmpty());
    }

    /** And the counterpart itself: the caller who cannot continue without one. */
    @Test
    void getByEmailThrowsWhenMissing() {
        assertThrows(UserNotFoundException.class,
                () -> repository.getByEmail("nobody@example.com"));
    }

    /**
     * Proves the {@code rows == 0} branch. Without it {@code deleteByEmail} would
     * succeed at deleting nothing, and silence is not a pass.
     */
    @Test
    void deletingAMissingUserThrows() {
        assertThrows(UserNotFoundException.class,
                () -> repository.deleteByEmail("nobody@example.com"));
    }

    /**
     * <b>The persistence context, made visible.</b>
     *
     * <p>Two separate calls, and the same object comes back — {@code assertSame},
     * not {@code assertEquals}. The second lookup never reached the database:
     * within one transaction Hibernate keeps a map of every entity it has handed
     * out, so one row is one object, guaranteed by reference identity rather than
     * by {@code equals}.
     *
     * <p>This is the mechanism behind everything surprising about JPA, and it is
     * worth seeing proved rather than described. It is also why
     * {@code equals} on {@code email} still matters: ACROSS transactions these
     * would be different objects, and only {@code equals} could tell they are the
     * same person.
     */
    @Test
    void findByEmailTwiceReturnsTheSameObject() {
        repository.save(anishom());

        User first = repository.findByEmail("khi0ne@example.com").orElseThrow();
        User second = repository.findByEmail("khi0ne@example.com").orElseThrow();

        assertSame(first, second, "one row is one object inside a transaction");
    }

    /**
     * <b>Dirty checking: a setter is a write.</b>
     *
     * <p>No {@code save()} call appears after the setter, and the row changes
     * anyway. On flush, Hibernate compares the managed entity against the
     * snapshot taken when it was loaded, finds {@code first_name} differs, and
     * emits the UPDATE by itself.
     *
     * <p>{@code flush()} is called explicitly only to force that moment INSIDE the
     * test — otherwise it would happen at commit, which this test rolls back
     * instead. The raw SQL check afterwards is the point: the assertion asks the
     * DATABASE, not Hibernate, because asking Hibernate would just return the
     * object already in the persistence context and prove nothing.
     *
     * <p>The lesson is the uncomfortable half: inside a transaction, a managed
     * entity IS the row. A stray setter anywhere — a defensive normalisation, a
     * fixup in a helper — persists silently. "I only changed it in memory" is not
     * a thing that exists here.
     */
    @Test
    void mutatingAManagedEntityWritesWithoutSave() {
        repository.save(anishom());
        User managed = repository.findByEmail("khi0ne@example.com").orElseThrow();

        managed.setFirstName("Turhan");
        springData.flush();

        String stored = jdbc.sql("SELECT first_name FROM users WHERE email = ?")
                .param("khi0ne@example.com")
                .query(String.class)
                .single();

        assertEquals("Turhan", stored, "no save() was called, and the row changed");
    }
}

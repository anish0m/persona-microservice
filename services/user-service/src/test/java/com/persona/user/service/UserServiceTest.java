package com.persona.user.service;

import com.persona.user.exception.DuplicateEmailException;
import com.persona.user.exception.UserNotFoundException;
import com.persona.user.model.User;
import com.persona.user.repository.InMemoryUserRepository;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link UserService}.
 *
 * <p>No {@code @SpringBootTest}, no {@code @Autowired}, no application context —
 * the service is built with {@code new} and handed a repository by hand. That is
 * the direct payoff for constructor injection, and it matters more in this
 * architecture than in the monolith: every service in the system pays the
 * context-startup cost separately, so a habit of testing through the framework
 * compounds across all of them.
 *
 * <p>A failure in this file can only mean the business logic is wrong. It can
 * never mean the wiring is — which is exactly what makes a red test here useful.
 */
class UserServiceTest {

    private final UserService service = new UserService(new InMemoryUserRepository());

    private User anishom() {
        return new User("khi0ne@example.com", "Anishom", "Frost", "Pass1234#");
    }

    @Test
    void registeringStoresTheUser() {
        service.register(anishom());

        assertEquals(1, service.count());
        assertTrue(service.findByEmail("khi0ne@example.com").isPresent());
    }

    @Test
    void registerReturnsTheSavedUser() {
        User saved = service.register(anishom());

        assertEquals(anishom(), saved);
    }

    @Test
    void secondSignupWithTheSameEmailIsRejected() {
        service.register(anishom());

        DuplicateEmailException thrown =
                assertThrows(DuplicateEmailException.class, () -> service.register(anishom()));

        // The email is carried as data, not only inside the message. Day-09 builds a
        // 409 from this field — and a remote caller rebuilds the meaning from the
        // status code, since the exception type itself cannot cross the wire.
        assertEquals("khi0ne@example.com", thrown.getEmail());
        assertEquals(1, service.count());
    }

    @Test
    void aDifferentEmailIsNotADuplicate() {
        service.register(anishom());
        service.register(new User("lincoln@example.com", "Lincoln", "Frost", "Pass1234#"));

        assertEquals(2, service.count());
    }

    @Test
    void missingUserIsEmptyNotAnError() {
        assertTrue(service.findByEmail("nobody@example.com").isEmpty());
    }

    @Test
    void getByEmailThrowsWhenMissing() {
        UserNotFoundException thrown =
                assertThrows(UserNotFoundException.class, () -> service.getByEmail("nobody@example.com"));

        assertEquals("nobody@example.com", thrown.getEmail());
    }

    @Test
    void deletingRemovesTheUser() {
        service.register(anishom());
        service.deleteByEmail("khi0ne@example.com");

        assertEquals(0, service.count());
        assertTrue(service.findByEmail("khi0ne@example.com").isEmpty());
    }

    @Test
    void deletingAMissingUserThrows() {
        assertThrows(UserNotFoundException.class, () -> service.deleteByEmail("nobody@example.com"));
    }

    @Test
    void anEmailFreedByDeletionCanBeRegisteredAgain() {
        service.register(anishom());
        service.deleteByEmail("khi0ne@example.com");
        service.register(anishom());

        assertEquals(1, service.count());
    }

    /**
     * The duplicate check passing does not mean concurrent signups are safe.
     *
     * <p>This test documents what the check actually buys — a clean error on the
     * sequential path — and deliberately does not attempt to prove thread safety,
     * because the check is not thread-safe and no test here could make it so. In
     * this architecture the racing parties are separate JVMs on separate machines,
     * so the guarantee has to come from a {@code UNIQUE} constraint in the shared
     * database, which arrives on Day-03.
     */
    @Test
    void theDuplicateCheckGuardsTheSequentialPathOnly() {
        service.register(anishom());

        assertThrows(DuplicateEmailException.class, () -> service.register(anishom()));
        assertEquals(1, service.count());
    }
}

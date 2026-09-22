package com.persona.user.service;

import com.persona.user.dto.CreateUserRequest;
import com.persona.user.dto.UpdateProfileRequest;
import com.persona.user.exception.DuplicateEmailException;
import com.persona.user.exception.UserNotFoundException;
import com.persona.user.model.User;
import com.persona.user.repository.InMemoryUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link UserService}.
 *
 * <p>Note what is absent: {@code @SpringBootTest}, {@code @Autowired}, any Spring
 * annotation at all. The service is built with {@code new} and handed a repository
 * by hand. That is not a shortcut around Spring — it is the payoff for constructor
 * injection. These tests start no application context, so they run in milliseconds
 * rather than seconds, and a failure here can only mean the business logic is
 * wrong, never that the wiring is.
 *
 * <p><b>A microservice note on the hashing tests below.</b> They matter more
 * here than in the monolith, because this service is the only place in the
 * system where a plaintext password legitimately exists. Every other service
 * receives an identity, never a credential. If hashing regressed here, no other
 * service's tests could possibly catch it — there is no shared process and no
 * shared database in which the mistake would show up. A boundary that contains
 * a secret also contains the responsibility for testing it.
 *
 * <p>Had {@code UserService} used field injection, none of these tests could exist
 * in this form: a private field with no setter cannot be populated without Spring,
 * so every test would have to boot the framework to test one {@code if}.
 */
class UserServiceTest {

    /**
     * A real BCryptPasswordEncoder at cost 4 rather than the default 10.
     *
     * <p>Cost 4 is 2^4 = 16 rounds instead of 1024 — roughly sixty times faster,
     * which keeps this suite in milliseconds while still exercising genuine
     * BCrypt: the output has the real format, the salt is real and random, and
     * matches() does real work.
     *
     * <p>A stub encoder returning "hashed:" + input was the alternative and was
     * rejected. It would make {@link #registeringHashesThePassword} pass against
     * an implementation that does no hashing at all, which is precisely the
     * property under test. A fake is the wrong tool when the thing being
     * verified is that the real one was used.
     *
     * <p>Note the cost is visible in the output, so a test could assert on it.
     * Deliberately not asserting: the cost is a tuning parameter that is
     * expected to rise, and a test that pins it would fail for the right
     * reason at the wrong time.
     */
    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);

    private final UserService service =
            new UserService(new InMemoryUserRepository(), encoder);

    /**
     * The fixture is now a {@link CreateUserRequest}, not a {@code User}.
     *
     * <p>That change is the whole Day-05 service lesson in one line: the service
     * accepts the type that can only have come from a signup, so it never has to
     * guess whether the password it is holding is plaintext or already hashed.
     */
    private CreateUserRequest signupRequest() {
        return new CreateUserRequest("khi0ne@example.com", "Anishom", "Frost", "Pass1234#");
    }

    @Test
    void registeringStoresTheUser() {
        service.register(signupRequest());

        assertEquals(1, service.count());
        assertTrue(service.findByEmail("khi0ne@example.com").isPresent());
    }

    @Test
    void registerReturnsTheSavedUser() {
        User saved = service.register(signupRequest());

        // The caller no longer holds the object to compare against: it passed a
        // DTO and got back a User the service constructed. That is Day-05's
        // change made visible — the returned object is the ONLY place the
        // hashed password and, from Day-03, the database-generated id exist.
        assertEquals("khi0ne@example.com", saved.getEmail());
        assertEquals("Anishom", saved.getFirstName());
    }

    @Test
    void secondSignupWithTheSameEmailIsRejected() {
        service.register(signupRequest());

        DuplicateEmailException thrown =
                assertThrows(DuplicateEmailException.class, () -> service.register(signupRequest()));

        // The exception carries the email as data, not only inside a message string.
        // Day-09 builds a 409 response from this without parsing English.
        assertEquals("khi0ne@example.com", thrown.getEmail());
        assertEquals(1, service.count());
    }

    @Test
    void aDifferentEmailIsNotADuplicate() {
        service.register(signupRequest());
        service.register(new CreateUserRequest("lincoln@example.com", "Lincoln", "Frost", "Pass1234#"));

        assertEquals(2, service.count());
    }

    @Test
    void missingUserIsEmptyNotAnError() {
        // Absence is a normal answer here, not a failure: "is this email free?"
        // expects to find nothing most of the time.
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
        service.register(signupRequest());
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
        service.register(signupRequest());
        service.deleteByEmail("khi0ne@example.com");
        service.register(signupRequest());

        assertEquals(1, service.count());
    }

    // =================================================================
    //  Day-05 — hashing and profile update.
    // =================================================================

    /**
     * The test this whole day exists for.
     *
     * <p>Note what it asserts: not "does it hash" but <b>"can the plaintext
     * reach storage"</b>. Those are different questions, and only the second one
     * is the security property. A test asserting merely that the stored value
     * starts with {@code $2} would pass against code that stored the hash in one
     * column and the plaintext in another.
     *
     * <p>{@code assertNotEquals} against the input is the assertion that would
     * catch the real failure — a register() that forgot to encode. Asserting on
     * absence, like {@code responseNeverContainsThePassword} on Day-02: the
     * cases worth testing are usually the ones where nothing happens.
     */
    @Test
    void registeringHashesThePassword() {
        User saved = service.register(signupRequest());

        assertNotEquals("Pass1234#", saved.getPasswordHash(),
                "the plaintext password must never be stored");
        assertTrue(saved.getPasswordHash().startsWith("$2"),
                "stored value should be a BCrypt hash");
        assertEquals(60, saved.getPasswordHash().length(),
                "a BCrypt hash is always exactly 60 characters");
    }

    /**
     * The stored hash verifies against the original password, and against
     * nothing else.
     *
     * <p>Hashing that cannot be verified is just corruption, so the previous
     * test alone is not enough — it would pass against a register() that stored
     * a hash of the wrong thing entirely.
     */
    @Test
    void theStoredHashVerifiesAgainstTheOriginalPassword() {
        User saved = service.register(signupRequest());

        assertTrue(encoder.matches("Pass1234#", saved.getPasswordHash()));
        assertFalse(encoder.matches("wrong-password", saved.getPasswordHash()));
    }

    /**
     * Two users with the same password get different hashes.
     *
     * <p>This is the salt, proven rather than assumed. Without it, an attacker
     * who cracks one hash has cracked every account sharing that password, and
     * a precomputed rainbow table cracks the whole column at once.
     *
     * <p>It is also the test that explains why comparison uses
     * {@code matches()} and never {@code equals()}:
     * {@code encode(x).equals(encode(x))} is false, always.
     */
    @Test
    void twoUsersWithTheSamePasswordGetDifferentHashes() {
        User first = service.register(signupRequest());
        User second = service.register(
                new CreateUserRequest("lincoln@example.com", "Lincoln", "Frost", "Pass1234#"));

        assertNotEquals(first.getPasswordHash(), second.getPasswordHash(),
                "same password, different salts, therefore different hashes");

        // Both still verify. Different hashes of the same password are not a
        // contradiction — the salt is stored inside each one.
        assertTrue(encoder.matches("Pass1234#", first.getPasswordHash()));
        assertTrue(encoder.matches("Pass1234#", second.getPasswordHash()));
    }

    @Test
    void updatingAProfileChangesTheEditableFields() {
        service.register(signupRequest());

        User updated = service.updateProfile("khi0ne@example.com",
                new UpdateProfileRequest("Turhan", "Winter", "avatar.png"));

        assertEquals("Turhan", updated.getFirstName());
        assertEquals("Winter", updated.getLastName());
        assertEquals("avatar.png", updated.getImage().orElseThrow());
    }

    /**
     * The derived username follows the name with no code and no second write.
     *
     * <p>Slice 3's lesson paying out: {@code username} has no backing field, so
     * it cannot go stale. Had it been stored, this test would be the one that
     * caught the missing update — and only if somebody thought to write it.
     */
    @Test
    void updatingTheNameUpdatesTheDerivedUsername() {
        service.register(signupRequest());
        assertEquals("@anishom-frost", service.getByEmail("khi0ne@example.com").getUsername());

        service.updateProfile("khi0ne@example.com",
                new UpdateProfileRequest("Turhan", "Winter", null));

        assertEquals("@turhan-winter", service.getByEmail("khi0ne@example.com").getUsername());
    }

    /**
     * Updating a profile leaves the password hash untouched.
     *
     * <p>The point of {@link UpdateProfileRequest} not having a password field,
     * asserted rather than assumed. This is the test that fails the day somebody
     * "simplifies" the signature to take a {@code User}.
     */
    @Test
    void updatingAProfileDoesNotTouchThePassword() {
        User registered = service.register(signupRequest());
        String hashBefore = registered.getPasswordHash();

        User updated = service.updateProfile("khi0ne@example.com",
                new UpdateProfileRequest("Turhan", "Winter", null));

        assertEquals(hashBefore, updated.getPasswordHash());
        assertTrue(encoder.matches("Pass1234#", updated.getPasswordHash()),
                "the original password must still work after a profile edit");
    }

    /**
     * Omitting the image clears it — the documented consequence of PUT meaning
     * "complete new state".
     *
     * <p>Written as a test precisely because it is the surprising behaviour.
     * Untested, it is the kind of thing a later reader "fixes" into a null-check
     * that silently makes removing an avatar impossible.
     */
    @Test
    void anAbsentImageInAnUpdateClearsIt() {
        service.register(signupRequest());
        service.updateProfile("khi0ne@example.com",
                new UpdateProfileRequest("Anishom", "Frost", "avatar.png"));

        User cleared = service.updateProfile("khi0ne@example.com",
                new UpdateProfileRequest("Anishom", "Frost", null));

        assertTrue(cleared.getImage().isEmpty());
    }

    @Test
    void updatingAMissingUserThrows() {
        assertThrows(UserNotFoundException.class, () -> service.updateProfile(
                "nobody@example.com", new UpdateProfileRequest("A", "B", null)));
    }
}

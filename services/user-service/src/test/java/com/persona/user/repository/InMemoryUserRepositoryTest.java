package com.persona.user.repository;

import com.persona.user.exception.DuplicateEmailException;
import com.persona.user.exception.UserNotFoundException;
import com.persona.user.model.User;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryUserRepositoryTest {

    private User anishom() {
        return new User("khi0ne@example.com", "Anishom", "Frost", "Pass1234#");
    }

    @Test
    void sameEmailMeansSamePerson() {
        assertEquals(anishom(), new User("khi0ne@example.com", "Different", "Name", "Other9999#"));
    }

    @Test
    void differentEmailMeansDifferentPeople() {
        assertNotEquals(anishom(), new User("other@example.com", "Anishom", "Frost", "Pass1234#"));
    }

    @Test
    void equalUsersAreFoundInAHashSet() {
        // Fails if hashCode is omitted while equals is overridden.
        Set<User> set = new HashSet<>();
        set.add(anishom());

        assertTrue(set.contains(anishom()));
        set.add(anishom());
        assertEquals(1, set.size());
    }

    @Test
    void savedUserCanBeFound() {
        InMemoryUserRepository repo = new InMemoryUserRepository();
        repo.save(anishom());

        assertTrue(repo.findByEmail("khi0ne@example.com").isPresent());
        assertEquals(1, repo.count());
    }

    @Test
    void missingUserIsEmptyNotAnError() {
        assertTrue(new InMemoryUserRepository().findByEmail("nobody@example.com").isEmpty());
    }

    @Test
    void getByEmailThrowsWhenMissing() {
        InMemoryUserRepository repo = new InMemoryUserRepository();

        UserNotFoundException thrown =
                assertThrows(UserNotFoundException.class, () -> repo.getByEmail("nobody@example.com"));

        assertEquals("nobody@example.com", thrown.getEmail());
    }

    @Test
    void secondSignupWithTheSameEmailIsRejected() {
        InMemoryUserRepository repo = new InMemoryUserRepository();
        repo.save(anishom());

        assertThrows(DuplicateEmailException.class, () -> repo.save(anishom()));
        assertEquals(1, repo.count());
    }

    @Test
    void deletingAMissingUserThrows() {
        InMemoryUserRepository repo = new InMemoryUserRepository();
        assertThrows(UserNotFoundException.class, () -> repo.deleteByEmail("nobody@example.com"));
    }

    @Test
    void findAllCannotBeUsedToMutateTheRepository() {
        InMemoryUserRepository repo = new InMemoryUserRepository();
        repo.save(anishom());

        assertThrows(UnsupportedOperationException.class, () -> repo.findAll().clear());
        assertEquals(1, repo.count());
    }
}

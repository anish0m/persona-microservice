package com.persona.user.repository;

import com.persona.user.exception.DuplicateEmailException;
import com.persona.user.exception.UserNotFoundException;
import com.persona.user.model.User;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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

    // ---- Day-09: paging -------------------------------------------------

    /** Five users, emails deliberately out of insertion order. */
    private InMemoryUserRepository repoWithFiveUsers() {
        InMemoryUserRepository repo = new InMemoryUserRepository();
        for (String name : new String[] {"e", "c", "a", "d", "b"}) {
            repo.save(new User(name + "@example.com", "First", "Last", "Pass1234#"));
        }
        return repo;
    }

    @Test
    void aPageIsLimitedToItsSizeButTheTotalCountsEveryUser() {
        Page<User> page = repoWithFiveUsers().findAll(PageRequest.of(0, 2));

        assertEquals(2, page.getContent().size());

        // The distinction the whole type exists for: totalElements describes the
        // repository, not the page. Returning 2 here would make getTotalPages()
        // report 1 and the UI would hide three users without any error.
        assertEquals(5, page.getTotalElements());
        assertEquals(3, page.getTotalPages());
    }

    @Test
    void pagesDoNotOverlapAndTogetherCoverEveryUser() {
        InMemoryUserRepository repo = repoWithFiveUsers();

        List<String> seen = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            repo.findAll(PageRequest.of(i, 2)).forEach(u -> seen.add(u.getEmail()));
        }

        // No duplicates and nothing missed — the property that fails first when
        // paging is applied to an unordered collection.
        assertEquals(5, seen.size());
        assertEquals(5, Set.copyOf(seen).size());
    }

    @Test
    void theLastPageIsShortRatherThanPadded() {
        Page<User> last = repoWithFiveUsers().findAll(PageRequest.of(2, 2));

        assertEquals(1, last.getContent().size());
        assertTrue(last.isLast());
    }

    /**
     * Asking past the end is an empty page, not an exception.
     *
     * <p>This is the arithmetic trap in the implementation: {@code subList}
     * throws {@code IndexOutOfBoundsException} when {@code from} exceeds the
     * list size, so the offset has to be clamped. SQL {@code OFFSET} past the
     * end returns no rows, and the fake has to agree or the profiles diverge.
     */
    @Test
    void aPageBeyondTheEndIsEmptyAndDoesNotThrow() {
        Page<User> page = repoWithFiveUsers().findAll(PageRequest.of(99, 2));

        assertTrue(page.getContent().isEmpty());
        assertEquals(5, page.getTotalElements());
    }

    @Test
    void anEmptyRepositoryPagesToAnEmptyPage() {
        Page<User> page = new InMemoryUserRepository().findAll(PageRequest.of(0, 20));

        assertTrue(page.getContent().isEmpty());
        assertEquals(0, page.getTotalElements());
        assertEquals(0, page.getTotalPages());
    }

    /**
     * Ordering is imposed, not incidental.
     *
     * <p>Note what this asserts and what it does not: this profile orders by
     * email, because {@code id} is null without a database. The JDBC and JPA
     * implementations order by {@code id}. So <em>which</em> users land on page 2
     * is profile-specific and is not asserted anywhere portable — only that
     * some stable order exists, which is what makes paging meaningful at all.
     */
    @Test
    void usersComeBackInAStableOrderAcrossPages() {
        Page<User> first = repoWithFiveUsers().findAll(PageRequest.of(0, 2));

        assertEquals(List.of("a@example.com", "b@example.com"),
                first.getContent().stream().map(User::getEmail).toList());
    }
}

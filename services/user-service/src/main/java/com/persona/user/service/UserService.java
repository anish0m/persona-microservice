package com.persona.user.service;

import com.persona.user.exception.DuplicateEmailException;
import com.persona.user.exception.UserNotFoundException;
import com.persona.user.model.User;
import com.persona.user.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Optional;

/**
 * Business rules for the user domain.
 *
 * <p>This class is the first one in the project that exists purely because of
 * layering. The model validates itself, the repository stores things, and between
 * signing up and being stored there is a set of decisions belonging to neither:
 * whether this signup is allowed at all. That is what lives here.
 *
 * <p><b>What this class must never learn.</b> No {@code HttpServletRequest}, no
 * status codes, no JSON, no {@code @RequestMapping}. If HTTP disappeared tomorrow
 * and persona were driven from a command line or a scheduled import, every method
 * below would still be correct and still be called. That is the test for whether
 * a rule belongs here rather than in the controller — not importance, but whether
 * it survives the transport being replaced.
 *
 * <p>It also does not know <em>where</em> users are stored. It holds an
 * {@code InMemoryUserRepository} today and a PostgreSQL-backed one from Day-03,
 * and the intent is that this file does not change when that happens.
 */
@Service
public class UserService {

    /**
     * Injected through the constructor, and {@code final} for a reason worth
     * separating from the other {@code final}s in this project.
     *
     * <p>{@code User.email} is {@code final} because it is a hash key and a moving
     * key gets lost in its bucket. That is not the danger here — nobody hashes a
     * repository. This field is {@code final} because {@link UserService} is a
     * <b>singleton</b>: Spring builds one instance and every concurrent request
     * shares it. A reassignable field on a shared object can be swapped while
     * requests are mid-flight, so some see the old repository and some the new,
     * with no exception and no reproducible failure — only wrong answers. Being
     * {@code final} makes that impossible to express rather than merely agreed.
     */
    private final UserRepository repository;

    /**
     * Constructor injection, with no {@code @Autowired} — a class with exactly one
     * constructor needs none, because Spring has nothing to choose between.
     *
     * <p>Three things follow from injecting here rather than annotating the field.
     * The field can be {@code final}, per above. The object is never half-built:
     * there is no window in which a {@code UserService} exists with a {@code null}
     * repository. And this class stays testable without Spring at all —
     * {@code new UserService(new InMemoryUserRepository())} is a valid line in a
     * plain JUnit test, which is why the tests for this class run in milliseconds
     * and need no application context.
     */
    public UserService(UserRepository repository) {
        this.repository = repository;
    }

    /**
     * Registers a new user.
     *
     * <p>The duplicate check below looks redundant — {@code InMemoryUserRepository}
     * already throws on a taken key. It is not redundant, but nor is it what makes
     * the system correct, and the distinction matters:
     *
     * <ul>
     *   <li><b>This check is policy.</b> It decides that a taken email means
     *       rejection, and it exists so the caller gets a clean, deliberate error
     *       instead of whatever the storage layer happens to throw. Policy can
     *       change — a later persona might allow reusing the email of a
     *       soft-deleted account — and when it does, it changes here.</li>
     *   <li><b>The storage constraint is the guarantee.</b> Both this check and the
     *       repository's are look-then-act, and both lose the same race: two
     *       threads read "free" before either writes. Moving the check between
     *       layers does not make it atomic. From Day-03 the real guarantee is a
     *       {@code UNIQUE} constraint in PostgreSQL, which cannot be raced because
     *       the database serialises it.</li>
     * </ul>
     *
     * <p>Which is the rule to carry forward: <b>never let the only copy of a
     * correctness guarantee live in application code.</b> Application code races.
     * Constraints do not. The check here buys a good error message, not safety.
     */
    public User register(User user) {
        if (repository.findByEmail(user.getEmail()).isPresent()) {
            throw new DuplicateEmailException(user.getEmail());
        }
        return repository.save(user);
    }

    /**
     * Looks a user up, tolerating absence.
     *
     * <p>Returns {@code Optional} rather than throwing because <b>absence is not
     * always an error</b>. A signup form asking "is this email free?" expects to
     * find nothing most of the time; that is a normal answer, and an exception for
     * a normal answer is control flow wearing a disguise.
     */
    public Optional<User> findByEmail(String email) {
        return repository.findByEmail(email);
    }

    /**
     * Loads a user who must exist, throwing {@link UserNotFoundException} if not.
     *
     * <p>For callers that cannot sensibly continue without the user — loading a
     * profile for a logged-in session, say. Forcing such a caller to unwrap an
     * {@code Optional} only makes it write an {@code if} it has no answer for.
     */
    public User getByEmail(String email) {
        return repository.getByEmail(email);
    }

    /**
     * Deletes a user, throwing if there was nothing to delete.
     *
     * <p>A thin pass-through today. It exists anyway rather than letting the
     * controller reach past this class to the repository, because the moment
     * deletion grows a rule — refuse while a balance is outstanding, archive before
     * removing, emit an event — that rule has an obvious home. A layer that is
     * only added once it is needed tends to get skipped exactly when it is.
     */
    public void deleteByEmail(String email) {
        repository.deleteByEmail(email);
    }

    /**
     * Every registered user.
     *
     * <p>Harmless at this size and a mistake at scale — Day-07 replaces it with
     * pagination, once there is a database that can offer a page without loading
     * everything first.
     */
    public Collection<User> findAll() {
        return repository.findAll();
    }

    public long count() {
        return repository.count();
    }
}

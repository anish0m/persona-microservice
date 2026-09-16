package com.persona.user.repository;

import com.persona.user.model.User;

import java.util.Collection;
import java.util.Optional;

/**
 * What the application can ask of user storage — and nothing about how.
 *
 * <p><b>Why this interface appears today and not on Day-01.</b> The package
 * documentation has claimed since slice 4 that swapping the store would not
 * change the service. That was not quite true: {@code UserService} held a field
 * of type {@code InMemoryUserRepository}, the concrete class. It named the
 * implementation, so switching to PostgreSQL would have meant editing it.
 *
 * <p>Extracting the interface now, at the exact moment a second implementation
 * exists, is the honest sequence. Writing it on Day-01 would have been an
 * interface with one implementation — a prediction. Today it is a fact: there
 * are two, and something has to describe what they have in common.
 *
 * <p>This is also where Day-00's lesson lands for real. {@code UserService}
 * depends on this type; Spring injects whichever bean implements it. The service
 * cannot tell the difference, because there is no method here that could reveal
 * it — no {@code getConnection}, no {@code getMap}. <b>An interface is a
 * vocabulary, and a caller can only say what the vocabulary allows.</b>
 *
 * <p>Note what the method names do NOT contain: no {@code select}, no
 * {@code query}, no {@code row}. {@code findByEmail} is a question about users,
 * not about tables. If SQL vocabulary leaked into these names, the abstraction
 * would be a wrapper around a database rather than a description of storage —
 * and a {@code findAllWhereClause(String sql)} would make the in-memory
 * implementation impossible to write.
 */
public interface UserRepository {

    /**
     * Stores a new user and returns it.
     *
     * <p>Returns rather than {@code void}, and Day-03 is where that stops looking
     * pointless: the database generates {@code id} and {@code created_at}, so the
     * returned object is the only place those values exist. The in-memory version
     * returned its argument unchanged, which made the signature look like
     * ceremony. It was not; it was the signature the real implementation needs.
     *
     * @throws com.persona.user.exception.DuplicateEmailException if the email is taken
     */
    User save(User user);

    /**
     * Finds a user, or empty if there is none.
     *
     * <p>{@code Optional} here and an exception in {@link #getByEmail} — the whole
     * design in one pair of methods. <b>Absence is not always an error.</b> "Is
     * this email taken?" expects to find nothing most of the time.
     */
    Optional<User> findByEmail(String email);

    /** Finds a user, or throws because the caller cannot continue without one. */
    User getByEmail(String email);

    /** @throws com.persona.user.exception.UserNotFoundException if nothing was deleted */
    void deleteByEmail(String email);

    Collection<User> findAll();

    long count();
}

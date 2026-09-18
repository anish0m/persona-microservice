package com.persona.user.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.persona.user.model.User;

/**
 * The Spring Data interface — and the whole implementation is its absence.
 *
 * <p>There is no class implementing this. At startup Spring Data builds a proxy
 * that implements every method: roughly eighteen inherited from
 * {@link JpaRepository} (save, findById, findAll, delete, count, existsById,
 * findAll(Pageable)…) plus a body for {@link #findByEmail} derived from its
 * NAME. The two type parameters are the entity and the type of its {@code @Id}.
 *
 * <p><b>Why this is not {@code UserRepository} itself.</b> The application's own
 * interface is deliberately narrow — six methods, phrased as questions about
 * users rather than about tables. Extending {@code JpaRepository} directly from
 * it would hand every caller {@code flush()}, {@code getReferenceById()},
 * {@code deleteAllInBatch()} and the rest, which is JPA vocabulary leaking into
 * the service layer. Day-01's rule, unchanged: <b>an interface is a vocabulary,
 * and a caller can only say what the vocabulary allows.</b> So this interface
 * stays inside the repository package and {@link JpaUserRepository} adapts it.
 *
 * <p>The cost is one extra file. The benefit is that {@code UserService} still
 * cannot tell which of the three implementations it has — and on Day-17, when
 * the microservice split happens, nothing outside this package has to change.
 *
 * <h2>The method name IS the query</h2>
 *
 * <p>Spring Data strips the {@code findBy} prefix and parses the remainder
 * against the ENTITY'S FIELD NAMES, producing
 * {@code SELECT u FROM User u WHERE u.email = ?1}. Nobody writes that.
 *
 * <p>The failure mode is the part worth keeping. Misspell the field —
 * {@code findByEmial} — and the application <b>refuses to start</b>:
 * {@code No property 'emial' found for type User}. Not a runtime error on first
 * call, not a wrong result at 3am. Compare Day-03's {@link JdbcUserRepository},
 * where a typo'd column name is merely characters in a string until the query
 * executes.
 *
 * <p>But state the limit precisely, because it is exactly the gap this project
 * keeps falling into: <b>it validates that the property EXISTS, not that it was
 * the one you meant.</b> {@code findByFirstName} parses perfectly when
 * {@code findByLastName} was intended, and returns the wrong rows, green. Same
 * shape as the seven-argument constructor that compiled while assigning three
 * parameters to nothing. The compiler checks types; Spring Data checks names;
 * only a test checks meaning.
 *
 * @see JpaUserRepository the adapter that turns this into a {@link UserRepository}
 */
public interface SpringDataUserRepository extends JpaRepository<User, Long> {

    /**
     * Declared, never implemented.
     *
     * <p>{@code Optional} rather than {@code User} is a claim of AT MOST ONE. If
     * two rows ever matched, this throws
     * {@code IncorrectResultSizeDataAccessException} rather than silently picking
     * one. What makes the claim true is not this signature — it is
     * {@code email VARCHAR(255) NOT NULL UNIQUE} in V1. Policy and guarantee,
     * again: the type states the expectation, the constraint enforces it.
     */
    Optional<User> findByEmail(String email);

    /**
     * Derived the same way, into {@code DELETE FROM users WHERE email = ?}.
     *
     * <p>Returns the number of rows deleted, which is what makes the
     * "deleting nothing is an error" rule expressible without first doing a
     * SELECT — the same {@code rows == 0} branch {@link JdbcUserRepository}
     * checks by hand. Silence is not a pass.
     *
     * <p>A derived DELETE needs {@code @Modifying} and a transaction, which
     * {@link JpaUserRepository} supplies at the method that calls it.
     */
    long deleteByEmail(String email);

    /** {@code SELECT COUNT(u) FROM User u WHERE u.email = ?1} — no row is loaded. */
    boolean existsByEmail(String email);
}

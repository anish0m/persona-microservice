package com.persona.user.service;

import com.persona.user.exception.DuplicateEmailException;
import com.persona.user.exception.UserNotFoundException;
import com.persona.user.model.User;
import com.persona.user.repository.InMemoryUserRepository;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Optional;

/**
 * Business rules for the user domain — and, in this architecture, <b>only</b> the
 * user domain.
 *
 * <p>Method-for-method identical to the monolith's {@code com.persona.service}
 * today, which is the honest starting position: a microservice is not a different
 * way of writing a class, it is a different way of drawing a boundary around one.
 * Everything interesting about that difference is invisible until this service
 * needs something it does not own.
 *
 * <p><b>Where the two architectures part company.</b> Suppose registration must
 * also open a wallet. In the monolith that is one injected bean and one method
 * call: in-process, microseconds, inside a single {@code @Transactional} block, so
 * a failure anywhere rolls the whole thing back. Here it is a network call to
 * another service, and each of those properties is lost:
 *
 * <ul>
 *   <li><b>It can be slow.</b> A method call has no timeout because it cannot hang
 *       independently. A network call needs one, and choosing it is a real
 *       decision.</li>
 *   <li><b>It can fail on its own.</b> The user is saved, wallet creation times
 *       out, and now the two services disagree about reality. Nothing rolled
 *       back.</li>
 *   <li><b>It can fail ambiguously.</b> A timeout does not tell you whether the
 *       other side did the work. Retrying might duplicate it — which is why
 *       Day-11 is about idempotency, and why it is a microservice topic rather
 *       than a monolith one.</li>
 * </ul>
 *
 * <p>That single substitution — method call becomes network call — is the source
 * of most of Days 10-15. It is worth noticing that the monolith gets all three
 * guarantees for free and this service must buy each one back with code.
 *
 * <p>The rule from the monolith still holds unchanged: no {@code HttpServletRequest},
 * no status codes, no JSON. HTTP is how this service is reached, not what it is.
 */
@Service
public class UserService {

    /**
     * {@code final} because this bean is a singleton shared by every concurrent
     * request — a reassignable field could be swapped mid-flight, leaving some
     * requests on the old repository and some on the new, with no exception and no
     * reproducible failure. Note this is a different danger from {@code User.email}
     * being {@code final}, which was about a hash key moving between buckets.
     * Same keyword, unrelated reasons.
     */
    private final InMemoryUserRepository repository;

    /**
     * Constructor injection, no {@code @Autowired} needed — one constructor means
     * Spring has nothing to choose between.
     *
     * <p>The testability argument is worth more here than in the monolith. Booting
     * a Spring context costs a second or two; in a microservice architecture there
     * are many services, each with its own suite, and a habit of testing through
     * the framework compounds across all of them. Being able to write
     * {@code new UserService(new InMemoryUserRepository())} keeps that cost at
     * zero.
     */
    public UserService(InMemoryUserRepository repository) {
        this.repository = repository;
    }

    /**
     * Registers a new user.
     *
     * <p>The duplicate check is policy — it decides a taken email means rejection,
     * and it exists so the caller gets a deliberate error rather than whatever
     * storage happens to throw. It is <em>not</em> the guarantee, and here the
     * reason is starker than in the monolith: the race is not between two threads
     * in one JVM but between <b>separate instances of this service on separate
     * machines</b>. No Java lock spans them. No {@code synchronized}, no
     * {@code ConcurrentHashMap}, nothing in this file can help.
     *
     * <p>From Day-03 the sole real guarantee is a {@code UNIQUE} constraint in
     * PostgreSQL — the one thing all instances share, and therefore the only place
     * the rule can actually be enforced. <b>Never let the only copy of a
     * correctness guarantee live in application code.</b>
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
     * <p>{@code Optional} because absence is not always an error — "is this email
     * free?" expects to find nothing most of the time.
     *
     * <p>This method is a likely candidate for being called by another service one
     * day, and when that happens the {@code Optional} does not survive the trip: it
     * flattens to a 200 or a 404, and the caller rebuilds the distinction from the
     * status code. The meaning is preserved by agreement, not by the compiler —
     * which is the recurring cost of a network boundary.
     */
    public Optional<User> findByEmail(String email) {
        return repository.findByEmail(email);
    }

    /**
     * Loads a user who must exist, throwing {@link UserNotFoundException} otherwise.
     *
     * <p>The exception cannot cross the wire either. Day-09 turns it into a 404 and
     * a remote caller reconstructs "not found" from that number. Which is why the
     * exception carries {@code getEmail()} as data rather than only inside its
     * message — the controller builds the response from a field, never by parsing
     * English out of a string.
     */
    public User getByEmail(String email) {
        return repository.getByEmail(email);
    }

    /**
     * Deletes a user, throwing if there was nothing to delete.
     *
     * <p>Trivial today. In this architecture it is the method most likely to grow
     * complicated first: deleting a user here leaves whatever other services hold
     * about that user untouched, and no database constraint spans service
     * boundaries to notice. That is the eventual-consistency problem in miniature,
     * and Day-14 meets it properly.
     */
    public void deleteByEmail(String email) {
        repository.deleteByEmail(email);
    }

    public Collection<User> findAll() {
        return repository.findAll();
    }

    public long count() {
        return repository.count();
    }
}

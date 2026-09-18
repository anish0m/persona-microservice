package com.persona.user.repository;

import java.util.Collection;
import java.util.Optional;

import jakarta.persistence.EntityManager;

import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.persona.user.exception.DuplicateEmailException;
import com.persona.user.exception.UserNotFoundException;
import com.persona.user.model.User;

/**
 * The third implementation of {@link UserRepository}, and the point of the day.
 *
 * <pre>
 *         UserService  --depends on-->  UserRepository   (six methods)
 *                                             ^
 *              +------------------------------+------------------------------+
 *      InMemoryUserRepository         JdbcUserRepository          JpaUserRepository
 *          @Profile("test")            @Profile("jdbc")            @Profile("jpa")
 *            Day-00 slice 4                 Day-03                     Day-04
 * </pre>
 *
 * <p>{@code UserService} does not change by a single line, and neither does
 * {@code UserServiceTest}, which still builds the service with plain {@code new}
 * and no Spring context in about eleven milliseconds. That is the interface
 * extracted on Day-03 "at the exact moment a second implementation existed"
 * finally being paid for: swapping the entire persistence mechanism touches zero
 * lines of business logic.
 *
 * <p><b>{@link JdbcUserRepository} is deliberately NOT deleted.</b> It works, it
 * is tested, and keeping two real implementations side by side is what makes the
 * comparison legible — the hand-written SQL is still there to read next to the
 * version that generates it.
 *
 * <h2>The profile scheme had to change, and that is a lesson in itself</h2>
 *
 * <p>Before today: {@code InMemoryUserRepository} was {@code @Profile("test")}
 * and {@code JdbcUserRepository} was {@code @Profile("!test")}. Two
 * implementations, one boolean, and "not test" was a perfectly good way to say
 * "the real one" — while there was only one real one.
 *
 * <p>Adding a third breaks that immediately. A JPA bean with no profile, or the
 * JDBC one still claiming everything that is not test, means TWO candidates
 * outside tests and the application fails at startup with
 * {@code NoUniqueBeanDefinitionException}. Which is the good outcome — it is
 * Day-03's {@code NoSuchBeanDefinitionException} from the other direction, and it
 * fails at startup rather than at the first request.
 *
 * <p>So the negation is gone and all three now name themselves positively:
 * {@code test}, {@code jdbc}, {@code jpa}. <b>{@code @Profile("!x")} encodes an
 * assumption about how many alternatives will ever exist</b>, and it is wrong the
 * first time a third one arrives.
 *
 * <h2>What this class exists to do</h2>
 *
 * <p>It adapts {@link SpringDataUserRepository} (JPA vocabulary) to
 * {@link UserRepository} (the application's vocabulary), and translates
 * persistence exceptions into this application's own — the same job the
 * {@code @Repository} annotation describes and the same translation
 * {@link JdbcUserRepository} performs for {@code DuplicateKeyException}.
 *
 * <p>The delegation is thin on purpose. Every method here is one line plus,
 * where it matters, one decision.
 */
@Repository
@Profile("jpa")
public class JpaUserRepository implements UserRepository {

    private final SpringDataUserRepository users;

    /**
     * The one piece of raw JPA this class needs, for {@code refresh} — Spring
     * Data has no method for "re-read this row into this object".
     *
     * <p>Note where it is: inside the repository package, injected into a class
     * that implements the application's own interface. {@code UserService} has no
     * idea an EntityManager exists, and that boundary is the reason it did not
     * have to change today.
     */
    private final EntityManager entityManager;

    /**
     * Constructor injection, single constructor, no {@code @Autowired} — Day-01,
     * unchanged. Both fields are {@code final}, so an instance of this class
     * cannot exist without its collaborators.
     */
    public JpaUserRepository(SpringDataUserRepository users, EntityManager entityManager) {
        this.users = users;
        this.entityManager = entityManager;
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>Why the catch is still here, and why it is subtler than Day-03's.</b>
     * {@code UserService.register()} already checks for a duplicate before
     * calling this. That check is a policy with a gap — two concurrent requests
     * can both read "not taken" before either writes — so the UNIQUE constraint
     * remains the only real guarantee, exactly as V1's comment says.
     *
     * <p>What JPA changes is WHEN the violation arrives. Under JDBC the INSERT
     * was sent by this line and the exception came from this line. Under JPA,
     * {@code save()} may only queue the insert; Hibernate flushes at commit, or
     * before a query that could be affected. So the constraint violation can
     * surface from a completely different line — often from the framework as the
     * transaction commits, after this method has returned successfully.
     *
     * <p>This catch therefore handles the case where a flush happens to occur
     * here, and cannot handle the case where it does not. That is not a bug to be
     * fixed by catching harder; it is the shape of the boundary. <b>Day-03 put it
     * as: "handled" means two different things on the two sides of it.</b> Once
     * PostgreSQL rejects a statement the whole transaction is poisoned — SQL state
     * 25P02, which this project has already met — and the only correct response is
     * to let it roll back. Catching and continuing inside a dead transaction
     * produces the 25P02 error one layer later, wearing a disguise.
     */
    @Override
    @Transactional
    public User save(User user) {
        try {
            User saved = users.save(user);
            // saveAndFlush would do this in one call. It is spelled out because
            // the flush is the entire point: without it, save() only queues the
            // INSERT and the constraint violation arrives somewhere else
            // entirely — see the note above.
            users.flush();
            // created_at is insertable = false: the column is left out of the
            // INSERT so that V1's DEFAULT now() applies. Which means Hibernate
            // does not know what the database chose, and the in-memory object
            // still has a null timestamp. This reads it back.
            //
            // Day-03 solved exactly this with INSERT ... RETURNING, in one round
            // trip. Here it costs a second SELECT — a small, concrete price for
            // not writing the SQL, and a fair example of what the abstraction
            // charges. The alternative is letting the application generate the
            // timestamp, which was rejected on Day-03 for a better reason:
            // several app servers means several clocks, the database is one.
            entityManager.refresh(saved);
            return saved;
        } catch (DataIntegrityViolationException e) {
            // Deliberately narrow. Only the unique-email violation becomes a
            // DuplicateEmailException; anything else is a bug in this code
            // rather than a user error, and must not be dressed up as a 409.
            //
            // The constraint is identified by NAME, from the exception, rather
            // than by asking the database whether the email exists. Two reasons,
            // and the second one cost a debugging round:
            //
            //   1. The transaction is already poisoned. PostgreSQL refuses every
            //      subsequent statement until rollback — SQL state 25P02, met on
            //      Day-03. A SELECT here cannot work.
            //   2. Any query here triggers an AUTO-FLUSH first, and the failed
            //      entity is still sitting in the persistence context with a null
            //      id. Hibernate asserts: "Entry for instance of User has a null
            //      identifier (this can happen if the session is flushed after an
            //      exception occurs)". The real error is then buried under an
            //      assertion failure from the framework's own internals.
            //
            // Which is Day-03's lesson arriving on schedule: catching an
            // exception in Java does not undo it in the database, and the
            // persistence context is unusable afterwards too. Read the exception
            // that was thrown; do not ask the dead transaction a question.
            if (isDuplicateEmail(e)) {
                throw new DuplicateEmailException(user.getEmail());
            }
            throw e;
        }
    }

    /**
     * Identifies the specific constraint from the exception, without touching
     * the database.
     *
     * <p>The constraint name is generated by PostgreSQL from V1's
     * {@code email ... UNIQUE}: table, column, suffix. It is not written down
     * anywhere in the migration, which makes this string a genuine coupling to
     * something implicit — the honest fix is to NAME the constraint in a future
     * migration ({@code CONSTRAINT users_email_key UNIQUE (email)}) so the
     * identifier appears in both places and a rename breaks loudly.
     *
     * <p>Falls back to matching the column name, because the message format is a
     * driver detail rather than a contract.
     */
    private boolean isDuplicateEmail(DataIntegrityViolationException e) {
        String message = e.getMostSpecificCause().getMessage();
        return message != null
                && (message.contains("users_email_key") || message.contains("(email)"));
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code readOnly = true} is not decoration. Hibernate skips taking the
     * dirty-check snapshot for entities loaded in a read-only transaction, which
     * costs less memory and — more usefully — removes the possibility of an
     * accidental write. Without it, any setter called on a returned entity while
     * the transaction is still open is an UPDATE, with no {@code save()} anywhere
     * in sight.
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<User> findByEmail(String email) {
        return users.findByEmail(email);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The {@code Optional} / exception pair from slice 4, unchanged by three
     * changes of storage: <b>absence is not always an error.</b> "Is this email
     * taken?" expects to find nothing most of the time; "show me this user's
     * profile" cannot continue without one.
     */
    @Override
    @Transactional(readOnly = true)
    public User getByEmail(String email) {
        return users.findByEmail(email)
                .orElseThrow(() -> new UserNotFoundException(email));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Deleting nothing is not success. The derived {@code deleteByEmail}
     * returns a row count, so this reads it rather than assuming — the same
     * {@code rows == 0} branch as the JDBC implementation, for the same reason:
     * silence is not a pass.
     */
    @Override
    @Transactional
    public void deleteByEmail(String email) {
        if (users.deleteByEmail(email) == 0) {
            throw new UserNotFoundException(email);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>Known debt, and it is the same debt in all three implementations:</b>
     * this loads every row into memory. Fine at a hundred users, fatal at a
     * million. Spring Data makes the fix nearly free — {@code findAll(Pageable)}
     * is already inherited and needs no code — but taking it means changing
     * {@link UserRepository} and therefore the service and the controller, which
     * is a deliberate slice of its own rather than something smuggled in here.
     */
    @Override
    @Transactional(readOnly = true)
    public Collection<User> findAll() {
        return users.findAll();
    }

    /** {@inheritDoc} — {@code SELECT COUNT(*)}; no rows are loaded. */
    @Override
    @Transactional(readOnly = true)
    public long count() {
        return users.count();
    }
}

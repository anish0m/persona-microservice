package com.persona.user.service;

import com.persona.user.dto.CreateUserRequest;
import com.persona.user.dto.LoginRequest;
import com.persona.user.dto.UpdateProfileRequest;
import com.persona.user.exception.DuplicateEmailException;
import com.persona.user.exception.InvalidCredentialsException;
import com.persona.user.exception.UserNotFoundException;
import com.persona.user.model.User;
import com.persona.user.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
     * The hashing algorithm, injected as the <b>interface</b> rather than as
     * {@code BCryptPasswordEncoder}.
     *
     * <p>Which is the same discipline as depending on {@code UserRepository}
     * instead of {@code JdbcUserRepository}: this class must know that passwords
     * are hashed, and must not know how. Moving to argon2 then changes
     * {@code SecurityConfig} and nothing else.
     *
     * <p>It also means a test can inject a trivially fast encoder without a
     * Spring context — the property that keeps {@code UserServiceTest} at
     * milliseconds rather than seconds.
     */
    private final PasswordEncoder passwordEncoder;



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
    public UserService(UserRepository repository, PasswordEncoder passwordEncoder) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
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
     *
     * <h2>Day-05 — why the parameter is a DTO and not a User</h2>
     *
     * <p>This signature used to be {@code register(User)}, and the controller
     * built the {@code User}. The change is not a tidy-up; it removes a question
     * that had no reliable answer.
     *
     * <p>{@code register(User)} cannot distinguish a {@code User} assembled from
     * an untrusted request body from one Hibernate just loaded out of the
     * database. The first carries a plaintext password that <b>must</b> be
     * hashed; the second carries a hash that <b>must not</b> be hashed again —
     * doing so locks the account permanently, silently, with no error anywhere.
     * The type cannot say which it is holding, so a human has to remember, and
     * that is the bug.
     *
     * <p>A {@link CreateUserRequest} is only ever constructed from a request
     * body, so its password is only ever plaintext. The question stops being
     * askable rather than being answered carefully. <b>A type is a cheaper
     * guarantee than a check.</b>
     *
     * <h2>Why the hashing is here and nowhere else</h2>
     *
     * <ul>
     *   <li><b>Not in the controller.</b> Hashing is not a transport concern.
     *       Put it there and the Day-09 admin import, and anything else that is
     *       not an HTTP request, silently stores plaintext.</li>
     *   <li><b>Not in {@code User}.</b> The model would need a
     *       {@code PasswordEncoder}, and a domain class holding a Spring bean
     *       can no longer be built in a plain unit test.</li>
     *   <li><b>Here</b>, because this is the one method that knows a signup is
     *       happening — which is exactly when a plaintext password exists and is
     *       the last moment it may.</li>
     * </ul>
     *
     * <p>Note the ordering below is load-bearing: the plaintext is encoded
     * <em>before</em> the {@code User} is constructed, so no {@code User} object
     * ever holds a plaintext password, not even transiently. Had the {@code User}
     * been built first and the field set afterwards, there would be a window in
     * which a plaintext password is one {@code toString} or one exception away
     * from a log file.
     */
    public User register(CreateUserRequest request) {
        if (repository.findByEmail(request.email()).isPresent()) {
            throw new DuplicateEmailException(request.email());
        }

        String hash = passwordEncoder.encode(request.password());

        return repository.save(new User(
                request.email(),
                request.firstName(),
                request.lastName(),
                hash));
    }

    /**
     * Edits the mutable parts of a profile. <b>Day-05.</b>
     *
     * <p>{@link UpdateProfileRequest} carries three fields and cannot express a
     * change to the email, the password or the id. That is the defence: not a
     * check that those fields are absent, but a type in which they do not exist.
     * See that record for why each exclusion is not negotiable.
     *
     * <h2>Why there is no save() call</h2>
     *
     * <p>Under the JPA repository this method is running inside a transaction, so
     * the {@code User} returned by {@code getByEmail} is <b>managed</b>: it lives
     * in the persistence context, Hibernate holds a snapshot of it, and at commit
     * it compares the object to that snapshot and writes an UPDATE for whatever
     * differs. The setter <em>is</em> the write. Day-04 proved this with a test
     * that mutated a field, called no {@code save}, and then read the row back
     * with raw SQL.
     *
     * <p>Calling {@code save} anyway would not be wrong, merely redundant — and
     * it would teach the wrong model of what is happening.
     *
     * <p><b>{@code @Transactional} is what makes any of that true.</b> Without it
     * there is no persistence context spanning the method, the setters run
     * against a detached object, and nothing is written — <em>silently</em>, with
     * a green test if the test asserts on the returned object rather than on the
     * database. That is the fifth appearance of this project's recurring failure
     * mode, and the reason {@code UserServiceIT} reads the row back with
     * {@code JdbcClient} rather than asking Hibernate.
     *
     * <p>Note the annotation is <b>not</b> conditional on which repository is
     * active. Under the JDBC implementation it is harmless: the repository's own
     * write is a single statement and already atomic. Correct under both, which
     * is the property that has kept this class unchanged since Day-01.
     *
     * <h2>What is quietly working here</h2>
     *
     * <ul>
     *   <li>{@code getByEmail} throws rather than returning {@code Optional},
     *       because absence <em>is</em> an error for an update even though it is
     *       not for a lookup. Slice 4's rule.</li>
     *   <li>{@code setFirstName} still runs {@code requireText}, so even with the
     *       DTO's {@code @NotBlank} removed the object refuses the invalid
     *       state.</li>
     *   <li>{@code getUsername()} needs no code at all. It is derived from the
     *       two names with no backing field, so it cannot go stale — slice 3
     *       paying out.</li>
     * </ul>
     */
    @Transactional
    public User updateProfile(String email, UpdateProfileRequest request) {
        User user = repository.getByEmail(email);

        user.setFirstName(request.firstName());
        user.setLastName(request.lastName());
        user.setImage(request.image());

        // update(), NOT save().
        //
        // Calling save() here was the first attempt and it failed immediately:
        // save() means INSERT, and every implementation rejects an email that
        // already exists. The fix was NOT to relax save() into an upsert —
        // that check is the duplicate-signup guarantee, and weakening it to
        // make an unrelated method compile would have deleted a real
        // protection to buy a convenience. A test going red is not always a
        // request to change the thing it is testing.
        //
        // The two operations genuinely differ. save() asks "may this person
        // exist?", where the answer can legitimately be no. update() says
        // "this existing person changed", where a missing row is a bug.
        //
        // Under JPA this call does nothing but an existence check — dirty
        // checking already scheduled the write. Under JDBC it issues a real
        // three-column UPDATE. Correct under both, which is the property that
        // has kept this class unchanged since Day-01.
        return repository.update(user);
    }

    /**
     * Verifies credentials for login. <b>Day-06.</b> Returns the authenticated
     * {@link User} on success; throws {@link InvalidCredentialsException}
     * otherwise.
     *
     * <p><b>The two failure modes are collapsed into one deliberately.</b> "No
     * user with that email" and "wrong password for that email" are different
     * facts internally — but returning a different response for each turns
     * this endpoint into an account-enumeration oracle: an attacker wanting to
     * know which emails are registered simply tries logging in with each and
     * reads which error comes back. One outcome, one message, regardless of
     * which half failed.
     *
     * <p>{@code passwordEncoder.matches(raw, stored)} — never {@code equals} —
     * because {@code encode(x)} draws a new random salt every call, so no two
     * hashes of the same password are ever equal. {@code matches} reads the
     * salt and cost back out of the stored hash and re-derives before
     * comparing.
     */
    public User login(LoginRequest request) {
        Optional<User> maybeUser = repository.findByEmail(request.email());

        if (maybeUser.isEmpty()) {
            // Without this, an unregistered email answers FASTER than a
            // registered one with a wrong password, because the real path also
            // pays BCrypt's ~50-100ms. That timing difference is itself a leak
            // — a patient attacker enumerates registered emails by measuring
            // latency alone, never reading a byte of the response body.
            passwordEncoder.matches(request.password(), DUMMY_HASH_FOR_TIMING);
            throw new InvalidCredentialsException();
        }

        User user = maybeUser.get();
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }

        return user;
    }

    /**
     * A syntactically valid BCrypt hash that matches no real password.
     * {@code matches} against this costs the same ~50-100ms as a real
     * comparison, which is the entire point — see {@link #login}.
     */
    private static final String DUMMY_HASH_FOR_TIMING =
            "$2a$10$7EqJtq98hPqEX7fNZaFWoOhi5L4mfP/EMDT1lqGKgnJKpgJH4pOb2";

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

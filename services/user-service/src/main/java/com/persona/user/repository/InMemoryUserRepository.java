package com.persona.user.repository;

import com.persona.user.exception.DuplicateEmailException;
import com.persona.user.exception.UserNotFoundException;
import com.persona.user.model.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores users in a {@link Map}, keyed by email.
 *
 * <p><b>This class is temporary and that is the point.</b> Day-03 replaces it
 * with a PostgreSQL-backed repository. Writing it now, deliberately, buys one
 * thing: every layer above can be built and tested against a working store
 * before any JDBC, any Hibernate, any SQL exists. When Day-03 swaps the
 * implementation, the service and controller should not need a single edit. If
 * they do, the boundary was drawn in the wrong place — and finding that out is
 * cheaper today than after the database is wired in.
 *
 * <p>Notice what this class does <em>not</em> do: it does not validate, it does
 * not hash, it does not decide policy. A repository answers "what is stored?" and
 * nothing else. The duplicate check here is the one judgement call, and it lives
 * here only because uniqueness is a property of the collection — see
 * {@link DuplicateEmailException}.
 *
 * <p>{@code @Repository} does two things. The obvious one: it makes this class a
 * bean, so Spring builds it at startup and hands it to whoever asks. The one worth
 * remembering: it switches on <b>exception translation</b>. From Day-03, a
 * vendor-specific {@code PSQLException} thrown inside here is caught and rethrown
 * as Spring's {@code DataAccessException} before it leaves the class. That is why
 * the annotation is not interchangeable with {@code @Service} even though both
 * merely "make a bean" — swap it and the service layer starts importing
 * {@code org.postgresql}, and the layer whose whole job is not knowing where data
 * lives would then name the vendor.
 *
 * <p>Note that today, in memory, that translation protects nothing. The annotation
 * is correct now and load-bearing later, and the failure from getting it wrong
 * would not appear until Day-03 — which is precisely why it is easy to get wrong.
 */
@Repository
@org.springframework.context.annotation.Profile("test")
public class InMemoryUserRepository implements UserRepository {

    /**
     * Keyed by email because email is identity — the same decision that
     * {@code equals}/{@code hashCode} encode on {@link User}.
     *
     * <p>Why a {@code Map} and not a {@code List}: finding a user by email in a
     * list means walking every element, and the cost grows with the number of
     * users. A hash map goes more or less straight to the entry regardless of
     * size. That difference is invisible with three users and decisive with three
     * million, and it is the same mechanism described in {@link User#hashCode()} —
     * hash to a bucket, then compare within it.
     *
     * <p>{@code ConcurrentHashMap} rather than {@code HashMap} because a web
     * application is multi-threaded from its very first request: Tomcat handles
     * each request on its own thread, all sharing this one object. A plain
     * {@code HashMap} written to by two threads at once can corrupt its internal
     * structure — not merely lose an update, but genuinely break. This does not
     * make the look-then-insert in {@link #save} atomic; nothing about the map
     * choice fixes that. It only prevents the map itself from being damaged.
     */
    private final Map<String, User> usersByEmail = new ConcurrentHashMap<>();

    /**
     * Stores a new user. Rejects an email that is already taken.
     *
     * <p>Returns the saved user rather than {@code void}. It looks pointless today
     * — the caller already has the object. It stops looking pointless on Day-03,
     * when the database generates the id and the returned object is the only place
     * that id exists. Establishing the signature now means the callers written
     * between here and there do not all need editing then.
     */
    public User save(User user) {
        if (usersByEmail.containsKey(user.getEmail())) {
            throw new DuplicateEmailException(user.getEmail());
        }
        usersByEmail.put(user.getEmail(), user);
        return user;
    }

    /**
     * Records changes to an existing user. <b>Day-05.</b>
     *
     * <p>Note this does almost nothing: the map already holds a reference to this
     * exact object, so mutating it through the setters has already changed what
     * is "stored". The {@code put} is therefore redundant in this implementation
     * and is written anyway, because relying on the store and the caller sharing
     * one object is an accident of holding things in memory. The JDBC
     * implementation shares nothing, and a method whose correctness depends on
     * which implementation is active is not an abstraction.
     *
     * <p>The existence check is the point of the method. It is what makes
     * "update something that is not there" a loud failure rather than a silent
     * insert.
     */
    @Override
    public User update(User user) {
        if (!usersByEmail.containsKey(user.getEmail())) {
            throw new UserNotFoundException(user.getEmail());
        }
        usersByEmail.put(user.getEmail(), user);
        return user;
    }



    /**
     * Finds a user, or returns empty.
     *
     * <p>{@code Optional} here, an exception in {@link #getByEmail}, and the
     * difference is the whole design: <b>absence is not always an error.</b>
     * "Is this email already taken?" expects to find nothing most of the time —
     * that is a normal answer, and an exception for a normal answer is control
     * flow disguised as failure. So the repository offers both and lets the caller
     * say which situation it is in.
     */
    public Optional<User> findByEmail(String email) {
        return Optional.ofNullable(usersByEmail.get(email));
    }

    /**
     * Finds a user, or throws.
     *
     * <p>For the case where the user genuinely must exist — loading a profile for
     * a logged-in session, say. The caller that cannot sensibly continue without
     * the user should not be forced to write an {@code if} that it has no answer
     * for.
     */
    public User getByEmail(String email) {
        return findByEmail(email).orElseThrow(() -> new UserNotFoundException(email));
    }

    /**
     * Deletes a user, throwing if there was nothing to delete.
     *
     * <p>{@code Map.remove} returns the old value, or {@code null} if the key was
     * absent — so one call both deletes and reports whether anything happened. The
     * alternative, {@code containsKey} then {@code remove}, touches the map twice
     * and re-opens the same race described in {@link DuplicateEmailException}.
     */
    public void deleteByEmail(String email) {
        if (usersByEmail.remove(email) == null) {
            throw new UserNotFoundException(email);
        }
    }

    /**
     * All stored users.
     *
     * <p>Wrapped in {@code List.copyOf}, which is not decoration. Returning
     * {@code usersByEmail.values()} directly hands the caller a live view of this
     * object's internal state — they could call {@code clear()} on it and empty
     * the repository from the outside. That is the encapsulation from slice 2
     * being undone by a getter, which is the most common way it gets undone.
     */
    public Collection<User> findAll() {
        return List.copyOf(usersByEmail.values());
    }

    /**
     * One page of users, from a {@link ConcurrentHashMap}.
     *
     * <p><b>A map has no order, so one has to be imposed.</b> The real
     * implementations order by {@code id} because {@code LIMIT}/{@code OFFSET}
     * is meaningless without it. This fake must do the same or it is not a
     * substitute: paging an unordered collection returns arbitrary overlapping
     * subsets, and a test written against it would pass while asserting
     * nonsense.
     *
     * <p><b>Ordering by email, not id — and that is a deliberate divergence.</b>
     * In this profile {@code id} is null: nothing assigns one, because no
     * database is involved. Email is the map key, so it is unique and always
     * present. The ordering therefore differs from the JDBC and JPA
     * implementations, which means <em>a test asserting which specific users
     * land on page 2 is only valid against the profile it ran on</em>. What is
     * safely assertable everywhere: page sizes, the total, and that pages do not
     * overlap.
     *
     * <p><b>Two arithmetic traps, both silent.</b> {@code getOffset()} returns a
     * {@code long} while {@code List.subList} takes {@code int}, so a cast is
     * unavoidable; and the offset may be past the end of the list, where
     * {@code subList} throws {@code IndexOutOfBoundsException} rather than
     * returning empty. Both are handled by clamping below — an out-of-range page
     * yields an empty page with the correct total, matching what SQL
     * {@code OFFSET} past the end does.
     *
     * <p><b>The total is taken before slicing</b>, for the same reason as in the
     * JDBC version: {@code totalElements} describes the table, not the page.
     */
    @Override
    public Page<User> findAll(Pageable pageable) {
        List<User> ordered = usersByEmail.values().stream()
                .sorted(Comparator.comparing(User::getEmail))
                .toList();

        int total = ordered.size();
        int from = (int) Math.min(pageable.getOffset(), total);
        int to = Math.min(from + pageable.getPageSize(), total);

        return new PageImpl<>(ordered.subList(from, to), pageable, total);
    }

    public long count() {
        return usersByEmail.size();
    }
}

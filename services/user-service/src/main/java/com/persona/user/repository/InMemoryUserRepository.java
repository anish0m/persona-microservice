package com.persona.user.repository;

import com.persona.user.exception.DuplicateEmailException;
import com.persona.user.exception.UserNotFoundException;
import com.persona.user.model.User;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores users in a {@link Map}, keyed by email. Temporary; PostgreSQL replaces
 * it on Day-03.
 *
 * <p>Its value today is that every layer above can be built and tested against a
 * working store before any SQL exists — and if swapping the implementation on
 * Day-03 forces edits upstairs, the boundary was drawn wrong.
 *
 * <p><b>The honest warning, and it is sharper here than in the monolith.</b> This
 * map lives in one JVM's heap. Run two instances of user-service behind a load
 * balancer — which is the entire point of splitting the service out — and there
 * are now two maps that know nothing about each other. Sign up on instance A, log
 * in on instance B, and you do not exist. The bug is not "the data is lost on
 * restart"; it is that the system is <em>already wrong while running</em>.
 *
 * <p>That is the concrete reason a microservice needs external state almost
 * immediately, while a monolith can limp along on an in-memory store for
 * surprisingly long. Same class, same code, very different expiry date. Worth
 * remembering on Day-17 when the trade-off comes up for real.
 *
 * <p>{@code @Repository} makes this a bean and switches on exception translation,
 * exactly as in the monolith — a vendor {@code PSQLException} becomes Spring's
 * {@code DataAccessException} before it leaves the class. The annotation is not
 * interchangeable with {@code @Service} for that reason, even though both merely
 * "make a bean".
 *
 * <p>Here the stakes are slightly higher than in the monolith. An untranslated
 * database exception escaping this class does not just leak a vendor type into the
 * service layer; it reaches the controller, which must turn it into an HTTP status
 * for a <em>different service</em> to interpret. A leaked {@code PSQLException}
 * usually becomes a 500, and the caller learns only that something broke — the
 * distinction between "not found" and "database unreachable" is lost at the wire,
 * where it cannot be recovered.
 */
@Repository
public class InMemoryUserRepository {

    /**
     * Keyed by email, matching {@code equals}/{@code hashCode} on {@link User}.
     *
     * <p>A {@code Map} rather than a {@code List} because lookup by key does not
     * get slower as the collection grows, whereas scanning a list does. Same
     * hash-to-bucket mechanism described on {@link User#hashCode()}.
     *
     * <p>{@code ConcurrentHashMap} because this single object is shared by every
     * request thread the server runs. A plain {@code HashMap} written concurrently
     * can corrupt its own internal structure, not merely lose an update. It does
     * not make the look-then-insert in {@link #save} atomic — nothing local can,
     * across instances.
     */
    private final Map<String, User> usersByEmail = new ConcurrentHashMap<>();

    /**
     * Stores a new user, rejecting an email that is already taken.
     *
     * <p>Returns the saved user rather than {@code void} — pointless today,
     * necessary on Day-03 when the database assigns the id and the returned object
     * is the only place it exists.
     */
    public User save(User user) {
        if (usersByEmail.containsKey(user.getEmail())) {
            throw new DuplicateEmailException(user.getEmail());
        }
        usersByEmail.put(user.getEmail(), user);
        return user;
    }

    /**
     * Finds a user, or returns empty — because absence is not always an error.
     * "Is this email taken?" expects to find nothing most of the time, and an
     * exception for an expected answer is control flow wearing a disguise.
     */
    public Optional<User> findByEmail(String email) {
        return Optional.ofNullable(usersByEmail.get(email));
    }

    /**
     * Finds a user, or throws — for callers that genuinely cannot continue
     * without one.
     */
    public User getByEmail(String email) {
        return findByEmail(email).orElseThrow(() -> new UserNotFoundException(email));
    }

    /**
     * Deletes a user, throwing if there was nothing to delete.
     *
     * <p>{@code Map.remove} returns the previous value or {@code null}, so one
     * call both deletes and reports. {@code containsKey} then {@code remove}
     * touches the map twice and widens the race.
     */
    public void deleteByEmail(String email) {
        if (usersByEmail.remove(email) == null) {
            throw new UserNotFoundException(email);
        }
    }

    /**
     * All stored users, as a copy.
     *
     * <p>Returning {@code usersByEmail.values()} directly would hand the caller a
     * live view of internal state — they could {@code clear()} it and empty the
     * repository from outside. Encapsulation undone by a getter is the usual way
     * encapsulation gets undone.
     */
    public Collection<User> findAll() {
        return List.copyOf(usersByEmail.values());
    }

    public long count() {
        return usersByEmail.size();
    }
}

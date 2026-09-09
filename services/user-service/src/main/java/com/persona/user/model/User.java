package com.persona.user.model;

import java.util.Optional;

/**
 * A person who can sign up and log in to persona.
 *
 * <p>Field-for-field identical to the monolith's {@code com.persona.model.User}.
 * That is the point of this slice: at Day-00 the two architectures differ only
 * in <em>where a class lives</em>, not in what it contains. The interesting
 * divergence starts later.
 *
 * <p><b>What is already different, and matters:</b> in the monolith this class is
 * shared by every feature — one User, one definition, and any feature can import
 * it. Here it belongs to user-service alone. No other service may import it.
 * When wallet-service needs to know who owns a wallet, it stores a user id and
 * asks over HTTP; it does not get a User object.
 *
 * <p>That sounds like pure loss, and at this size it is. What it buys is that
 * user-service can add, rename or drop a field without recompiling anything
 * else — and in the monolith it cannot. Day-17 is where you get to feel which
 * trade you would actually make.
 *
 * <p>Encapsulation reasoning is the same as the monolith's: private fields, so
 * that every route into this object's state is a method where a rule can live.
 */
public class User {

    /**
     * Identity, and immutable. Same reasoning as the monolith.
     *
     * <p>Extra weight here: in a distributed system this value is also the key
     * other services use to refer to a person. An identity that can change is an
     * identity other services can end up holding a stale copy of, with no
     * compiler to catch it.
     */
    private final String email;

    private String firstName;
    private String lastName;

    /**
     * Plain today, BCrypt hash from Day-06. Named plainly so the Day-06 change is
     * deliberate rather than silent.
     *
     * <p>In this architecture, one more rule: this field must never leave the
     * service. The monolith leaks a password by passing an object to the wrong
     * method; a microservice leaks one by serialising it onto the network, where
     * it may be logged by a gateway or proxy the service does not control.
     */
    private String password;

    /**
     * A profile picture URL, or {@code null} when none is set. The first field
     * allowed to be genuinely absent.
     *
     * <p>Stored nullable, exposed as {@code Optional} — see {@link #getImage()}.
     *
     * <p>Distributed wrinkle: in this architecture the image is likely to live in
     * object storage owned by another service, so this field is a <em>reference</em>
     * to something user-service does not control. The URL being present is not a
     * promise that the image still exists. A monolith can check the file; here you
     * would need a network call, and by the earlier rule that cannot live in a
     * setter.
     */
    private String image;

    /**
     * Delegates to the setters rather than assigning directly, so construction and
     * later mutation run the same validation, written once.
     */
    public User(String email, String firstName, String lastName, String password) {
        requireText(email, "Email");
        this.email = email;
        setFirstName(firstName);
        setLastName(lastName);
        setPassword(password);
    }

    /**
     * The model validates only what it can check <em>using itself</em>: null,
     * blank, length.
     *
     * <p>The boundary bites harder here than in the monolith. A rule like "password
     * cannot be one of your last 5" needs stored history — in the monolith that is
     * a repository import, ugly but local. Here it may be another service entirely,
     * which means a network call: it can be slow, and it can fail. A setter that
     * can hang for 30 seconds or throw a timeout is not a setter anymore.
     *
     * <p>So the same rule applies with more force: if answering it requires leaving
     * this object, it does not belong in this object.
     */
    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be null or blank");
        }
    }

    public String getEmail() {
        return email;
    }

    public String getFirstName() {
        return firstName;
    }

    /**
     * Every field with a constructor rule needs the same rule on its setter, or the
     * rule only applies at birth.
     */
    public void setFirstName(String firstName) {
        requireText(firstName, "First name");
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        requireText(lastName, "Last name");
        this.lastName = lastName;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        requireText(password, "Password");
        this.password = password;
    }

    /**
     * persona's public handle: {@code @firstname-lastname}, lowercased.
     *
     * <p>Derived, not stored. A stored copy is a second place holding the same
     * truth, and {@code setFirstName} would update one and not the other — the
     * object then reports a name and a username that disagree.
     *
     * <p>Sharper here than in the monolith. A stored username would be replicated
     * into other services' local caches, and a stale copy in another service's
     * database cannot be fixed by fixing this class. Derived values do not get
     * replicated; only the inputs do.
     */
    public String getUsername() {
        return "@" + firstName.toLowerCase() + "-" + lastName.toLowerCase();
    }

    /**
     * Returns the image URL, or empty when none is set.
     *
     * <p>{@code Optional} as a return type makes absence part of the signature, so
     * the caller cannot forget the empty case. As a field it would cost an object
     * per User and would not serialise cleanly — which in a microservice matters
     * more, because these objects become JSON on the wire.
     *
     * <p>Rule of thumb: {@code Optional} as a return type, never as a field, never
     * as a parameter.
     */
    public Optional<String> getImage() {
        return Optional.ofNullable(image);
    }

    /**
     * Accepts {@code null} to clear the image; rejects blank. {@code null} means
     * "no image"; {@code ""} would be a second value meaning the same thing, and
     * two representations of one state is exactly the ambiguity to avoid.
     */
    public void setImage(String image) {
        if (image != null && image.isBlank()) {
            throw new IllegalArgumentException("Image cannot be blank — use null to clear it");
        }
        this.image = image;
    }

    /**
     * Excludes the password — logs, stack traces and debugger views must never
     * see a credential.
     */
    @Override
    public String toString() {
        return "User{email='" + email + "', firstName='" + firstName
                + "', lastName='" + lastName + "'}";
    }
}

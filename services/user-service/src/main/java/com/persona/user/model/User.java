package com.persona.user.model;

import java.util.Optional;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A person who can sign up and log in to persona.
 *
 * <p>Encapsulation: every field is {@code private}. Nothing outside this class
 * can reach in and change state directly. The only way in is a constructor or a
 * setter — and a setter is a method, which means it is a place where a rule can
 * later be enforced. A public field has no such place. That is the whole reason
 * for the ceremony; it is not decoration.
 *
 * <h2>Day-04 — this class is now an entity, and that cost something</h2>
 *
 * <p>Until today the class documentation opened by pointing at what was NOT
 * here: no Spring annotation, no {@code @Entity}, no SQL. The model was the one
 * layer that depended on nothing, which was exactly why every other layer was
 * allowed to depend on it. <b>That claim is no longer true</b>, and pretending
 * otherwise would be worse than the change itself.
 *
 * <p>What was actually traded:
 * <ul>
 *   <li><b>{@code email} lost its {@code final}.</b> Hibernate constructs an
 *       entity empty and fills the fields by reflection, so a final field is
 *       officially unsupported — reflection can usually write one, but the JVM
 *       is free to constant-fold a value it believes fixed. "Works until the JIT
 *       optimises it" is the worst failure mode available, so this is not a risk
 *       worth carrying. See {@link #email}.</li>
 *   <li><b>A no-arg constructor exists.</b> {@code protected}, so the framework
 *       can reach it and application code cannot. See {@link #User()}.</li>
 *   <li><b>The class cannot be {@code final}</b>, nor its getters, because lazy
 *       loading hands back a generated subclass.</li>
 * </ul>
 *
 * <p>The honest summary: <b>JPA asks an object to weaken its design so that a
 * framework can populate it.</b> The alternative — a separate {@code UserEntity}
 * mirroring this class, mapped back and forth — keeps the model pristine and
 * costs a duplicate class plus a mapper that will drift. For an application this
 * size that trade is not worth it. On something larger, or a domain with real
 * invariants to protect, it frequently is. What matters is knowing a trade was
 * made, rather than discovering later that the model layer quietly acquired a
 * dependency on Hibernate.
 *
 * <p>What did NOT change: every validation rule, {@code getUsername()} still
 * derived, {@code equals}/{@code hashCode} still on email, {@code toString}
 * still omitting the password. JPA maps state; it has no opinion about behaviour.
 *
 * <h2>And in a microservice, one extra rule</h2>
 *
 * <p>This entity may only describe tables <b>this service owns</b>. That sounds
 * obvious and is the single easiest boundary to destroy, because JPA makes
 * crossing it pleasant: adding {@code @ManyToOne Wallet wallet} here would
 * compile, generate a join, and work perfectly — against a database that, after
 * Day-17, belongs to a different service.
 *
 * <p>The annotation cannot tell you the architecture is wrong. A foreign key is
 * a statement that two tables live in one database; an object reference is a
 * statement that two classes live in one process. In a monolith those are both
 * true and the mapping is free. Here, each one is a decision to merge two
 * services back together, taken one annotation at a time.
 *
 * <p>So the rule for this file, from today onward: <b>entities stop at the
 * service boundary; across it there are ids and HTTP calls, not references.</b>
 * The monolith's copy of this class carries no such warning, and that difference
 * — not the mapping, which is byte-identical — is the real divergence of Day-04.
 */
@Entity
@Table(name = "users")
public class User {

    /**
     * The database's identity for this row — {@code null} until it has been saved.
     *
     * <p><b>Day-03 added this field, and the nullability is the interesting part.</b>
     * A {@code User} the controller has just built from a request is a perfectly
     * valid User that does not exist in any table yet. Only {@code save} can
     * assign an id, because only the database knows what the next one is. So the
     * type must permit "no id yet", and {@code Long} rather than {@code long} is
     * what says that — a primitive {@code long} would default to 0, and 0 is
     * indistinguishable from a real id.
     *
     * <p><b>Why equals() and hashCode() still use email, not this.</b> An obvious
     * reading of "id is the primary key" says identity should compare ids. It
     * cannot, and the reason is the null above: two unsaved users would both have
     * a null id and compare EQUAL, which would collapse them into one entry in any
     * {@code HashSet}. Worse, saving a user would change its hash code while it sat
     * in a collection — the bucket problem from Day-00, with the key moving under
     * the map.
     *
     * <p>So there are genuinely two identities here, and they are not in conflict:
     * {@code id} is what the ROW is, {@code email} is what the PERSON is. Business
     * equality is the person. This is exactly why the table has both a primary key
     * and a unique constraint.
     *
     * <p>There is no setter. Nothing in the application may assign an id — the
     * repository sets it through the package-private {@link #assignId} below, at
     * the one moment it is legitimate.
     *
     * <p><b>Day-04.</b> {@code @GeneratedValue(strategy = IDENTITY)} does not
     * decide anything — it REPEATS what V1 already decided with
     * {@code GENERATED ALWAYS AS IDENTITY}. The annotation and the migration are
     * two statements of one fact, and {@code ddl-auto: validate} is what makes
     * them fail loudly at startup when they disagree.
     *
     * <p>{@code GenerationType.AUTO} is the trap here: it looks database-agnostic
     * and on PostgreSQL it silently picks {@code SEQUENCE}, which this schema does
     * not have. Never leave AUTO on when Flyway owns the DDL.
     *
     * <p>The cost of IDENTITY, worth knowing rather than acting on: Hibernate
     * cannot know the id until the INSERT has run, so it must send every INSERT
     * immediately and <b>batching is disabled for this entity</b>. A hundred
     * users is a hundred round trips. {@code SEQUENCE} fetches ids up front and
     * can batch. It does not matter for a signup table, where inserts arrive one
     * human at a time; it would matter for a high-volume append-only table.
     *
     * <p>And the nullability above is now load-bearing in a second way:
     * {@code save()} is an upsert that dispatches on this field. Null means
     * persist (INSERT), non-null means merge (UPDATE). A primitive {@code long}
     * would make every new object look like an update of row 0.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * When the row was created, in UTC. {@code null} until saved, for the same
     * reason as {@link #id}: the database supplies it via {@code DEFAULT now()}.
     *
     * <p>Letting the database stamp this rather than the application is deliberate.
     * Application servers drift, run in different timezones, and there may be
     * several of them; the database is one clock. For a record of when something
     * happened, one slightly-wrong clock beats several disagreeing ones.
     *
     * <p><b>Day-04 — {@code updatable = false} is "no setter", enforced a second
     * time.</b> This field already had no setter, so application code could not
     * overwrite what the database stamped. That is no longer sufficient on its
     * own: dirty checking compares a managed entity against its load-time
     * snapshot and writes anything that differs, without anyone calling
     * {@code save()}. {@code updatable = false} removes the column from every
     * generated UPDATE permanently. Ownership stated in Java AND in the mapping.
     *
     * <p><b>{@code insertable = false} is the half that was missed first, and the
     * failure is worth keeping.</b> With only {@code nullable = false} the first
     * INSERT failed:
     *
     * <pre>
     * org.hibernate.PropertyValueException: not-null property references a null
     * or transient value for entity com.persona.model.User.createdAt
     * </pre>
     *
     * <p>V1 says {@code created_at TIMESTAMPTZ NOT NULL DEFAULT now()} — the
     * DATABASE supplies this value. But {@code nullable = false} made Hibernate
     * check the field in Java BEFORE sending the statement, and a freshly
     * constructed User has no timestamp yet, so it refused. The default never got
     * the chance to apply. {@link com.persona.user.repository.JdbcUserRepository} never
     * hit this because its hand-written INSERT simply does not name the column.
     *
     * <p>{@code insertable = false} says "omit this column from INSERT entirely",
     * which is precisely what lets {@code DEFAULT now()} do its job. The pair
     * together — neither inserted nor updated — is the mapping for <b>a value this
     * application reads and never writes.</b>
     *
     * <p>The general shape, and it is the recurring one: <b>an annotation is a
     * claim about the schema, and two correct-looking claims can still describe a
     * column nobody is allowed to write.</b> The Java-side check and the SQL-side
     * default were each individually right and jointly wrong.
     *
     * <p>Consequence, deliberately accepted: after {@code save()} the in-memory
     * object's {@code createdAt} is whatever it was before — Hibernate did not
     * send the column, so it does not know what the database chose. Reading it
     * back requires a refresh, which {@link com.persona.user.repository.JpaUserRepository#save}
     * does. Day-03's {@code RETURNING} clause solved the same problem explicitly;
     * here it is a flush plus a refresh.
     */
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private java.time.Instant createdAt;

    /**
     * Email is persona's identity: it is what a person logs in with, and it is
     * what makes two User objects "the same person". Slice 4 builds
     * equals/hashCode on this field for that reason.
     *
     * <p><b>Day-04 — this field WAS {@code final}, and JPA took that away.</b>
     * The reason it was final, from slice 2, remains completely correct: identity
     * must not change, and a mutable field behind {@code hashCode} is a live trap
     * (mutate a key while it sits in a HashMap and the entry becomes permanently
     * unreachable — see {@link #hashCode()}). The compiler enforced that promise
     * for free.
     *
     * <p>Hibernate cannot live with it. It builds the object through the no-arg
     * constructor and assigns fields by reflection; reflection can usually write
     * a final field, but the JVM is entitled to treat a final as a constant and
     * fold it, so the mapping is officially unsupported and fails unpredictably
     * rather than immediately. An unsupported thing that works today is worse
     * than one that fails today.
     *
     * <p><b>What replaces the guarantee.</b> Not nothing, and not merely a
     * comment:
     * <ul>
     *   <li>there is still <b>no setter</b> — the only way to change it is to add
     *       a method, which is a deliberate act, not an accident;</li>
     *   <li>{@code updatable = false} means Hibernate will never write this
     *       column in an UPDATE, so even dirty checking cannot change a stored
     *       email;</li>
     *   <li>{@code UNIQUE} in V1 remains the actual guarantee that two people
     *       cannot share one.</li>
     * </ul>
     *
     * <p>This is the day's trade in one field: <b>a compile-time guarantee
     * downgraded to a runtime one, in exchange for the framework being able to
     * construct the object at all.</b> Worth feeling rather than skipping past —
     * the mapping is not free, and this is what it cost.
     */
    @Column(name = "email", nullable = false, unique = true, length = 255, updatable = false)
    private String email;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    /**
     * Stored as given for now. This is a known, temporary lie: Day-06 replaces it
     * with a BCrypt hash and this field becomes {@code passwordHash}. It is named
     * plainly today so that the change on Day-06 is visible and deliberate rather
     * than silent.
     *
     * <p><b>Day-04 note on mapping being opt-OUT.</b> Nothing here says "persist
     * this" — every non-static, non-transient field is mapped by default. So this
     * field is stored because it was not excluded, not because it was chosen. A
     * field that must never reach the database takes {@code @Transient} (the JPA
     * annotation, meaning "not for the database" — not the Java {@code transient}
     * keyword, which means "not for serialization"). Worth knowing precisely here,
     * because this is the field most likely to be reached for on Day-06.
     */
    @Column(name = "password", nullable = false, length = 255)
    private String password;

    /**
     * A profile picture URL, or {@code null} when the person has not set one.
     *
     * <p>This is the first field that is genuinely allowed to be absent. Email,
     * name and password all have a rule that says "must be there"; this one does
     * not. That difference is real and the type system should carry it — see
     * {@link #getImage()}.
     *
     * <p>Stored as a plain nullable field rather than as {@code Optional<String>}.
     * {@code Optional} is designed as a return type, to force a caller to handle
     * the empty case. As a field it costs an extra object per User, does not
     * serialise cleanly, and adds nothing: the field is private, so the only way
     * anyone reads it is through the getter, which is where the protection needs
     * to be anyway.
     *
     * <p>The one nullable column in the table, so the one {@code @Column} here
     * without {@code nullable = false}. The absence is genuine and the schema,
     * the field and {@link #getImage()}'s {@code Optional} all say the same thing.
     */
    @Column(name = "image", length = 512)
    private String image;

    /**
     * For Hibernate only. <b>Day-04.</b>
     *
     * <p>Reading a row means producing a User <em>before</em> the values are
     * known: Hibernate creates an empty instance and then assigns each field by
     * reflection. It cannot use the constructor below — it has no idea which
     * column maps to which parameter, and that constructor validates, which would
     * re-judge data already stored on its way back out.
     *
     * <p>{@code protected} rather than {@code public} is the whole point. The
     * framework can reach it; application code cannot, so
     * {@code new User()} still does not compile anywhere in this project and "a
     * User without an email is unrepresentable" survives almost intact. Almost:
     * the door exists now, it is just not one the application can open.
     *
     * <p>A private one would work for Hibernate too, but not for the lazy-loading
     * proxy, which is a generated SUBCLASS and therefore needs a constructor it
     * can call via {@code super()}. {@code protected} is the smallest opening
     * that satisfies both.
     */
    protected User() {
        // Deliberately empty. Hibernate assigns the fields directly afterwards.
    }

    /**
     * The only constructor the application may use. There is no PUBLIC no-arg
     * form on purpose: a User without an email is not a meaningful User, and
     * leaving that out makes it unrepresentable rather than merely discouraged.
     *
     * <p>Note it delegates to the setters rather than assigning directly. Both
     * routes into a field — construction and later mutation — then run the same
     * validation, written once. Duplicate the rule instead and Day-06's switch to
     * BCrypt gets applied to one path and forgotten in the other, which lets an
     * unhashed password in through the constructor.
     */
    public User(String email, String firstName, String lastName, String password) {
        requireText(email, "Email");
        this.email = email;
        setFirstName(firstName);
        setLastName(lastName);
        setPassword(password);
    }

    /**
     * Rebuilds a User that already exists in storage. <b>Day-03.</b>
     *
     * <p>There are now two ways to obtain a User, and they are genuinely different
     * events. The constructor above means <em>a new person is signing up</em>: no
     * id, no timestamp, and validation must run because the data came from outside.
     * This one means <em>a row that was already accepted is being read back</em>,
     * and it carries the id and {@code created_at} the database assigned.
     *
     * <p>The alternative — a public {@code setId} — was rejected. A setter is
     * available to everyone forever, so a controller could invent an id, and an
     * invented id is a row pointing at the wrong person. Passing identity through
     * a constructor means it can only be supplied at the one moment it is known,
     * and {@code id} can stay without a setter. Same instinct as the final
     * {@code email}: rather than documenting that something must not happen,
     * arrange for there to be no method with which to do it.
     *
     * <p>Validation still runs. It is tempting to skip it — the row was validated
     * on the way in, so this is wasted work. But "was validated on the way in" is
     * an assumption about every past version of this application and about anyone
     * who has ever held a psql prompt. If the database contains a user with a blank
     * name, the useful moment to find out is on read, not three layers later.
     *
     * <p>Only the repository should call this. Java cannot express that across
     * packages without a module system, so it is stated rather than enforced —
     * which is precisely the weaker kind of protection this class usually avoids,
     * and worth noticing as the exception.
     */
    public User(Long id, String email, String firstName, String lastName,
                String password, String image, java.time.Instant createdAt) {
        this(email, firstName, lastName, password);
        this.id = id;
        this.image = image;
        this.createdAt = createdAt;
    }

    /**
     * The model validates only what it can check <em>using itself</em>: null,
     * blank, length. That is the whole test for whether a rule belongs here.
     *
     * <p>Watch the same rule outgrow this class:
     * <ul>
     *   <li>"at least 8 characters" — fine, self-contained</li>
     *   <li>"cannot equal your email" — needs a sibling field; awkward but possible</li>
     *   <li>"cannot be one of your last 5 passwords" — needs the <b>database</b></li>
     * </ul>
     * The third one cannot live here without the model importing a repository,
     * which destroys the one property that makes this layer safe to depend on.
     * That rule belongs in the service. The split is not about importance; it is
     * about what the object can answer on its own.
     */
    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be null or blank");
        }
    }

    /** The storage id, or {@code null} if this user has never been saved. */
    public Long getId() {
        return id;
    }

    /** When this row was created, or {@code null} if never saved. */
    public java.time.Instant getCreatedAt() {
        return createdAt;
    }

    public String getEmail() {
        return email;
    }

    public String getFirstName() {
        return firstName;
    }

    /**
     * Every field with a constructor rule needs the same rule on its setter, or
     * the rule only applies at birth — the object is protected when created and
     * unprotected for the rest of its life.
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
     * <p>There is no {@code username} field. It is <b>derived</b> — computed from
     * first and last name every time it is asked for. That is a deliberate choice
     * with a specific failure it avoids.
     *
     * <p>Store it as a field instead and there are now two places holding the same
     * truth. {@code setFirstName("Turhan")} updates one and not the other, and the
     * object is quietly lying: {@code getFirstName()} says Turhan while
     * {@code getUsername()} still says the old name. Every stored-derivative bug
     * looks like this. Deriving on read makes the inconsistency <em>unrepresentable</em>
     * rather than merely unlikely.
     *
     * <p>The cost is recomputation on every call. Two string operations. You would
     * only trade back to a stored field with a measurement proving it matters —
     * and then you would need a rule that recomputes it in every setter.
     *
     * <p>No setter, for the same reason: a username is not a thing you set, it is a
     * thing your name implies. To change it, change your name.
     */
    public String getUsername() {
        return "@" + firstName.toLowerCase() + "-" + lastName.toLowerCase();
    }

    /**
     * Returns the image URL, or an empty {@code Optional} when none is set.
     *
     * <p>This is what {@code Optional} is actually for. A plain {@code String}
     * return type tells the caller nothing: they must read the source, or the
     * docs, or find out at runtime via {@code NullPointerException} on
     * {@code user.getImage().length()}. An {@code Optional<String>} return type
     * makes absence part of the signature — the compiler will not let the caller
     * treat it as a String without first deciding what an absent one means.
     *
     * <p>Rule of thumb: {@code Optional} as a return type, never as a field, never
     * as a parameter.
     */
    public Optional<String> getImage() {
        return Optional.ofNullable(image);
    }

    /**
     * Accepts {@code null} — that is how you clear a profile picture — but rejects
     * blank.
     *
     * <p>The distinction matters: {@code null} means "no image", while {@code ""}
     * means "an image whose URL is the empty string", which is not a thing. Two
     * different values representing the same state is exactly the ambiguity
     * {@code Optional} exists to remove, so the setter refuses to create it.
     */
    public void setImage(String image) {
        if (image != null && image.isBlank()) {
            throw new IllegalArgumentException("Image cannot be blank — use null to clear it");
        }
        this.image = image;
    }

    /**
     * Two Users are the same person when they have the same email.
     *
     * <p>Without this method, {@code equals} is inherited from {@code Object},
     * where it means "the same object in memory". Two Users built from the same
     * row of the same database are then <em>not equal</em> — which is not what
     * anybody means by equal, and it is wrong in a way that is easy to miss
     * because nothing fails loudly.
     *
     * <p>Only email is compared, and that is deliberate. Equality here answers
     * "is this the same person?", not "do these two objects hold identical
     * bytes?". A person who changes their profile picture is still the same
     * person. Include every field and you get an equality that says otherwise.
     *
     * <p>Email is the right field precisely because it is {@code final}. Equality
     * built on a mutable field is a trap — see {@link #hashCode()}.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        // getClass() rather than instanceof: a future subclass of User is not
        // interchangeable with a User just because it happens to share an email.
        if (other == null || getClass() != other.getClass()) {
            return false;
        }
        return email.equals(((User) other).email);
    }

    /**
     * Must be overridden whenever {@code equals} is, and must use the same field.
     * This is not a style rule; breaking it breaks every hash-based collection in
     * the JDK.
     *
     * <p>How a {@code HashSet} actually decides membership: it calls
     * {@code hashCode()} to pick a bucket, and only then calls {@code equals} on
     * the few items already in that bucket. Override {@code equals} alone and two
     * equal Users land in <em>different</em> buckets, so {@code equals} is never
     * consulted — {@code set.contains(sameUser)} returns {@code false} while
     * {@code a.equals(b)} returns {@code true}. The object is simultaneously in
     * the set and not findable in it. That is the duplicate-signup bug this slice
     * exists to prevent.
     *
     * <p>The contract is one-directional: equal objects <b>must</b> have equal
     * hash codes; unequal objects <em>may</em> collide, and that is merely slow,
     * not wrong.
     *
     * <p>And here is why the field must be immutable. Put a User in a
     * {@code HashMap}, then change the field the hash is computed from, and the
     * key's bucket is now wrong — the entry is still in the map but permanently
     * unreachable, a genuine memory leak. {@code email} being {@code final} means
     * this cannot happen. That is the payoff for the decision made back in slice 2.
     */
    @Override
    public int hashCode() {
        return email.hashCode();
    }

    /**
     * Deliberately excludes the password. {@code toString} output ends up in log
     * files, stack traces and IDE debugger views — all places a credential must
     * never reach. Forgetting this one method is a genuinely common way real
     * systems leak passwords into logs.
     */
    @Override
    public String toString() {
        return "User{email='" + email + "', firstName='" + firstName
                + "', lastName='" + lastName + "'}";
    }
}

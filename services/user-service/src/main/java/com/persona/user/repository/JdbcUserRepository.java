package com.persona.user.repository;

import com.persona.user.exception.DuplicateEmailException;
import com.persona.user.exception.UserNotFoundException;
import com.persona.user.model.User;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Stores users in PostgreSQL.
 *
 * <p><b>The class Day-01 promised.</b> The in-memory repository's javadoc said
 * that when the store was swapped, "the service and controller should not need a
 * single edit — if they do, the boundary was drawn in the wrong place". This is
 * the test of that claim, and it very nearly passed: the only change required
 * above this package was {@code UserService} naming {@link UserRepository}
 * instead of the concrete in-memory class. No method signature moved, no logic
 * changed, the controller was not opened. That is what the layering was for.
 *
 * <p><b>Why no JPA.</b> Day-04 adds Hibernate, and it will save real work. But an
 * ORM that generates SQL you have never written is a tool you cannot debug: when
 * it emits 400 queries for one page, you need to know what it should have emitted.
 * So today the SQL is visible, typed out, and boring on purpose.
 *
 * <p>{@link JdbcClient} is Spring's modern fluent wrapper over JDBC. Compared to
 * raw {@code java.sql}, it closes connections, handles the {@code PreparedStatement},
 * and converts checked {@link SQLException}s — none of which is the interesting
 * part. The interesting part is that every query here is parameterised.
 */
/*
 * DAY-04 CHANGE — this was @Profile("!test") until a third implementation existed.
 *
 * "Not test" was a perfectly good way to say "the real one" while there was
 * exactly one real one. The moment JpaUserRepository arrived, "!test" and "jpa"
 * both matched outside tests, giving two candidates for one injection point and
 * NoUniqueBeanDefinitionException at startup.
 *
 * Which is the good failure: loud, at boot, naming both beans. Compare Day-03's
 * mirror image, where a MISSING `implements UserRepository` compiled fine and
 * produced NoSuchBeanDefinitionException at startup instead. Too few and too many
 * both fail the same way, and that is the container earning its keep.
 *
 * The lesson is about the negation, not about the fix: @Profile("!x") encodes an
 * assumption about how many alternatives will ever exist. All three now name
 * themselves positively — test, jdbc, jpa — so adding a fourth breaks nothing.
 */
@Repository
@Profile("jdbc")
public class JdbcUserRepository implements UserRepository {

    /**
     * Named once so a column rename is a single edit rather than five. Note it
     * lists columns explicitly instead of {@code SELECT *}: with a star, adding a
     * column to the table silently changes what every query returns, and the order
     * of columns becomes load-bearing. Explicit columns make the query say what it
     * depends on.
     */
    private static final String COLUMNS =
            "id, email, first_name, last_name, password_hash, image, created_at, role";

    private final JdbcClient jdbc;

    public JdbcUserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts a user and returns it with the id and timestamp the database
     * assigned.
     *
     * <p><b>RETURNING is doing real work here.</b> It is a Postgres extension that
     * makes an INSERT return columns from the row it just wrote, so one round trip
     * both writes the row and reports the generated id. The alternative —
     * {@code INSERT} then {@code SELECT} to find out what id was assigned — is two
     * round trips and needs a way to identify the row it just created, which is
     * awkward precisely because the identifier is the thing being fetched.
     *
     * <p><b>Why the duplicate check is a catch, not an if.</b> The obvious shape is
     * {@code if (findByEmail(...).isPresent()) throw ...}. That is a look-then-act:
     * two concurrent signups both look, both find nothing, and both insert. The
     * database's UNIQUE constraint has no such gap — so this method lets the insert
     * happen and translates the failure. <b>Do not ask whether it is allowed; try
     * it, and handle being refused.</b>
     *
     * <p>{@code DuplicateKeyException} is Spring's vendor-neutral exception, not
     * Postgres's {@code PSQLException} with SQLSTATE 23505. That translation is
     * what {@code @Repository} switches on — the annotation whose second purpose
     * looked theoretical on Day-01, load-bearing from this line onward.
     */
    @Override
    public User save(User user) {
        try {
            return jdbc.sql("""
                        INSERT INTO users (email, first_name, last_name, password_hash, image, role)
                        VALUES (?, ?, ?, ?, ?, ?)
                        RETURNING
                        """ + COLUMNS)
                    .params(user.getEmail(), user.getFirstName(), user.getLastName(),
                            user.getPasswordHash(), user.getImage().orElse(null),
                            user.getRole())
                    .query(JdbcUserRepository::mapRow)
                    .single();
        } catch (DuplicateKeyException e) {
            throw new DuplicateEmailException(user.getEmail());
        }
    }

    /**
     * Writes changes to an existing row. <b>Day-05.</b>
     *
     * <p>This is the implementation the {@code update} method exists for. There
     * is no persistence context here and no dirty checking — mutating the Java
     * object changes nothing whatsoever in the database, silently, and the JPA
     * implementation's ability to get away with doing nothing is a property of
     * Hibernate rather than of the design.
     *
     * <p><b>Only three columns appear in the SET clause.</b> Not {@code email},
     * which is identity and is the WHERE clause instead; not
     * {@code password_hash}, which a profile edit must never touch; not
     * {@code id} or {@code created_at}, which belong to the database. The
     * statement cannot express the changes {@link com.persona.user.dto.UpdateProfileRequest}
     * refuses to carry — the same restriction stated a second time, in SQL, at
     * the far end of the call chain.
     *
     * <p>The row count is checked rather than assumed. An UPDATE that matches
     * nothing is a perfectly successful statement in SQL: zero rows changed is
     * not an error to the database, and without this check "update a user who
     * does not exist" would return quietly and look like it worked.
     */
    @Override
    public User update(User user) {
        int rows = jdbc.sql("""
                    UPDATE users
                       SET first_name = ?, last_name = ?, image = ?
                     WHERE email = ?
                    """)
                .params(user.getFirstName(), user.getLastName(),
                        user.getImage().orElse(null), user.getEmail())
                .update();

        if (rows == 0) {
            throw new UserNotFoundException(user.getEmail());
        }
        return getByEmail(user.getEmail());
    }



    /**
     * <p>The {@code ?} is not string formatting. Written as
     * {@code "... WHERE email = '" + email + "'"}, an email of
     * {@code x'; DROP TABLE users; --} is executed as SQL. With a placeholder the
     * value is sent separately from the statement, so the database parses the query
     * first and the value can never be read as code — it is not escaping, it is a
     * different channel.
     *
     * <p>Same shape as the DTO and the {@code @ExceptionHandler}: a boundary where
     * one kind of thing must not be mistaken for another. SQL injection has been
     * the top web vulnerability for two decades, and the fix has been one character
     * the entire time.
     *
     * <p>{@code optional()} returns empty for no rows and throws if more than one
     * comes back. That second behaviour is worth having: two rows for one email
     * would mean the UNIQUE constraint was not doing its job, and silently taking
     * the first would hide it.
     */
    @Override
    public Optional<User> findByEmail(String email) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM users WHERE email = ?")
                .param(email)
                .query(JdbcUserRepository::mapRow)
                .optional();
    }

    @Override
    public User getByEmail(String email) {
        return findByEmail(email).orElseThrow(() -> new UserNotFoundException(email));
    }

    /**
     * <p>{@code update()} returns the number of rows affected, so one statement
     * both deletes and reports whether anything was there — no {@code SELECT} first,
     * and therefore no gap between checking and acting. The same reasoning as
     * {@code Map.remove} returning the old value, and the same reasoning as
     * {@code save} catching rather than asking.
     */
    @Override
    public void deleteByEmail(String email) {
        int rows = jdbc.sql("DELETE FROM users WHERE email = ?")
                .param(email)
                .update();
        if (rows == 0) {
            throw new UserNotFoundException(email);
        }
    }

    /**
     * <p>ORDER BY is not decoration. Without it SQL makes <b>no guarantee whatsoever</b>
     * about row order — it may match insertion order for a while and then change
     * when the planner picks a different access path, which is a bug that appears
     * in production and cannot be reproduced locally.
     *
     * <p>This method also has a real flaw: it loads every user into memory. Fine at
     * a hundred, fatal at a million, and the failure arrives gradually. Day-07
     * replaces it with pagination — the sorted, stable order this line establishes
     * is a precondition for that, because paging through an unordered result can
     * show the same row twice and skip another.
     */
    @Override
    public Collection<User> findAll() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM users ORDER BY id")
                .query(JdbcUserRepository::mapRow)
                .list();
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>This method is the reason the JDBC profile is worth keeping.</b> The
     * JPA implementation of the same thing is one delegated line, which makes
     * {@code Page} look like a free gift. Here every part of it has to be typed
     * out, and what appears is that <b>one page costs two queries</b>:
     *
     * <pre>
     *   1.  SELECT ... ORDER BY id LIMIT ? OFFSET ?     the rows
     *   2.  SELECT COUNT(*) FROM users                  the total
     * </pre>
     *
     * Spring Data was always issuing both. It simply never said so.
     *
     * <p><b>ORDER BY is not decoration — it is required for correctness.</b>
     * {@code LIMIT}/{@code OFFSET} without {@code ORDER BY} is undefined in
     * PostgreSQL: the planner may return rows in any order, so "skip 20 take 10"
     * can hand back rows already seen on page 1 and never show others at all.
     * Ordering by {@code id} is chosen because it is unique; ordering by a
     * non-unique column (say {@code last_name}) would need a tiebreaker for the
     * same reason.
     *
     * <p><b>The count is taken separately and deliberately not inside the first
     * query.</b> A windowed {@code COUNT(*) OVER ()} would fold both into one
     * round trip, but it computes the count from the full result set before
     * limiting, and here it would return the count of the <em>page</em> rather
     * than of the table unless written carefully. Two plain statements are
     * slower and obviously correct, which at this scale is the better trade.
     *
     * <p><b>Why the count comes second.</b> It is the total <em>before</em>
     * paging, so it must not inherit {@code LIMIT}. Getting this backwards —
     * counting the returned rows — yields a {@code totalElements} that equals
     * the page size, and {@code getTotalPages()} then confidently reports 1.
     * Nothing throws; the number is simply wrong.
     *
     * <p><b>No {@code @Transactional} here, and that is a real gap.</b> The two
     * statements run in separate auto-commit transactions, so a row inserted
     * between them produces a page of 10 with a total of 11 — internally
     * inconsistent by a hair. Annotating the method would close it. It is left
     * open to match the rest of this class, which is unannotated throughout, and
     * flagged rather than hidden.
     */
    @Override
    public Page<User> findAll(Pageable pageable) {
        List<User> rows = jdbc.sql(
                        "SELECT " + COLUMNS + " FROM users ORDER BY id LIMIT ? OFFSET ?")
                .params(pageable.getPageSize(), pageable.getOffset())
                .query(JdbcUserRepository::mapRow)
                .list();

        long total = count();

        return new PageImpl<>(rows, pageable, total);
    }

    /**
     * <p>{@code COUNT(*)} is computed by the database over the index; the row data
     * never leaves it. Counting in Java would mean transferring every row across
     * the network to call {@code .size()} on the result — the general rule being
     * to send the question to the data rather than the data to the question.
     */

    /**
     * <p>{@code COUNT(*)} is computed by the database over the index; the row data
     * never leaves it. Counting in Java would mean transferring every row across
     * the network to call {@code .size()} on the result — the general rule being
     * to send the question to the data rather than the data to the question.
     */
    @Override
    public long count() {
        return jdbc.sql("SELECT COUNT(*) FROM users")
                .query(Long.class)
                .single();
    }

    /**
     * One row of the result set, as a {@link User}.
     *
     * <p>This method is the entire object-relational mapping layer, written by hand.
     * Worth reading closely today, because on Day-04 {@code @Entity} makes it
     * disappear and it is much easier to trust something you have already written
     * once yourself.
     *
     * <p>Note the column names are {@code snake_case} while the Java is
     * {@code camelCase}. Nothing bridges those automatically here — this method is
     * the bridge. That mismatch is one of the things JPA handles by convention,
     * and one of the things that makes JPA feel like magic until you have seen the
     * mapping it replaces.
     *
     * <p><b>Reading the timestamp correctly took two attempts.</b> The obvious
     * {@code getObject("created_at", Instant.class)} is rejected by the driver:
     * <em>conversion to class java.time.Instant from timestamptz not supported</em>.
     * The reason is that an {@code Instant} is a point on the timeline with no
     * offset attached, so the driver will not perform a conversion that has to
     * invent one. {@link java.time.OffsetDateTime} is what a {@code timestamptz}
     * genuinely is, and {@code toInstant()} then discards the offset explicitly —
     * the conversion happens in this code, where it is visible, rather than
     * silently inside a driver.
     *
     * <p>What is deliberately NOT used is {@code getTimestamp}, which returns a
     * legacy {@code java.sql.Timestamp} carrying the JVM's default timezone and is
     * therefore capable of shifting a stored UTC instant by hours depending on
     * where the server runs. Avoiding exactly that was the point of TIMESTAMPTZ.
     */
    private static User mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new User(
                rs.getLong("id"),
                rs.getString("email"),
                rs.getString("first_name"),
                rs.getString("last_name"),
                rs.getString("password_hash"),
                rs.getString("image"),
                rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant(),
                // Read explicitly rather than letting the six-argument
                // constructor default it to "USER" — relying on that default
                // here would read every admin back as a normal user.
                rs.getString("role"));
    }
}

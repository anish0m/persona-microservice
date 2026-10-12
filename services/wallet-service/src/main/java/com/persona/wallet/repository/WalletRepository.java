package com.persona.wallet.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.persona.wallet.model.Wallet;

/**
 * Storage for wallets. Day-07.
 *
 * <p>One Spring Data interface, no adapter, for the reasons argued in the
 * monolith's copy of this file: the four-implementation pattern on the user
 * side exists to teach a comparison, and a pattern that exists to teach
 * something has done its job once.
 *
 * <h2>findByUserId is the method the split made important</h2>
 *
 * <p>In the monolith this was a convenience — {@code Wallet} held a
 * {@code User} reference, so an owner was always one dereference away, and
 * this query merely avoided loading the user first.
 *
 * <p>Here it is <b>the only way in.</b> There is no {@code user} association to
 * navigate, no join to write, and no way to ask the database anything about a
 * user at all. Every request that reaches this service carries a user id
 * extracted from a verified JWT, and this method turns that number into a row.
 * The entire read path of wallet-service is this one query.
 *
 * <p>Which is worth noticing as a property of good service boundaries rather
 * than an accident: <b>a service whose data is reachable by one key is a
 * service that was cut in the right place.</b> If wallet-service needed to
 * query by email, or by a user's signup date, the boundary would be wrong —
 * those are user-service's facts, and needing them here would mean an HTTP
 * call on every read.
 */
public interface WalletRepository extends JpaRepository<Wallet, Long> {

    /**
     * The wallet belonging to a user id.
     *
     * <p><b>At most one is still a guarantee</b>, because {@code UNIQUE} on
     * {@code wallets.user_id} survived the split — it is a statement about this
     * table alone and needs no knowledge of the users table. The half of the
     * old foreign key that did NOT survive is "this id refers to a real user",
     * which nothing here can check.
     *
     * <p>{@code Optional} rather than {@code Wallet} because absence is a
     * normal answer in this build in a way it is not in the monolith: a user
     * can legitimately exist with no wallet during the window between
     * user-service committing and this service being called. See
     * {@link com.persona.wallet.exception.WalletNotFoundException}.
     */
    Optional<Wallet> findByUserId(Long userId);

    /** Whether a user already has a wallet. Generates a COUNT, not a SELECT *. */
    boolean existsByUserId(Long userId);
}

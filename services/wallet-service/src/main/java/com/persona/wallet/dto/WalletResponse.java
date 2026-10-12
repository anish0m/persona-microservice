package com.persona.wallet.dto;

import java.time.Instant;

import com.persona.wallet.model.Wallet;
import com.persona.wallet.model.WalletStatus;

/**
 * What persona sends back when asked about a wallet. Day-07.
 *
 * <h2>The balance is a String, and this is the point of the record</h2>
 *
 * <p>Every other decision today protects the exactness of the balance —
 * {@code NUMERIC(19,4)} in the schema, {@code BigDecimal} in the entity,
 * {@code precision}/{@code scale} matching so a mismatch fails the boot. All of
 * it is undone at the last inch if the number leaves as a JSON number:
 *
 * <pre>
 * Postgres NUMERIC(19,4) ──► BigDecimal ──► Jackson ──► JSON ──► JavaScript
 *       1.1000        exact      1.1000       ???                  double
 * </pre>
 *
 * <p>Two separate losses hide in that last arrow. Jackson serialises a
 * {@code BigDecimal} as a JSON number by default, so {@code 1.1000} is written
 * as {@code 1.1} and the scale is gone — a client cannot tell whether the
 * system tracks four decimal places or one. Worse, <b>JSON numbers are parsed
 * into IEEE-754 doubles by every JavaScript client in existence</b>, which
 * hands the value to the exact type the entire day was spent avoiding, after
 * the database, Hibernate and the service layer all got it right.
 *
 * <p>So money leaves as a string: {@code "balance": "1.1000"}, never
 * {@code "balance": 1.1}. This is not a persona quirk — Stripe sends integer
 * minor units, PayPal sends strings. Nobody serious sends a JSON float for
 * money.
 *
 * <p>{@code toPlainString()} rather than {@code toString()} because
 * {@code toString()} may emit scientific notation ({@code 1E+2}) at some
 * scales. Out of range for persona, and it costs nothing to be exact.
 *
 * <p>Note the companion setting in {@code application.properties},
 * {@code spring.jackson.generator.write-bigdecimal-as-plain=true}. It is
 * belt-and-braces: this record already converts explicitly, but the property
 * catches any {@code BigDecimal} that reaches Jackson by some other route — a
 * future DTO where someone forgets. The explicit conversion is the mechanism;
 * the property is the backstop. Same shape as the Java check and the database
 * CHECK constraint on the balance itself.
 *
 * <h2>Why userId IS here, where the monolith's version omits the owner</h2>
 *
 * <p>The monolith deliberately has no owner field: serialising it would mean
 * traversing a LAZY {@code @OneToOne}, which outside a transaction throws
 * {@code LazyInitializationException} and inside one costs an extra query for
 * fields the client did not ask for.
 *
 * <p>Here there is no association to traverse — {@code userId} is already a
 * plain column on this row, so exposing it costs nothing. It is also more
 * useful: in a distributed system the caller may be another service
 * correlating this response against its own records, and the id is the only
 * identifier the two sides share. <b>What was an expensive navigation in the
 * monolith is a free field here; the same split that removed the convenience
 * removed the cost.</b>
 */
public record WalletResponse(
        Long id,

        /*
         * The owner. See the class comment — present here and absent in the
         * monolith, for reasons that are about the architecture rather than
         * about taste.
         */
        Long userId,

        /*
         * A String. See the class comment — this single type choice is what
         * keeps four decimal places alive across the wire, and the reason it
         * looks odd is exactly why it needs the comment.
         */
        String balance,

        /*
         * The enum serialises to its NAME ("ACTIVE"), because Jackson's default
         * for an enum is Enum.name(). That is the right default here and worth
         * noticing as the mirror of the @Enumerated decision: the database
         * stores the name rather than the ordinal, and so does the wire. A
         * client that switches on "ACTIVE" keeps working when a fourth state is
         * added; one that switches on 0 does not.
         */
        WalletStatus status,

        Instant createdAt) {

    /**
     * The one place a {@code Wallet} becomes a {@code WalletResponse}.
     *
     * <p>A static factory on the DTO rather than a method on {@code Wallet},
     * because the model must not know a web layer exists. The arrows point one
     * way: the DTO may depend on the model; the model may not depend on the
     * DTO. Same rule as the user-service DTOs.
     */
    public static WalletResponse from(Wallet wallet) {
        return new WalletResponse(
                wallet.getId(),
                wallet.getUserId(),
                wallet.getBalance().toPlainString(),
                wallet.getStatus(),
                wallet.getCreatedAt());
    }
}

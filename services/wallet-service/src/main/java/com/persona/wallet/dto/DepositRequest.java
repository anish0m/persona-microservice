package com.persona.wallet.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

/**
 * Adding dummy money to your own wallet. Day-07.
 *
 * <h2>Why BigDecimal and not String on the way IN</h2>
 *
 * <p>{@link WalletResponse} sends the balance out as a String, so the obvious
 * symmetry would be to accept one here too. It is the wrong move, and the
 * asymmetry is deliberate.
 *
 * <p>Inbound and outbound have different problems. Outbound, the danger is the
 * CLIENT's parser turning an exact number into a double — persona cannot
 * control that, so it sends a string the client must parse deliberately.
 * Inbound, Jackson parses the JSON token into a {@code BigDecimal} directly
 * from its textual representation, never routing through a double, so
 * exactness is preserved. Accepting a String here would mean parsing it by
 * hand and inventing an error message for malformed input that
 * {@code @NotNull} plus Jackson already produce for free.
 *
 * <p>Worth being precise, because it sounds contradictory: <b>the risk is not
 * "BigDecimal in a DTO", it is "a JSON number parsed by JavaScript".</b> The
 * inbound side has no JavaScript parser in the path.
 *
 * <h2>Three constraints, three different failures</h2>
 *
 * <p>{@code Wallet.credit} already rejects a non-positive amount, so these
 * annotations are not what makes the system correct. They exist so the failure
 * is a <b>400 with a field name</b> rather than a 500 from an
 * {@code IllegalArgumentException} escaping the entity. Same relationship as
 * everywhere else in this project: validation buys the error message, the
 * invariant buys the safety.
 */
public record DepositRequest(

        /*
         * @NotNull rather than @NotBlank — this is a number, and a missing
         * field is the only "empty" a BigDecimal has.
         *
         * Without this, a body of {} yields amount == null, which reaches
         * Wallet.credit and throws IllegalArgumentException: a 500 for what is
         * plainly a client error.
         */
        @NotNull(message = "amount is required")

        /*
         * inclusive = false makes this strictly greater than zero.
         *
         * A zero deposit is not harmful but it is meaningless, and accepting
         * meaningless operations means they appear in the transaction history
         * of a later day, where someone has to decide what they mean. Refusing
         * at the edge is cheaper than explaining at the centre.
         *
         * Negative is the one that matters: without this, a deposit of -100 is
         * a withdrawal with no authorization check on it. The entity would
         * catch it, but an input that could drain an account should never get
         * as far as relying on a downstream guard.
         */
        @DecimalMin(value = "0.00", inclusive = false, message = "amount must be positive")

        /*
         * An upper bound, because "dummy money" with no ceiling is a way to
         * create a balance that overflows NUMERIC(19,4) at some later sum.
         * 19 digits with 4 after the point leaves 15 before it; this cap is far
         * below that and is a policy number, not a technical limit.
         */
        @DecimalMax(value = "1000000.00", message = "amount may not exceed 1000000.00")

        /*
         * THE ONE THAT IS EASY TO FORGET.
         *
         * The column is NUMERIC(19,4), so a request for 0.00001 has more
         * precision than the database can store. Postgres does not reject it —
         * it ROUNDS, silently, and the user is credited a different amount than
         * they asked for with a 200 response saying it worked.
         *
         * Money that silently becomes a different amount is the exact failure
         * the whole day is built to prevent, and it would slip in here, past
         * BigDecimal, past NUMERIC, through the one gap neither of them
         * watches. fraction = 2 is stricter than the column's 4 on purpose:
         * users deposit currency, and the extra two decimal places exist for
         * intermediate arithmetic, not for input.
         */
        @Digits(integer = 15, fraction = 2,
                message = "amount may have at most 2 decimal places")
        BigDecimal amount) {
}

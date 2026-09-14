package com.persona.user.dto;

/**
 * What a signup request is allowed to contain.
 *
 * <p><b>Why not just take a {@code User}?</b> Because {@code @RequestBody User}
 * means "take whatever JSON arrives and set the matching fields on my domain
 * object". Today {@link com.persona.user.model.User} has four fields and that looks
 * harmless. The day someone adds {@code boolean admin} or {@code BigDecimal
 * balance}, every client on the internet can set it by adding one line to their
 * JSON — and nothing in the codebase changed to make that true. That is
 * <b>mass assignment</b>; GitHub was compromised by exactly it in 2012.
 *
 * <p>This record is the answer: the fields that may cross the wire are listed
 * here, explicitly, and adding a field to the model does not add it to the API.
 * The API surface becomes a decision rather than a side effect.
 *
 * <p>A {@code record} rather than a class because that is precisely what this is
 * — a name for a group of values, with no behaviour. Java generates the
 * constructor, the accessors, {@code equals}, {@code hashCode} and
 * {@code toString}, and the fields are final. Jackson understands records
 * natively.
 *
 * <p>Note the field order matches the {@code User} constructor. That is a
 * convenience for the mapping below, not a requirement — JSON is matched by
 * name, not position.
 */
public record CreateUserRequest(
        String email,
        String firstName,
        String lastName,
        String password) {
}

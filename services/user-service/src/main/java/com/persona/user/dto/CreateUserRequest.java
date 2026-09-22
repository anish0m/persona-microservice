package com.persona.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

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
 * <h2>Day-05 — the constraints, and why they are not duplication</h2>
 *
 * <p>{@link com.persona.model.User} already validates every one of these fields
 * through {@code requireText}. Adding the annotations here looks like saying the
 * same thing twice, and deleting either one causes a real defect, so the
 * distinction is worth stating precisely.
 *
 * <ul>
 *   <li><b>These annotations ask: did the caller send something usable?</b> The
 *       answer is 400, they run once at the HTTP edge, and they are removed by
 *       deleting a line.</li>
 *   <li><b>{@code requireText} asks: can this object exist in this state at
 *       all?</b> The answer is {@code IllegalArgumentException} — a bug, not a
 *       bad request — it runs on every construction and every setter forever,
 *       and no caller can bypass it.</li>
 * </ul>
 *
 * <p>Delete these and the caller still gets a 400, because the model throws and
 * the controller maps it. What is lost is the <em>message</em>. A hand-written
 * guard is a {@code throw}, and a throw ends the method, so it reports the first
 * failure only: the caller fixes one field, resubmits, and finds the next. Bean
 * validation collects every violation before failing and reports all of them at
 * once. <b>Fail-fast is right for a bug; fail-completely is right for a form.</b>
 *
 * <p>Delete the model's guard instead and the only check lives on the HTTP
 * boundary — and a Day-13 scheduler or a Day-14 Kafka consumer does not go
 * through a controller. There is no {@code @Valid} anywhere on those paths.
 *
 * <p>This is the third appearance of policy versus guarantee. Day-01: a service
 * duplicate check with nothing behind it. Day-03: the same check, backed by a
 * {@code UNIQUE} constraint. Day-05: these annotations, backed by the model.
 * <b>Never let the only copy of a correctness guarantee live at a boundary</b> —
 * boundaries get added, bypassed and refactored.
 *
 * <p><b>The annotations do nothing on their own.</b> They are inert metadata
 * until two things are both true: {@code spring-boot-starter-validation} is on
 * the classpath, and {@code @Valid} is on the controller parameter. Miss either
 * and there is no error, no log line, a green build, and no validation — the
 * same shape as Day-03's Flyway module and Day-04's misplaced properties.
 * {@code UserControllerTest} proves it is running by making it fail on purpose.
 *
 * <p><b>And a microservice cannot delegate this upward.</b> The tempting thought
 * is that the API Gateway will validate, so this is duplicated effort. It is
 * not: the gateway is one caller. Another service calling this one directly, a
 * Day-14 Kafka consumer, and a developer with curl all bypass it. A service
 * that trusts its callers to have validated has moved its correctness into
 * somebody else's deployment.
 */
public record CreateUserRequest(

        /*
         * @Email is a SHAPE check, not an existence check: "a@b" passes it. It
         * says an address looks like an address; only sending mail says it is
         * one. Worth knowing exactly what has been proven, because the natural
         * reading of a green validation is stronger than the truth.
         *
         * @NotBlank as well as @Email, because @Email alone accepts the empty
         * string — an unfortunate but deliberate part of the spec, on the
         * grounds that "absent" is @NotNull's job. Two annotations, two claims.
         */
        @NotBlank(message = "Email is required")
        @Email(message = "must be a valid email address")
        String email,

        /*
         * @NotBlank rather than @NotNull or @NotEmpty, and the three are not
         * interchangeable:
         *
         *   @NotNull   rejects null only          -> "" passes. Rarely wanted.
         *   @NotEmpty  rejects null and ""        -> "   " passes. The trap.
         *   @NotBlank  rejects null, "" and "   " -> what a name actually needs.
         *
         * @Size mirrors VARCHAR(100) in V1. Without it, a 200-character name
         * passes validation, reaches the INSERT, and fails as a driver-level
         * error — a 500 for what is unambiguously the caller's mistake. The
         * constraint is still the guarantee; this makes the failure arrive at
         * the right layer wearing the right status code.
         */
        @NotBlank(message = "First name is required")
        @Size(max = 100, message = "must be at most 100 characters")
        String firstName,

        @NotBlank(message = "Last name is required")
        @Size(max = 100, message = "must be at most 100 characters")
        String lastName,

        /*
         * The only field whose constraint is a POLICY rather than a description
         * of the column. The stored value is a 60-character hash regardless of
         * how long the input was, so `max = 72` is not about storage.
         *
         * It is about BCrypt: the algorithm silently TRUNCATES input beyond 72
         * bytes. Without this bound, two different long passwords sharing their
         * first 72 bytes both authenticate — a silent weakening with no error
         * anywhere, which is this project's least favourite kind of bug.
         *
         * min = 8 is a deliberately weak rule, and stating it as weak matters
         * more than the number. Length alone is poor evidence of strength
         * ("password" is eight characters). Real policy — breach-list checks,
         * rate limiting, MFA — is Day-06. This is a floor, not a claim.
         */
        @NotBlank(message = "Password is required")
        @Size(min = 8, max = 72, message = "must be between 8 and 72 characters")
        String password) {
}

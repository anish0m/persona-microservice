package com.persona.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * What a profile edit is allowed to change.
 *
 * <h2>Why this record exists at all</h2>
 *
 * <p>The version every tutorial writes is {@code @RequestBody User}, and here it
 * is a security hole rather than a matter of taste. {@link
 * com.persona.user.model.User} has seven fields, so that signature accepts a body
 * which sets all seven:
 *
 * <pre>
 * PUT /users/khi0ne&#64;example.com
 * { "firstName": "Turhan",
 *   "passwordHash": "whatever-I-want",     &lt;- account takeover
 *   "email":        "attacker&#64;evil.com",   &lt;- identity theft
 *   "id":           1 }                    &lt;- row targeting
 * </pre>
 *
 * <p>Changing a display name has become a password reset endpoint. Nobody
 * decided that; it fell out of the parameter type. This is <b>mass
 * assignment</b>, which {@link CreateUserRequest} already defeated once — but
 * that fix was per-endpoint rather than permanent, and this is the same attack
 * against the endpoint that did not exist yet.
 *
 * <h2>Defend by typing, not by checking</h2>
 *
 * <p>The fields below are not validated-out. They are <b>absent</b>:
 *
 * <pre>
 *   defend by CHECKING                  defend by TYPING
 *   ------------------                  ----------------
 *   if (body.email() != null) throw     the field does not exist
 *   one forgotten branch is a hole      there is nothing to forget
 *   a new developer can get it wrong    the compiler enforces it
 * </pre>
 *
 * <p>The same move as {@code Optional<String> image}, as {@code email} having no
 * setter, and as {@link UserResponse} excluding the hash by not declaring it.
 * <b>Make it unrepresentable, not merely unlikely.</b>
 *
 * <h2>Why email in particular cannot be here</h2>
 *
 * <p>Settled long before today, in three independent places. {@code email} has
 * had no setter since slice 2. {@code equals}/{@code hashCode} are built on it,
 * so mutating it while the object sits in a {@code HashSet} loses the entry in
 * the wrong bucket — the memory leak from slice 4. And Day-04 mapped it
 * {@code @Column(updatable = false)}, so Hibernate would refuse in any case.
 *
 * <p>Changing a first name means <em>the same person, new name</em>. Changing an
 * email means <em>a different person</em>. If it must ever change, that is a
 * deliberate operation with its own endpoint, its own verification that the new
 * address exists, and its own audit trail. Not a side effect of editing a
 * display name.
 *
 * <h2>Why PUT and not PATCH</h2>
 *
 * <p>This record carries <b>all three</b> editable fields, so sending it replaces
 * the complete editable state — which is what PUT means, and two identical PUTs
 * leave the same result, as Day-02 required of an idempotent method.
 *
 * <p>PATCH would need "field absent" (leave unchanged) to be distinguishable
 * from "field null" (clear it), and JSON does not express that without
 * {@code Optional} fields or {@code JsonNullable}. Real complexity for no
 * benefit at three fields.
 *
 * <p><b>The consequence must be understood rather than discovered:</b> with PUT,
 * omitting {@code image} clears the image. That is not a bug, it is what
 * "complete new state" means — but the caller has to know it, and Day-09's
 * Bootstrap form must send the current image back rather than omitting it.
 */
public record UpdateProfileRequest(

        @NotBlank(message = "First name is required")
        @Size(max = 100, message = "must be at most 100 characters")
        String firstName,

        @NotBlank(message = "Last name is required")
        @Size(max = 100, message = "must be at most 100 characters")
        String lastName,

        /*
         * The one field with NO @NotBlank, and the omission is the point.
         *
         * Absence is meaningful here: null means "this person has no avatar",
         * which is a legitimate state the column has allowed since V1 and the
         * model expresses as Optional<String>. Every other field in this record
         * has a rule saying "must be there". This one does not, and the
         * annotations say so by not being written.
         *
         * @Size still applies when a value IS present — it mirrors
         * VARCHAR(512). A constraint on a nullable field is not a contradiction:
         * it says "if there is one, it must fit", which is exactly the rule.
         */
        @Size(max = 512, message = "must be at most 512 characters")
        String image) {
}

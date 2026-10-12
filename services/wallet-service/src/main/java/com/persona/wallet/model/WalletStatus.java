package com.persona.wallet.model;

/**
 * What a wallet is currently allowed to do.
 *
 * <pre>
 *       ACTIVE ──────freeze──────► FROZEN
 *         │   ◄──────unfreeze──────┘
 *         │                        │
 *         └────────close───────────┴──────► CLOSED  (terminal)
 * </pre>
 *
 * <table border="1">
 *   <caption>The rules this enum encodes</caption>
 *   <tr><th>Status</th><th>Receive</th><th>Send</th><th>Read balance</th></tr>
 *   <tr><td>ACTIVE</td><td>yes</td><td>yes</td><td>yes</td></tr>
 *   <tr><td>FROZEN</td><td>no</td><td>no</td><td>yes</td></tr>
 *   <tr><td>CLOSED</td><td>no</td><td>no</td><td>yes</td></tr>
 * </table>
 *
 * <h2>Three decisions made deliberately rather than by default</h2>
 *
 * <ul>
 *   <li><b>Reading is always allowed.</b> A frozen user must still be able to
 *       see their own money. Hiding the balance from someone whose account has
 *       been frozen turns a support ticket into a complaint to a regulator.</li>
 *   <li><b>CLOSED is terminal.</b> Not because a transition out is technically
 *       impossible, but because "reopen" is a different operation with
 *       different rules — re-verification, new agreements. Modelling it as a
 *       simple state flip invites someone to write that flip. Same instinct as
 *       the schema refusing {@code ON DELETE CASCADE}.</li>
 *   <li><b>FROZEN blocks receiving too.</b> The tempting half-measure is to
 *       block only outgoing money — "we are protecting them, not punishing
 *       them". But a wallet is usually frozen because of a fraud
 *       investigation, and accepting deposits into a wallet under
 *       investigation means accepting money that may have to be returned, from
 *       a sender who has already lost it. Blocking both directions is what
 *       freeze means.</li>
 * </ul>
 *
 * <h2>Why this is persisted with {@code @Enumerated(EnumType.STRING)}</h2>
 *
 * <p>Stated here as well as on the field, because the trap belongs to the enum
 * rather than to any one mapping of it.
 *
 * <p><b>Day-07 note — this file is byte-identical to the monolith's apart from
 * the package line.</b> That is not laziness; it is information. A wallet's
 * status rules are pure domain logic with no dependency on anything outside
 * this enum, so the service boundary costs them nothing. Compare
 * {@code Wallet}, where the same split removes a foreign key, an association
 * and a transaction. <b>What survives a split unchanged tells you what was
 * genuinely self-contained all along.</b>
 *
 * <p>{@code @Enumerated} with no argument defaults to <b>ORDINAL</b>, which
 * stores the constant's INDEX: {@code ACTIVE}=0, {@code FROZEN}=1,
 * {@code CLOSED}=2. The database then holds integers whose meaning lives
 * nowhere but the source-file ordering of this file. Someone sorts the
 * constants alphabetically, or inserts a new state in the middle:
 *
 * <pre>
 *     public enum WalletStatus { ACTIVE, CLOSED, FROZEN }   // reordered
 * </pre>
 *
 * <p>Nothing fails. Nothing warns. Every frozen wallet in production has just
 * become closed, and every closed wallet has become frozen. No migration ran;
 * no code changed behaviour. The existing rows were <em>reinterpreted</em>.
 *
 * <p>Worth separating this from the day's other trap, because they fail in
 * opposite ways. Using {@code double} for money is obviously wrong the moment
 * it is seen. ORDINAL produces correct-looking output <em>indefinitely</em> —
 * 0, 1 and 2 are valid values, the application works, the tests pass — and
 * becomes wrong RETROACTIVELY, triggered by an unrelated edit, with no error at
 * any point.
 *
 * <p><b>Rule: every {@code @Enumerated} is {@code EnumType.STRING}.</b> There
 * is no good reason for ORDINAL in a schema intended to be kept. The few bytes
 * saved are not a real saving.
 */
public enum WalletStatus {

    /** Normal. Money may move in and out. */
    ACTIVE,

    /**
     * Suspended — typically pending an investigation. Money may not move in
     * either direction; the balance remains visible to its owner.
     */
    FROZEN,

    /**
     * Permanently shut. Terminal: there is deliberately no transition out of
     * this state. The balance remains visible so the record stays auditable.
     */
    CLOSED;

    /**
     * Whether money may move in or out right now.
     *
     * <p>A method rather than {@code status == ACTIVE} scattered at every call
     * site: when a fourth state arrives — {@code PENDING_VERIFICATION}, say —
     * the decision about whether it can transact is made HERE, once, instead of
     * being missed at one of the places that compared against a constant.
     */
    public boolean allowsMovement() {
        return this == ACTIVE;
    }
}

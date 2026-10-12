-- ===========================================================================
--  V1 — the wallet. Day-07. wallet-service's OWN schema, in its OWN database.
--
--  V1, not V4. user-service's history is at V3 and this one starts over,
--  because they are different databases with different
--  flyway_schema_history tables. There is no global "version 4 of persona".
--
--  That is not a technicality. It means the two schemas evolve on separate
--  timelines, and the order in which they are deployed is something humans
--  reason about rather than something Flyway can enforce. In the monolith,
--  V4 could not possibly run before V3. Here, wallet-service V1 and
--  user-service V3 have no relationship at all.
-- ===========================================================================

CREATE TABLE wallets (

    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    -- -------------------------------------------------------------------
    --  THE LINE WHERE THIS FILE DIFFERS FROM THE MONOLITH'S V4, AND THE
    --  REASON persona IS BUILT TWICE.
    --
    --  The monolith writes:
    --
    --      user_id BIGINT NOT NULL UNIQUE REFERENCES users(id)
    --
    --  Here, `REFERENCES users(id)` IS NOT AVAILABLE. Not discouraged —
    --  unavailable. users lives in persona-user-db and this table lives in
    --  persona-wallet-db; they are two PostgreSQL processes, and PostgreSQL
    --  has no cross-database foreign key. Writing that line produces
    --  `relation "users" does not exist` and the migration fails.
    --
    --  WHAT IS LOST, precisely:
    --
    --    * A wallet can be created for user 99 when there is no user 99.
    --      Nothing refuses it. The row is simply wrong, and stays wrong.
    --    * Deleting a user no longer fails because they hold a wallet — the
    --      monolith's deliberate refusal of ON DELETE CASCADE protected
    --      money, and that protection is gone. A deleted user leaves an
    --      orphaned balance that nothing points at.
    --    * No join. "Show me this wallet and its owner's email" was one
    --      query; it is now two calls to two services, with a window in
    --      between where the answer can change.
    --
    --  WHAT REPLACES IT: nothing, at this layer. That is the honest answer
    --  and the one worth sitting with. The integrity that a foreign key
    --  enforced for free now has to be arranged by the application —
    --  wallet-service trusting the id in a verified JWT, user-service
    --  emitting an event when a user is deleted, and a reconciliation job
    --  that finds orphans after the fact instead of preventing them.
    --
    --  Each of those is more code, more failure modes, and weaker. The
    --  compensation is that the two services can be deployed, scaled and
    --  broken independently. THAT IS THE TRADE. It is a real trade, not a
    --  free upgrade, and anyone who describes microservices without naming
    --  this half of it is selling something.
    --
    --  UNIQUE SURVIVES, and is worth noticing. It is a constraint about THIS
    --  table only, so it needs no knowledge of users: one row per user_id,
    --  enforced by this database. So "a user has at most one wallet" is
    --  still a guarantee; "a wallet belongs to a real user" is not. The
    --  foreign key was doing two jobs and only one of them was local.
    -- -------------------------------------------------------------------
    user_id BIGINT NOT NULL UNIQUE,

    -- -------------------------------------------------------------------
    --  Identical to the monolith, because money is money regardless of how
    --  many services there are. NEVER float/double: 0.1 + 0.2 is
    --  0.30000000000000004 under IEEE 754, which is the specification, not
    --  a bug.
    --
    --  The real cost is not the lost fraction but losing the ability to
    --  tell an arithmetic error from an attack — once totals drift on their
    --  own, a discrepancy proves nothing.
    --
    --  (19,4): four decimals, not two, so intermediate results keep their
    --  precision and rounding happens ONCE at the end.
    --
    --  NOTE FOR LATER: in a distributed system this column acquires a second
    --  hazard the monolith does not have. A transfer between two wallets in
    --  one database is one transaction; between two SERVICES it is two, and
    --  money can exist in neither or both for a window. Day-08's transfer
    --  work is where that bites.
    -- -------------------------------------------------------------------
    balance NUMERIC(19,4) NOT NULL DEFAULT 0,

    -- VARCHAR, not an integer: @Enumerated defaults to ORDINAL, which would
    -- store 0/1/2 and leave their meaning in the source-file ordering of a
    -- Java enum. Reorder that enum and every frozen wallet silently becomes
    -- closed. Storing the name also makes the CHECK below writable at all.
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- The backstops. These still work exactly as they do in the monolith,
    -- because they are rules about a row in THIS table — no cross-database
    -- knowledge required. Local invariants survive the split; relational
    -- ones do not. That distinction is most of what service boundaries do
    -- to a schema.
    CONSTRAINT wallets_balance_non_negative CHECK (balance >= 0),

    CONSTRAINT wallets_status_valid
        CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED'))
);

-- No index on user_id: the UNIQUE constraint already created one.

COMMENT ON TABLE wallets IS
    'Owned by wallet-service. One wallet per user_id, but user_id is NOT a foreign key — users live in another database. See V1 comment.';

COMMENT ON COLUMN wallets.user_id IS
    'The id of a user in user-service. UNENFORCED: no FK is possible across databases. Trusted because it comes from a signature-verified JWT.';

COMMENT ON COLUMN wallets.balance IS
    'Exact decimal. Never read into a float/double. Serialised to JSON as a string.';

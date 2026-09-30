-- ===========================================================================
--  V3 — every user gets a role. Day-06.
--
--  WHY A COLUMN, NOT A SEPARATE roles TABLE (roles / user_roles join table).
--
--  A join table is the right shape for MANY roles per user — a real system
--  where someone can be both ADMIN and BILLING. persona has exactly one role
--  per user, decided at signup and rarely changed. A join table for a 1:1
--  relationship buys nothing but two extra JOINs on every query that needs
--  to know who someone is, which is most of them. Design for the cardinality
--  that is actually true, not the one that sounds more "enterprise".
--
--  NOT NULL DEFAULT 'USER' — every existing row (and there are none yet, but
--  the pattern matters) becomes a normal user rather than silently NULL,
--  which would make `role = 'ADMIN'` false for a reason that is invisible
--  until someone asks why a real admin cannot get past @PreAuthorize.
-- ===========================================================================

ALTER TABLE users ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'USER';

-- CHECK rather than a separate roles lookup table, for the same reason V1
-- used CHECK-free VARCHARs elsewhere it could get away with it: this is a
-- closed, small, rarely-changing set of values, and Postgres enforcing it
-- means a typo in an INSERT fails at the database rather than becoming a
-- role nothing in the application ever checks for.
ALTER TABLE users ADD CONSTRAINT users_role_valid
    CHECK (role IN ('USER', 'ADMIN'));

COMMENT ON COLUMN users.role IS
    'USER or ADMIN. Read by Spring Security as a single GrantedAuthority (ROLE_<value>). No multi-role support — see migration comment for why.';

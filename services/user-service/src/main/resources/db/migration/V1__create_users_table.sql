-- ===========================================================================
--  V1 — the users table.
--
--  THE FILENAME IS THE API.  V1__create_users_table.sql
--                            ^^  ^^^^^^^^^^^^^^^^^^^^^
--                            |   description (underscores become spaces)
--                            version — TWO underscores separate them
--
--  Flyway runs pending migrations in version order at startup and records
--  each one in flyway_schema_history. It also stores a CHECKSUM of the file.
--
--  WHICH MEANS: ONCE THIS FILE HAS RUN ANYWHERE, IT IS IMMUTABLE.
--
--  Editing it after it has been applied makes the checksum disagree with the
--  history table, and Flyway refuses to start rather than guess. That feels
--  hostile the first time and it is exactly right: the alternative is a
--  schema that silently differs between your machine and production.
--
--  To change something later, add V2. Never edit V1.
-- ===========================================================================

CREATE TABLE users (

    -- SURROGATE PRIMARY KEY.
    --
    -- Not the email, even though email is this application's identity and is
    -- what equals()/hashCode() compare. Three reasons, in order of weight:
    --
    --   1. People change their email address. With email as the key, that
    --      change has to propagate to every table that references a user.
    --      With a surrogate key it is  UPDATE users SET email = ?  — one row,
    --      and nothing else in the database notices, because the IDENTITY did
    --      not change, an ATTRIBUTE did.
    --   2. Email is PII, and a key ends up in URLs, logs, and Referer headers
    --      sent to third parties. Logs are the hardest place to erase from.
    --   3. 8 bytes and sequential beats ~25 bytes and random in every index
    --      and every foreign key that carries it. (The least important reason,
    --      and the one most often quoted first.)
    --
    -- GENERATED ALWAYS AS IDENTITY is the SQL-standard form and the modern
    -- replacement for SERIAL. "ALWAYS" means an INSERT cannot supply its own
    -- id — the database owns this column absolutely. Make the bad thing
    -- unrepresentable, in SQL.
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    -- NOT NULL + UNIQUE — and this is the single most important line here.
    --
    -- UserService.register() already checks for a duplicate email. That check
    -- is a POLICY: it reads, decides, then writes, and two concurrent requests
    -- both read "not taken" before either writes. The check is correct and
    -- still lets a duplicate through.
    --
    -- This constraint is a GUARANTEE. Enforced at write time, with the row
    -- locked, so there is no gap to race. It holds no matter how many
    -- instances run and no matter what future code forgets.
    --
    -- The Java check stays — it is what produces a readable 409 instead of a
    -- driver exception. But it is now a CONVENIENCE. The defence lives here.
    --
    -- Free bonus: UNIQUE creates an index, so findByEmail is a B-tree lookup
    -- rather than a sequential scan. The most common query in the application
    -- is already fast, as a side effect of being correct.
    email VARCHAR(255) NOT NULL UNIQUE,

    first_name VARCHAR(100) NOT NULL,
    last_name  VARCHAR(100) NOT NULL,

    -- 255 because Day-05 stores a BCrypt hash (60 chars), not a password.
    -- Today it is still plaintext, which is a known, dated debt — not an
    -- oversight. See Day-05.
    password VARCHAR(255) NOT NULL,

    -- The ONLY nullable column in this table, and it is a genuine absence:
    -- a user who has not set an avatar. This is the SQL counterpart of
    -- getImage() returning Optional<String>.
    --
    -- Everything else is NOT NULL because NOT NULL is the default worth
    -- arguing against, not the other way round. VARCHAR(255) means "a string
    -- OR NOTHING"; VARCHAR(255) NOT NULL means "a string". The model's
    -- requireText() says the same thing in Java and can be bypassed by
    -- anything that does not go through the constructor. This cannot.
    image VARCHAR(512),

    -- TIMESTAMPTZ, never TIMESTAMP.
    --
    -- TIMESTAMP is a wall-clock reading with no answer to "where?".
    -- TIMESTAMPTZ is an actual instant, normalised to UTC. Twice a year the
    -- clocks go back and an hour happens TWICE; with local time, ordering
    -- within that hour is genuinely ambiguous.
    --
    -- Store UTC, convert in the UI at the last possible moment — the same
    -- boundary discipline as the DTO.
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- A comment stored IN the database, visible to anyone who inspects the schema
-- with \d+ or a GUI. Comments in this file help whoever reads the repository;
-- this helps whoever is holding a psql prompt at 3am and has never seen it.
COMMENT ON TABLE users IS 'Registered persona users. Email is unique and is the application-level identity; id is the storage identity.';

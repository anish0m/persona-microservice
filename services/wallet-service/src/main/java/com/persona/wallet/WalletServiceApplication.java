package com.persona.wallet;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the <b>wallet-service</b>. Day-07.
 *
 * <p>The second service in persona, and the one that makes the architecture
 * cost something. With one service, "microservices" was a folder layout. With
 * two that own separate databases, it is a list of things that have become
 * impossible.
 *
 * <pre>
 *   MONOLITH (one process, one database)
 *   ────────────────────────────────────────────────────────
 *       POST /users
 *          │
 *          ▼
 *       &#64;Transactional {
 *           INSERT users   ──┐
 *           INSERT wallets ──┴── one transaction, both or neither
 *       }
 *
 *   MICROSERVICES (two processes, two databases)
 *   ────────────────────────────────────────────────────────
 *       POST /users                      POST /wallets/me
 *          │                                │
 *       user-service  ── HTTP ──────────►  wallet-service
 *          │                                │
 *       persona_user                     persona_wallet
 *       COMMIT ✅                         COMMIT ✅ (or not)
 *          │                                │
 *          └──── NO transaction spans these ─┘
 * </pre>
 *
 * <p>What replaces the transaction is not an equivalent — it is a weaker
 * arrangement that has to be designed: an idempotent create so a retry is
 * safe, a caller willing to retry, and an accepted window during which a user
 * exists with no wallet. See {@code WalletService.createFor}.
 *
 * <p><b>The honest summary of Day-07 across the two builds:</b> the monolith
 * solved this in one annotation; the microservice build spends a service, a
 * database, a token claim, an idempotency guarantee and a documented failure
 * window to get something less reliable. It buys independent deployment and
 * scaling with that. Whether the trade is worth it depends entirely on
 * whether those are needed — which is the judgement the syllabus wants
 * exercised by Day-17, and why persona is built twice.
 *
 * <p>Port 8082: user-service is 8081, the monolith is 8080.
 */
@SpringBootApplication
public class WalletServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(WalletServiceApplication.class, args);
    }
}

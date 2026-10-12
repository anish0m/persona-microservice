package com.persona.wallet.config;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Verifies tokens issued by user-service. Day-07.
 *
 * <h2>Note the name: Verifier, not Service</h2>
 *
 * <p>user-service has a {@code JwtService} that both <b>issues</b> and
 * <b>validates</b>. This class only validates, and the missing
 * {@code issueToken} method is the point rather than an omission.
 *
 * <p><b>Exactly one service may mint tokens.</b> If wallet-service could issue
 * them too, there would be two places where "what does a valid persona
 * identity look like" is decided, and they would drift — one adding a claim,
 * one changing an expiry, both signing with the same key so neither fails
 * loudly. Naming the class for what it may do makes the asymmetry visible in
 * the file list, before anyone opens it.
 *
 * <p>This is a general shape worth keeping: <b>when two services share a
 * capability, give the one that may not do the whole thing a narrower type,
 * not the same type with a method nobody calls.</b>
 *
 * <h2>Why this does not call user-service</h2>
 *
 * <p>Verification is pure arithmetic: re-compute the HMAC over the header and
 * payload with the shared secret and compare it to the signature. No network,
 * no database, no coordination. That is the entire appeal of a stateless token
 * — authentication scales to N services without N round trips back to the
 * issuer.
 *
 * <p>Two costs, both real:
 *
 * <ul>
 *   <li><b>The secret is now in two deployments.</b> Rotating it means
 *       rotating both together; miss one and every token minted by the other
 *       is rejected. An asymmetric key pair (RS256 — user-service signs with a
 *       private key, everyone verifies with the public one) removes this, and
 *       is the usual production answer.</li>
 *   <li><b>Revocation does not propagate.</b> user-service keeps a
 *       {@code TokenDenyList} for logout; this service has no idea it exists.
 *       A token logged out over there still works here until it expires.
 *       Fixing that needs shared state — Redis, or an event — which is a
 *       later day's problem and is named here rather than hidden.</li>
 * </ul>
 */
@Component
public class JwtVerifier {

    private final SecretKey signingKey;

    /**
     * The same secret as user-service, by configuration rather than by a
     * shared jar.
     *
     * <p>Note what is NOT injected: {@code persona.jwt.expiration-ms}. This
     * service never issues a token, so it has no use for a lifetime — it reads
     * the {@code exp} claim out of whatever arrives. A field that would never
     * be read is a field that goes stale without anyone noticing.
     */
    public JwtVerifier(@Value("${persona.jwt.secret}") String secretHex) {
        this.signingKey = Keys.hmacShaKeyFor(hexToBytes(secretHex));
    }

    /**
     * Hex string to bytes. Duplicated from user-service's {@code JwtService},
     * deliberately.
     *
     * <p>Six lines copied rather than extracted into a shared module. That
     * looks like the obvious candidate for a {@code persona-common} jar, and
     * refusing it is the single most important build decision in this service
     * — see the pom. A shared jar re-couples the two services at build time:
     * a change there forces a rebuild and redeploy of both, which is precisely
     * what the split exists to avoid.
     *
     * <p><b>The rule of thumb: duplicate code is cheaper than a shared
     * deployment dependency, until the duplicated thing is genuinely a
     * published contract.</b> Six lines of hex parsing is not a contract.
     */
    private static byte[] hexToBytes(String hex) {
        int length = hex.length();
        byte[] bytes = new byte[length / 2];
        for (int i = 0; i < length; i += 2) {
            bytes[i / 2] = (byte) Integer.parseInt(hex.substring(i, i + 2), 16);
        }
        return bytes;
    }

    /**
     * Whether the token is genuine and unexpired.
     *
     * <p>Catches {@code IllegalArgumentException} as well as
     * {@code JwtException} because jjwt throws the former for a null or empty
     * token — a different type for what is, to a caller, the same answer.
     */
    public boolean isValid(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    /** The subject claim — the user's email. */
    public String extractEmail(String token) {
        return parseClaims(token).getSubject();
    }

    /**
     * The {@code uid} claim — the user's id in user-service, and the key to
     * every row this service owns.
     *
     * <p>Read as a {@link Number} and widened rather than asked for as a
     * {@code Long}: jjwt deserialises a JSON number into whatever fits, so a
     * small id arrives as an {@code Integer}. Asking for {@code Long.class}
     * throws depending on how many rows the users table happens to have —
     * a bug that passes on a fresh database and fails on a mature one.
     */
    public Long extractUserId(String token) {
        Number uid = parseClaims(token).get("uid", Number.class);
        return uid == null ? null : uid.longValue();
    }

    /**
     * The {@code role} claim.
     *
     * <p><b>A snapshot, not a live read.</b> The monolith's filter looked the
     * role up in the database on every request, so demoting an admin took
     * effect immediately. Here the role was fixed when the token was minted
     * and stays that way until it expires — up to one token lifetime of
     * privilege the database no longer grants.
     *
     * <p>That window is the price of not calling user-service on every
     * request, and it is why short token lifetimes matter more in a
     * distributed system than in a monolith.
     */
    public String extractRole(String token) {
        return parseClaims(token).get("role", String.class);
    }

    /**
     * {@code verifyWith} is what makes this verification rather than decoding.
     *
     * <p>Without it, jjwt would happily read the payload of a token signed by
     * nobody — the claims are base64, not ciphertext, and anyone can write
     * {@code "role":"ADMIN"} into one. The signature check is the entire
     * security boundary of this service.
     */
    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}

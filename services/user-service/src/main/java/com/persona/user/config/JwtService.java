package com.persona.user.config;

import java.util.Date;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Issues and validates JWTs. <b>Day-06, piece 1.</b> Identical reasoning to
 * the monolith's {@code JwtService} — see that class for the full write-up on
 * why the payload carries only {@code sub}+{@code exp} for now, why the
 * signature protects integrity rather than secrecy, and why
 * {@link #isValid} is one {@code try/catch} rather than three.
 *
 * <h2>The one thing that is different here, and is not solved yet</h2>
 *
 * <p>In the monolith, one process issues tokens and the same process checks
 * them — the key never needs to leave this class. In a microservice split,
 * that stops being true the moment a second service needs to verify a token
 * {@code user-service} issued: it needs this exact key, which turns
 * {@code persona.jwt.secret} from "this service's config" into shared
 * infrastructure between services. Not addressed here — see the comment on
 * {@code persona.jwt.secret} in {@code application.properties} for why that
 * is deliberate.
 */
@Component
public class JwtService {

    private final SecretKey signingKey;
    private final long expirationMs;

    public JwtService(
            @Value("${persona.jwt.secret}") String secretHex,
            @Value("${persona.jwt.expiration-ms}") long expirationMs) {
        byte[] keyBytes = hexToBytes(secretHex);
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        this.expirationMs = expirationMs;
    }

    private static byte[] hexToBytes(String hex) {
        int length = hex.length();
        byte[] bytes = new byte[length / 2];
        for (int i = 0; i < length; i += 2) {
            bytes[i / 2] = (byte) Integer.parseInt(hex.substring(i, i + 2), 16);
        }
        return bytes;
    }

    /**
     * <b>Day-07 — the token now carries the user id and role as claims.</b>
     *
     * <p>Until today it carried only the email, and that was sufficient:
     * {@code JwtAuthenticationFilter} looked the user up in this service's own
     * database to find their role, so the token only had to say <em>who</em>.
     *
     * <p>wallet-service broke that. It receives the same token, needs the
     * user's id to find their wallet and their role to enforce
     * {@code hasRole('ADMIN')} — and <b>it has no users table to look either
     * one up in.</b> Its only options are to call user-service on every single
     * request, or to read the facts out of the token.
     *
     * <p>So the token becomes the carrier of what a shared database used to
     * supply. That is a real pattern and a real trade:
     *
     * <ul>
     *   <li><b>Gained:</b> wallet-service authenticates and authorises with
     *       zero network calls. Authentication scales to N services without N
     *       round trips back to the issuer. This is the entire appeal of a
     *       stateless token.</li>
     *   <li><b>Lost:</b> the claims are a <em>snapshot</em>. Demote an admin to
     *       USER and their existing token still says ADMIN until it expires —
     *       up to 15 minutes of privilege the database no longer grants. In
     *       the monolith the filter re-read the role from the database on
     *       every request, so a demotion took effect on the next one.</li>
     * </ul>
     *
     * <p>That staleness window is why {@code expiration-ms} matters more in a
     * distributed system than in a monolith, and why production systems keep
     * access tokens short and add refresh tokens rather than simply making
     * them long-lived.
     *
     * <p>Note the id and role are <b>readable by anyone holding the token</b> —
     * a JWT is signed, not encrypted, and the payload is base64, not
     * ciphertext. Neither is a secret (the role is a fact about the holder,
     * not a credential), but nothing confidential may ever go in here.
     */
    public String issueToken(String email, Long userId, String role) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);

        return Jwts.builder()
                .subject(email)

                // Day-07. The two facts wallet-service cannot look up itself.
                // `uid` and `role` are custom (private) claims — the JWT spec
                // reserves a handful of names like sub/iat/exp/jti and leaves
                // the rest to the application.
                .claim("uid", userId)
                .claim("role", role)

                // A UNIQUE TOKEN ID — not decoration. Without it, every claim
                // is a function of (email, current second), because `iat` and
                // `exp` have one-second resolution. Two logins by the same
                // user within the same second produced BYTE-IDENTICAL tokens,
                // so after a logout put one on the deny list, the next login
                // handed back an already-revoked token. `jti` is the standard
                // claim for exactly this: a nonce making each issued token
                // distinct even when everything else matches.
                .id(java.util.UUID.randomUUID().toString())

                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey)
                .compact();
    }

    public String extractEmail(String token) {
        return parseClaims(token).getSubject();
    }

    /**
     * The user's id, from the {@code uid} claim. <b>Day-07.</b>
     *
     * <p>Returns {@code Long}, but note what arrives: jjwt deserialises a JSON
     * number into whatever fits, so a small id comes back as an
     * {@code Integer} and a large one as a {@code Long}. Asking for
     * {@code Long.class} directly throws once ids are small, which is the kind
     * of bug that passes every test on a fresh database and fails in
     * production where ids happen to be bigger — or vice versa.
     *
     * <p>So it is read as a {@link Number} and widened. Unglamorous, and the
     * alternative is a defect that depends on how many rows the table has.
     */
    public Long extractUserId(String token) {
        Number uid = parseClaims(token).get("uid", Number.class);
        return uid == null ? null : uid.longValue();
    }

    /**
     * The user's role, from the {@code role} claim. <b>Day-07.</b>
     *
     * <p>A snapshot taken when the token was issued, not a live read — see
     * {@link #issueToken} for why that matters and what it costs.
     */
    public String extractRole(String token) {
        return parseClaims(token).get("role", String.class);
    }

    /**
     * When this token stops being valid on its own. Needed by
     * {@link TokenDenyList} so a revoked token can be forgotten once it would
     * have expired anyway. Reads the payload, so the token must already have
     * been established as valid.
     */
    public java.time.Instant extractExpiry(String token) {
        return parseClaims(token).getExpiration().toInstant();
    }

    public boolean isValid(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}

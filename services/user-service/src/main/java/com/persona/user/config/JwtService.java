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

    public String issueToken(String email) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);

        return Jwts.builder()
                .subject(email)

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

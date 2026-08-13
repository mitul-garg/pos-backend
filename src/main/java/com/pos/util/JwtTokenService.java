package com.pos.util;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import javax.crypto.SecretKey;

import com.pos.config.AppProperties;
import com.pos.exception.InvalidCredentialsException;
import com.pos.pojo.enums.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Signs and verifies the bearer tokens (C3). Pure encode/decode — it holds no state,
 * touches no database and knows no rules, which is why it lives in {@code util} rather
 * than {@code service}.
 *
 * <p><b>The token is deliberately thin.</b> It carries the user id, the tenant and the
 * role, and that is all: no display name, no permissions list, nothing the application
 * would then be tempted to trust. Everything the application acts on is re-read from
 * the database per request, so a claim can never go stale in a way that matters. What
 * the signature buys is the identity of the caller, not the state of their account.
 */
@Component
public class JwtTokenService {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenService.class);

    /** Absent, not null, for a platform {@code SUPER_ADMIN}. */
    public static final String TENANT_CLAIM = "tenantId";
    public static final String ROLE_CLAIM = "role";

    /**
     * The committed placeholder from {@code application.properties}. Rejected here so a
     * misconfigured deployment fails at startup rather than serving requests signed with
     * a key that is public in the repository.
     */
    private static final String UNUSABLE_SECRET = "CHANGE_ME";

    /** HS256 produces a 256-bit MAC; JWA forbids a key shorter than its own output. */
    private static final int MINIMUM_SECRET_BYTES = 32;

    private final SecretKey key;
    private final Duration ttl;

    @Autowired
    public JwtTokenService(AppProperties props) {
        this(props.getJwtSecret(), props.getJwtTtlMinutes());
    }

    /**
     * Package-private so {@code JwtTokenServiceTest} can vary the secret and the TTL
     * without a Spring context — a codec test that needs one is a test of Spring.
     */
    JwtTokenService(String secret, long ttlMinutes) {
        if (secret == null || secret.isBlank() || UNUSABLE_SECRET.equals(secret)) {
            throw new IllegalStateException(
                    "pos.jwt.secret is not set. There is no usable default on purpose: a "
                            + "signing key that works out of the box is one nobody replaces. "
                            + "Set POS_JWT_SECRET, or put pos.jwt.secret in "
                            + "application-local.properties (gitignored).");
        }
        byte[] material = secret.getBytes(StandardCharsets.UTF_8);
        if (material.length < MINIMUM_SECRET_BYTES) {
            throw new IllegalStateException(
                    "pos.jwt.secret must be at least " + MINIMUM_SECRET_BYTES
                            + " bytes for HS256; got " + material.length + ".");
        }
        if (ttlMinutes <= 0) {
            throw new IllegalStateException("pos.jwt.ttlMinutes must be positive; got " + ttlMinutes);
        }
        this.key = Keys.hmacShaKeyFor(material);
        this.ttl = Duration.ofMinutes(ttlMinutes);
    }

    /**
     * @param tenantId {@code null} for a platform user — the claim is then omitted
     *                 entirely rather than written as null, so "no tenant" and "tenant
     *                 unknown" cannot be confused on the way back in
     */
    public String issue(Long userId, Long tenantId, Role role) {
        Instant now = Instant.now();
        JwtBuilder builder = Jwts.builder()
                // Ids are strings on the wire everywhere else in this API; a token is no
                // different, and `sub` is a string claim by specification anyway.
                .subject(String.valueOf(userId))
                .claim(ROLE_CLAIM, role.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)));
        if (tenantId != null) {
            builder.claim(TENANT_CLAIM, String.valueOf(tenantId));
        }
        return builder.signWith(key).compact();
    }

    /**
     * Verifies signature and expiry, then reads the claims back.
     *
     * <p>Every failure — tampered, expired, malformed, signed with another key — raises
     * the same {@link InvalidCredentialsException}, so the caller learns only that the
     * token is unusable. An expired token could safely say so (its holder already
     * authenticated once), but the frontend forces a re-login on any 401 regardless, so
     * a second message would buy nothing and would be one more thing to keep uniform.
     */
    public JwtPrincipal parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            return new JwtPrincipal(
                    Long.valueOf(claims.getSubject()),
                    readTenantId(claims),
                    Role.valueOf(claims.get(ROLE_CLAIM, String.class)));
        } catch (JwtException | IllegalArgumentException | NullPointerException ex) {
            // DEBUG, not WARN: an expired token is the ordinary end of a shift, and a
            // logger that shouts about it teaches people to ignore the security log.
            log.debug("Rejecting token: {}", ex.getMessage());
            throw new InvalidCredentialsException();
        }
    }

    private Long readTenantId(Claims claims) {
        String tenantId = claims.get(TENANT_CLAIM, String.class);
        return tenantId == null ? null : Long.valueOf(tenantId);
    }
}

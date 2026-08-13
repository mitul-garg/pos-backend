package com.pos.util;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import javax.crypto.SecretKey;

import com.pos.exception.InvalidCredentialsException;
import com.pos.pojo.enums.Role;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The token codec, with no database and no Spring context — a signing test that needs
 * either is testing the wrong thing.
 *
 * <p>The rejection cases are the ones that matter. A token this class accepts is a
 * caller it believes, so every way of producing one without the signing key has to fail:
 * that is the entire basis on which {@code AuthService} trusts a request at all.
 */
@DisplayName("JwtTokenService")
class JwtTokenServiceTest {

    private static final String SECRET = "test-only-signing-key-not-a-secret-0123456789";
    private static final String OTHER_SECRET = "a-completely-different-key-0123456789-abcdef";

    private final JwtTokenService tokens = new JwtTokenService(SECRET, 60);

    @Nested
    @DisplayName("round trip")
    class RoundTrip {

        @Test
        @DisplayName("carries the user, tenant and role back out")
        void carriesTheClaims() {
            JwtPrincipal principal = tokens.parse(tokens.issue(42L, 7L, Role.CASHIER));

            assertEquals(42L, principal.userId());
            assertEquals(7L, principal.tenantId());
            assertEquals(Role.CASHIER, principal.role());
        }

        @Test
        @DisplayName("gives a platform user no tenant, rather than a tenant that looks real")
        void omitsTheTenantForAPlatformUser() {
            // The claim is left out entirely rather than written as null, so "belongs to
            // no tenant" cannot be confused with a claim that failed to serialize -- and
            // so C4's filter can never be handed a tenant id for a SUPER_ADMIN.
            JwtPrincipal principal = tokens.parse(tokens.issue(9L, null, Role.SUPER_ADMIN));

            assertNull(principal.tenantId());
            assertEquals(Role.SUPER_ADMIN, principal.role());
        }

        @Test
        @DisplayName("gives two users different tokens")
        void issuesDistinctTokens() {
            assertNotEquals(tokens.issue(1L, 1L, Role.ADMIN), tokens.issue(2L, 1L, Role.ADMIN));
        }
    }

    @Nested
    @DisplayName("rejects")
    class Rejects {

        @Test
        @DisplayName("a token signed with another key")
        void aTokenFromAnotherKey() {
            String foreign = new JwtTokenService(OTHER_SECRET, 60).issue(1L, 1L, Role.ADMIN);

            assertThrows(InvalidCredentialsException.class, () -> tokens.parse(foreign));
        }

        @Test
        @DisplayName("a token whose claims were edited")
        void aTamperedToken() {
            // The signature covers the payload, which is the property everything rests
            // on: the claims are readable by anyone holding the token, but not writable.
            String[] parts = tokens.issue(1L, 1L, Role.CASHIER).split("\\.");
            String edited = parts[0] + "."
                    + parts[1].substring(0, parts[1].length() - 2) + "XY."
                    + parts[2];

            assertThrows(InvalidCredentialsException.class, () -> tokens.parse(edited));
        }

        @Test
        @DisplayName("a token with the signature stripped off")
        void anUnsignedToken() {
            // The classic JWT hole -- parsing without demanding a signature. If this ever
            // passes, anyone writes their own claims and picks their own tenant, and no
            // amount of correctness elsewhere matters.
            String[] parts = tokens.issue(1L, 1L, Role.CASHIER).split("\\.");

            assertThrows(InvalidCredentialsException.class,
                    () -> tokens.parse(parts[0] + "." + parts[1] + "."));
        }

        @Test
        @DisplayName("an expired token, even though it is otherwise perfectly signed")
        void anExpiredToken() {
            // Minted here rather than by waiting out a TTL: the public API cannot backdate
            // a token, which is itself the point. Signed with the real key, so the only
            // thing wrong with it is `exp` -- and that alone has to be disqualifying,
            // because expiry is the only thing that ever ends a session (there is no
            // revocation list; see AuthController.logout).
            Instant now = Instant.now();
            String expired = Jwts.builder()
                    .subject("1")
                    .claim(JwtTokenService.ROLE_CLAIM, Role.CASHIER.name())
                    .issuedAt(Date.from(now.minus(Duration.ofHours(2))))
                    .expiration(Date.from(now.minus(Duration.ofHours(1))))
                    .signWith(key(SECRET))
                    .compact();

            assertThrows(InvalidCredentialsException.class, () -> tokens.parse(expired));
        }

        @Test
        @DisplayName("a validly signed token that carries no role")
        void aTokenMissingARole() {
            // Reachable only from a future version of this class that forgets the claim,
            // which is exactly when a NullPointerException would escape to the 500
            // backstop and read as "the server is broken" instead of "your token is".
            String roleless = Jwts.builder()
                    .subject("1")
                    .expiration(Date.from(Instant.now().plus(Duration.ofHours(1))))
                    .signWith(key(SECRET))
                    .compact();

            assertThrows(InvalidCredentialsException.class, () -> tokens.parse(roleless));
        }

        @Test
        @DisplayName("garbage, as a 401 rather than as something the 500 handler catches")
        void garbage() {
            assertThrows(InvalidCredentialsException.class, () -> tokens.parse("not-a-token"));
            assertThrows(InvalidCredentialsException.class, () -> tokens.parse(""));
            assertThrows(InvalidCredentialsException.class, () -> tokens.parse("a.b.c"));
        }
    }

    @Nested
    @DisplayName("refuses to start")
    class RefusesToStart {

        @Test
        @DisplayName("on the committed placeholder secret")
        void onThePlaceholderSecret() {
            // application.properties ships pos.jwt.secret=CHANGE_ME precisely so this
            // fires. A signing key that works out of the box is one nobody replaces, and
            // anyone with the source could then mint a token for any tenant.
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> new JwtTokenService("CHANGE_ME", 60));

            assertTrue(ex.getMessage().contains("pos.jwt.secret"), ex.getMessage());
        }

        @Test
        @DisplayName("on no secret at all")
        void onNoSecret() {
            assertThrows(IllegalStateException.class, () -> new JwtTokenService(null, 60));
            assertThrows(IllegalStateException.class, () -> new JwtTokenService("   ", 60));
        }

        @Test
        @DisplayName("on a secret too short for HS256")
        void onAShortSecret() {
            // JWA forbids a key shorter than the MAC it produces. Failing at startup with
            // the byte count beats failing at the first login with a WeakKeyException
            // nobody can trace back to a property.
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> new JwtTokenService("too-short", 60));

            assertTrue(ex.getMessage().contains("32 bytes"), ex.getMessage());
        }

        @Test
        @DisplayName("on a TTL that would issue already-expired tokens")
        void onANonPositiveTtl() {
            // Every login would appear to succeed and every request after it would 401,
            // which is a spectacularly confusing way to be misconfigured.
            assertThrows(IllegalStateException.class, () -> new JwtTokenService(SECRET, 0));
            assertThrows(IllegalStateException.class, () -> new JwtTokenService(SECRET, -5));
        }
    }

    private static SecretKey key(String secret) {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }
}

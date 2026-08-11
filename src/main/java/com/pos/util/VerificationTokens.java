package com.pos.util;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Mints the one-time token behind email verification (C9,
 * {@code tenant-registration-plan.md} §4) — 256 bits from {@link SecureRandom},
 * URL-safe base64 with no padding so it drops straight into a query string
 * ({@code /verify?token=...}) with no encoding step.
 *
 * <p>32 random bytes, not a {@link java.util.UUID} — a UUID's 122 bits of randomness
 * (6 of its 128 are fixed by the version/variant) is already far more than a username
 * or a tenant code needs to guess, but this token is the *entire* authorization for
 * activating a tenant, with no rate limit protecting it the way a login attempt has
 * one — worth the extra headroom. No collision handling: {@code uk_tenant_verification
 * _token} would reject one, but at this entropy a collision is not a realistic
 * scenario to write retry logic for, unlike a human-chosen tenant code or username.
 */
public final class VerificationTokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    private VerificationTokens() {
    }

    public static String generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}

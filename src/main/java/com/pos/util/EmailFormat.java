package com.pos.util;

import java.util.regex.Pattern;

/**
 * The email-format check for self-registration's {@code adminEmail} (C9,
 * {@code tenant-registration-plan.md} §4) — pulled out for a name and a unit test,
 * the same reasoning {@link Honeypot} gives for existing as its own class rather than
 * an inline regex a future reader has to reverse-engineer.
 *
 * <p><b>Deliberately permissive</b> — has-an-{@code @}-and-a-dot, not RFC 5322-exact —
 * ported verbatim from the frontend's {@code domain/validators.js} (`EMAIL_PATTERN`).
 * The address is only ever used to send one email; the cost of a false negative (a
 * real address rejected) is higher than the cost of a false positive (a malformed one
 * that just bounces), and the two codebases agreeing on the pattern means a form the
 * frontend accepts is never rejected by the backend it's about to call.
 */
public final class EmailFormat {

    private static final Pattern PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private EmailFormat() {
    }

    /** @return {@code true} if {@code value} (trimmed) looks like an email address */
    public static boolean isValid(String value) {
        return value != null && PATTERN.matcher(value.trim()).matches();
    }
}

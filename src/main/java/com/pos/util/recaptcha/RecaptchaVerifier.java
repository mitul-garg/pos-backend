package com.pos.util.recaptcha;

/**
 * Server-side verification of a Google reCAPTCHA v2 ("I'm not a robot" checkbox)
 * response token (peer-review Phase 0) — the backstop for {@code POST
 * /api/tenants/register} and {@code /resend-verification} beside {@link Honeypot}
 * and {@link RegistrationRateLimiter}. The honeypot alone stops a naive scraper
 * blind to CSS; this is what still gates a scripted attacker who knows to leave
 * that field blank.
 *
 * <p>An interface, not a single class, for the identical reason {@code EmailSender}
 * is one ({@code MailConfig}'s Javadoc): {@code mvn test} must never make a real
 * network call to Google, so a no-op stand-in exists for that case — see
 * {@code RecaptchaConfig}, which selects between {@link GoogleRecaptchaVerifier}
 * and {@link NoopRecaptchaVerifier} the same way {@code MailConfig} selects an
 * {@code EmailSender}.
 */
public interface RecaptchaVerifier {

    /**
     * @param token the widget's response token (the frontend's {@code recaptchaToken}
     *              field) — {@code null}/blank is a legitimate input, not a caller
     *              error, since a form submitted without completing the widget sends
     *              exactly that
     * @return {@code true} if Google confirms this token as a genuine solve of the
     *         registered site's challenge, {@code false} otherwise — including a
     *         blank token, an expired/already-used one, or a failure to reach Google
     */
    boolean verify(String token);
}

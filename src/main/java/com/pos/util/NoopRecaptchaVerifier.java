package com.pos.util;

/**
 * Always passes — selected by {@code RecaptchaConfig} whenever
 * {@code pos.recaptcha.enabled=false}, the same reasoning {@code LoggingEmailSender}
 * exists for {@code EmailSender}: {@code mvn test} and an out-of-the-box
 * {@code mvn jetty:run} must never require a real Google site registration or make a
 * live network call just to exercise self-registration.
 *
 * <p>Deliberately not conditioned on the token's value at all (unlike, say, a fake
 * that requires a magic string) — the "off" state means the gate isn't there, full
 * stop, so every token including a blank one passes. A test that needs to prove the
 * gate actually rejects something supplies its own controllable {@link
 * RecaptchaVerifier}, the same way {@code TenantRegistrationIT} supplies its own
 * {@code CapturingEmailSender} instead of relying on {@code LoggingEmailSender}.
 */
public class NoopRecaptchaVerifier implements RecaptchaVerifier {

    @Override
    public boolean verify(String token) {
        return true;
    }
}

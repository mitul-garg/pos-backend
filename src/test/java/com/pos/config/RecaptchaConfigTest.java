package com.pos.config;

import com.pos.util.recaptcha.GoogleRecaptchaVerifier;
import com.pos.util.recaptcha.NoopRecaptchaVerifier;
import com.pos.util.recaptcha.RecaptchaVerifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code RecaptchaVerifier} selection logic, with no Spring context — {@code
 * RecaptchaConfig.buildVerifier} is pure wiring, the same reason {@code
 * MailConfigTest} constructs {@code MailConfig.buildEmailSender}'s subject directly.
 */
@DisplayName("RecaptchaConfig's RecaptchaVerifier selection")
class RecaptchaConfigTest {

    @Test
    @DisplayName("defaults to always-pass when disabled, even with no secret at all")
    void noopWhenDisabled() {
        RecaptchaVerifier verifier = RecaptchaConfig.buildVerifier(false, null);

        assertInstanceOf(NoopRecaptchaVerifier.class, verifier);
    }

    @Test
    @DisplayName("builds a real verifier once enabled with a secret present")
    void googleWhenEnabledAndConfigured() {
        RecaptchaVerifier verifier = RecaptchaConfig.buildVerifier(true, "a-real-secret");

        assertInstanceOf(GoogleRecaptchaVerifier.class, verifier);
    }

    @Test
    @DisplayName("fails at startup, not per-request, when enabled with no secret")
    void rejectsMissingSecretWhenEnabled() {
        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> RecaptchaConfig.buildVerifier(true, null));

        assertTrue(ex.getMessage().contains("pos.recaptcha.secret"),
                () -> "expected message to name pos.recaptcha.secret, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("fails at startup when enabled with a blank secret")
    void rejectsBlankSecretWhenEnabled() {
        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> RecaptchaConfig.buildVerifier(true, "   "));

        assertTrue(ex.getMessage().contains("pos.recaptcha.secret"),
                () -> "expected message to name pos.recaptcha.secret, got: " + ex.getMessage());
    }
}

package com.pos.config;

import com.pos.util.EmailSender;
import com.pos.util.JavaMailEmailSender;
import com.pos.util.LoggingEmailSender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The {@code EmailSender} selection logic, with no Spring context — {@code
 * MailConfig.buildEmailSender} is pure wiring, the same reason {@code JwtTokenServiceTest}
 * constructs its subject directly rather than through a container.
 */
@DisplayName("MailConfig's EmailSender selection")
class MailConfigTest {

    @Test
    @DisplayName("defaults to logging when mail is disabled, even with no credentials at all")
    void loggingWhenDisabled() {
        EmailSender sender = MailConfig.buildEmailSender(false, null, 0, null, null, null);

        assertInstanceOf(LoggingEmailSender.class, sender);
    }

    @Test
    @DisplayName("builds a real sender once enabled with every setting present")
    void javaMailWhenEnabledAndConfigured() {
        EmailSender sender = MailConfig.buildEmailSender(
                true, "smtp.gmail.com", 587, "store@example.com", "app-password",
                "store@example.com");

        assertInstanceOf(JavaMailEmailSender.class, sender);
    }

    @Test
    @DisplayName("fails at startup, not per-request, when enabled with a blank host")
    void rejectsBlankHostWhenEnabled() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                MailConfig.buildEmailSender(true, " ", 587, "u", "p", "f@example.com"));
        assertContainsProperty(ex, "pos.mail.host");
    }

    @Test
    @DisplayName("fails at startup when enabled with a missing username")
    void rejectsMissingUsernameWhenEnabled() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                MailConfig.buildEmailSender(true, "smtp.gmail.com", 587, null, "p", "f@example.com"));
        assertContainsProperty(ex, "pos.mail.username");
    }

    @Test
    @DisplayName("fails at startup when enabled with a missing app password")
    void rejectsMissingAppPasswordWhenEnabled() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                MailConfig.buildEmailSender(true, "smtp.gmail.com", 587, "u", "", "f@example.com"));
        assertContainsProperty(ex, "pos.mail.appPassword");
    }

    @Test
    @DisplayName("fails at startup when enabled with a missing from-address")
    void rejectsMissingFromAddressWhenEnabled() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                MailConfig.buildEmailSender(true, "smtp.gmail.com", 587, "u", "p", null));
        assertContainsProperty(ex, "pos.mail.fromAddress");
    }

    private void assertContainsProperty(IllegalStateException ex, String property) {
        org.junit.jupiter.api.Assertions.assertTrue(
                ex.getMessage().contains(property),
                () -> "expected message to name " + property + ", got: " + ex.getMessage());
    }
}

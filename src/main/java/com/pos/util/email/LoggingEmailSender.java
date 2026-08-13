package com.pos.util.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code pos.mail.enabled=false} implementation (C9) — logs what would have been
 * sent instead of opening an SMTP connection. This is the default everywhere except a
 * deployed environment that has provisioned real credentials (see
 * {@code application.properties}), which is what lets {@code mvn test} and
 * {@code mvn jetty:run} exercise self-registration end to end with no Gmail account, the
 * same way {@code pos.seed.dev} lets local development run without deployed secrets.
 *
 * <p>Logged at INFO, not DEBUG — a developer manually testing {@code register}/
 * {@code resend-verification} against a local {@code jetty:run} needs the verification
 * link to actually be visible in the console; requiring a log-level change to see it
 * would defeat the point of this class existing.
 */
public class LoggingEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    @Override
    public void send(String to, String subject, String body) {
        log.info("Email not sent (pos.mail.enabled=false) -- to: {}, subject: {}\n{}",
                to, subject, body);
    }
}

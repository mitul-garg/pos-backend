package com.pos.util;

/**
 * Sends a plain-text email (C9, {@code tenant-registration-plan.md}). Only ever needed
 * for one thing so far — the self-registration verification/resend link — so the
 * contract stays as small as that use needs: no attachments, no HTML, no templating.
 *
 * <p>Two implementations, chosen by {@code pos.mail.enabled} in {@link
 * com.pos.config.MailConfig}: {@link JavaMailEmailSender} for a deployed environment
 * with real SMTP credentials, {@link LoggingEmailSender} everywhere else (default) so
 * {@code mvn test} and {@code mvn jetty:run} never need a real mailbox. Composing the
 * subject/body is {@code TenantRegistrationService}'s job, not this interface's — this
 * is purely the transport, the same division {@code PasswordEncoder} draws between
 * "hash this" and whoever decides what gets hashed.
 */
public interface EmailSender {

    /**
     * @param to      the recipient address — {@code AppUser.email} for every caller so far
     * @param subject plain text, no encoding surprises expected
     * @param body    plain text; a link is just a URL in the message body
     */
    void send(String to, String subject, String body);
}

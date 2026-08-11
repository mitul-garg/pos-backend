package com.pos.util;

import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * The {@code pos.mail.enabled=true} implementation (C9) — a thin adapter over Spring's
 * {@link JavaMailSender}, itself built and configured by {@link com.pos.config.MailConfig}
 * from {@link com.pos.config.AppProperties}'s {@code pos.mail.*} settings.
 *
 * <p>{@link SimpleMailMessage} rather than a {@code MimeMessage}: the one email this
 * feature sends is plain text with a link in it (verification/resend), which is exactly
 * what {@code SimpleMailMessage} is for. Reach for {@code MimeMessageHelper} the day an
 * HTML template or an attachment is actually needed — not before.
 */
public class JavaMailEmailSender implements EmailSender {

    private final JavaMailSender mailSender;
    private final String fromAddress;

    public JavaMailEmailSender(JavaMailSender mailSender, String fromAddress) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
    }

    @Override
    public void send(String to, String subject, String body) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);
        // Propagates as-is. TenantRegistrationService (C9(d)) is responsible for making
        // sure a slow/failed send never rolls back the tenant/admin row it followed --
        // it calls this only after its transaction has committed, and logs-and-continues
        // on failure rather than letting this exception reach the caller.
        mailSender.send(message);
    }
}

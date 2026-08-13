package com.pos.config;

import java.util.Properties;

import com.pos.util.email.EmailSender;
import com.pos.util.email.JavaMailEmailSender;
import com.pos.util.email.LoggingEmailSender;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * Wires {@link EmailSender} (C9) — a root-context config, registered in
 * {@link WebAppInitializer} beside {@link PersistenceConfig}/{@link SecurityConfig}: the
 * bean has to exist before any request arrives, since {@code TenantRegistrationService}
 * (a root-context {@code @Service}) takes it as a constructor dependency.
 *
 * <p><b>Two implementations, selected by an {@code if}, not
 * {@code @ConditionalOnProperty}.</b> There is no Spring Boot auto-configuration here
 * (CONVENTIONS.md), so the choice between {@link JavaMailEmailSender} and
 * {@link LoggingEmailSender} is exactly the kind of decision Boot would hide and this
 * project writes out instead — the same shape {@link SecurityConfig#passwordEncoder()}
 * uses for a single-implementation bean, one step further because this one has two.
 *
 * <p>Defaults to {@link LoggingEmailSender} ({@code pos.mail.enabled=false}), so
 * {@code mvn test} and {@code mvn jetty:run} never open a real SMTP connection — no
 * developer needs a Gmail account to exercise self-registration locally. Only a deployed
 * environment sets {@code POS_MAIL_ENABLED=true}, and only once iac has actually
 * provisioned the SMTP secrets below (`tenant-registration-plan.md` section 6 —
 * deliberately not done until this code exists to read them).
 */
@Configuration
public class MailConfig {

    @Bean
    public EmailSender emailSender(AppProperties props) {
        return buildEmailSender(
                props.isMailEnabled(),
                props.getMailHost(),
                props.getMailPort(),
                props.getMailUsername(),
                props.getMailAppPassword(),
                props.getMailFromAddress());
    }

    /**
     * Package-private so a plain unit test can exercise the selection logic without a
     * Spring context — {@code JwtTokenService}'s package-private constructor is the same
     * device, for the same reason: this is pure wiring logic, not a test of Spring.
     */
    static EmailSender buildEmailSender(boolean enabled, String host, int port, String username,
                                        String appPassword, String fromAddress) {
        if (!enabled) {
            return new LoggingEmailSender();
        }

        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(requireSet(host, "pos.mail.host"));
        sender.setPort(port);
        sender.setUsername(requireSet(username, "pos.mail.username"));
        sender.setPassword(requireSet(appPassword, "pos.mail.appPassword"));

        // STARTTLS on 587, the plan's Decision #2 (a personal Gmail/Workspace account's
        // SMTP relay) — 465/SMTPS or unauthenticated 25 are not what that decision picked.
        Properties javaMailProperties = sender.getJavaMailProperties();
        javaMailProperties.put("mail.transport.protocol", "smtp");
        javaMailProperties.put("mail.smtp.auth", "true");
        javaMailProperties.put("mail.smtp.starttls.enable", "true");

        return new JavaMailEmailSender(sender, requireSet(fromAddress, "pos.mail.fromAddress"));
    }

    /**
     * The mail equivalent of {@code JwtTokenService} rejecting the placeholder JWT
     * secret: fail loudly at startup rather than construct a sender that can only fail
     * per-request once a real registration tries to use it. Unreachable while
     * {@code pos.mail.enabled} is false, which is every environment except a deployed
     * one with real credentials.
     */
    private static String requireSet(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    property + " is not set, but pos.mail.enabled=true. Set it (see "
                            + "application.properties), or leave pos.mail.enabled off.");
        }
        return value;
    }
}

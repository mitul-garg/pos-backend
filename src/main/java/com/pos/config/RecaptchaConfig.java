package com.pos.config;

import com.pos.util.GoogleRecaptchaVerifier;
import com.pos.util.NoopRecaptchaVerifier;
import com.pos.util.RecaptchaVerifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires {@link RecaptchaVerifier} (peer-review Phase 0) — a root-context config,
 * registered in {@link WebAppInitializer} beside {@link MailConfig}: the bean has to
 * exist before any request arrives, since {@code TenantRegistrationService} (a
 * root-context {@code @Service}) takes it as a constructor dependency.
 *
 * <p><b>Two implementations, selected by an {@code if}, not
 * {@code @ConditionalOnProperty}</b> — the same shape {@link MailConfig} uses for
 * {@code EmailSender}, for the identical reason: no Spring Boot auto-configuration
 * here (CONVENTIONS.md).
 *
 * <p>Defaults to {@link NoopRecaptchaVerifier} ({@code pos.recaptcha.enabled=false}),
 * so {@code mvn test} and an out-of-the-box {@code mvn jetty:run} never make a real
 * network call to Google — no developer needs a reCAPTCHA site registration to
 * exercise self-registration locally. Only a deployed environment sets
 * {@code POS_RECAPTCHA_ENABLED=true}, and only once iac has actually provisioned the
 * secret below.
 */
@Configuration
public class RecaptchaConfig {

    @Bean
    public RecaptchaVerifier recaptchaVerifier(AppProperties props) {
        return buildVerifier(props.isRecaptchaEnabled(), props.getRecaptchaSecret());
    }

    /**
     * Package-private so a plain unit test can exercise the selection logic without a
     * Spring context — {@code MailConfig.buildEmailSender} is the same device, for the
     * same reason: this is pure wiring logic, not a test of Spring.
     */
    static RecaptchaVerifier buildVerifier(boolean enabled, String secret) {
        if (!enabled) {
            return new NoopRecaptchaVerifier();
        }
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "pos.recaptcha.secret is not set, but pos.recaptcha.enabled=true. Set it "
                            + "(see application.properties), or leave pos.recaptcha.enabled off.");
        }
        return new GoogleRecaptchaVerifier(secret);
    }
}

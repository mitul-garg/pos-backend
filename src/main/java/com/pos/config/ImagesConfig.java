package com.pos.config;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

import com.google.auth.oauth2.ServiceAccountCredentials;
import com.pos.util.images.GcsImageSigner;
import com.pos.util.images.ImageSigner;
import com.pos.util.images.NoopImageSigner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires {@link ImageSigner} (peer-review Phase 3) — a root-context config, registered
 * in {@link WebAppInitializer} beside {@link MailConfig}/{@link RecaptchaConfig}: the
 * bean has to exist before any request arrives, since {@code ProductService} takes it
 * as a constructor dependency.
 *
 * <p><b>Two implementations, selected by an {@code if}, not
 * {@code @ConditionalOnProperty}</b> — the same shape {@link MailConfig}/
 * {@link RecaptchaConfig} use, for the identical reason (no Spring Boot
 * auto-configuration, CONVENTIONS.md).
 *
 * <p>Defaults to {@link NoopImageSigner} ({@code pos.images.enabled=false}), so
 * {@code mvn test} and an out-of-the-box {@code mvn jetty:run} never need a real GCP
 * service-account key on disk. Only a deployed environment — or a developer's own
 * {@code application-local.properties} pointing at a locally-generated test key, the
 * same override path {@code pos.recaptcha.enabled} documents — sets
 * {@code pos.images.enabled=true}.
 */
@Configuration
public class ImagesConfig {

    @Bean
    public ImageSigner imageSigner(AppProperties props) {
        return buildSigner(
                props.isImagesEnabled(),
                props.getImagesBucket(),
                props.getImagesSignerKeyPath(),
                props.getImagesMaxSizeBytes(),
                props.getImagesUploadUrlTtlMinutes(),
                props.getImagesReadUrlTtlMinutes());
    }

    /**
     * Package-private so a plain unit test can exercise the selection logic without a
     * Spring context — {@code RecaptchaConfig.buildVerifier}/{@code MailConfig.buildEmailSender}
     * are the same device, for the same reason.
     */
    static ImageSigner buildSigner(boolean enabled, String bucket, String signerKeyPath, long maxSizeBytes,
                                   int uploadUrlTtlMinutes, int readUrlTtlMinutes) {
        if (!enabled) {
            return new NoopImageSigner();
        }
        String bucketName = requireSet(bucket, "pos.images.bucket");
        String keyPath = requireSet(signerKeyPath, "pos.images.signerKeyPath");

        ServiceAccountCredentials credentials;
        try (InputStream keyStream = new FileInputStream(keyPath)) {
            credentials = ServiceAccountCredentials.fromStream(keyStream);
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "Failed to load the GCS image-signer key from pos.images.signerKeyPath=" + keyPath, ex);
        }
        return new GcsImageSigner(credentials, bucketName, maxSizeBytes, uploadUrlTtlMinutes, readUrlTtlMinutes);
    }

    /**
     * The images equivalent of {@code MailConfig.requireSet}/{@code RecaptchaConfig}'s
     * own check: fail loudly at startup rather than construct a signer that can only
     * fail per-request once a real upload tries to use it.
     */
    private static String requireSet(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    property + " is not set, but pos.images.enabled=true. Set it (see "
                            + "application.properties), or leave pos.images.enabled off.");
        }
        return value;
    }
}

package com.pos.config;

import com.pos.util.images.GcsImageSigner;
import com.pos.util.images.ImageSigner;
import com.pos.util.images.NoopImageSigner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code ImageSigner} selection logic, with no Spring context —
 * {@code RecaptchaConfigTest} is the identical shape for the identical reason:
 * {@code ImagesConfig.buildSigner} is pure wiring.
 */
@DisplayName("ImagesConfig's ImageSigner selection")
class ImagesConfigTest {

    @Test
    @DisplayName("defaults to a no-op signer when disabled, even with nothing else configured")
    void noopWhenDisabled() {
        ImageSigner signer = ImagesConfig.buildSigner(false, null, null, 5_242_880L, 15, 60);

        assertInstanceOf(NoopImageSigner.class, signer);
    }

    @Test
    @DisplayName("fails at startup when enabled with no bucket")
    void rejectsMissingBucketWhenEnabled() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ImagesConfig.buildSigner(true, null, "/tmp/key.json", 5_242_880L, 15, 60));

        assertTrue(ex.getMessage().contains("pos.images.bucket"),
                () -> "expected message to name pos.images.bucket, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("fails at startup when enabled with no signer key path")
    void rejectsMissingKeyPathWhenEnabled() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ImagesConfig.buildSigner(true, "a-bucket", null, 5_242_880L, 15, 60));

        assertTrue(ex.getMessage().contains("pos.images.signerKeyPath"),
                () -> "expected message to name pos.images.signerKeyPath, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("fails at startup, not per-request, when the key file doesn't exist")
    void rejectsUnreadableKeyFile() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ImagesConfig.buildSigner(true, "a-bucket", "/no/such/file.json", 5_242_880L, 15, 60));

        assertTrue(ex.getMessage().contains("pos.images.signerKeyPath"),
                () -> "expected message to name pos.images.signerKeyPath, got: " + ex.getMessage());
    }

    // No "builds a real GcsImageSigner once enabled and configured" case here, unlike
    // RecaptchaConfigTest's googleWhenEnabledAndConfigured -- that would need a real
    // (or synthetic-but-file-backed) key on disk. GcsImageSignerTest already proves
    // the signer class itself works, against a synthetic in-memory key; the live
    // round trip against a real key and a real bucket was verified manually (see the
    // peer-review commit message), the same "no committed test opens a real network
    // connection" line GoogleRecaptchaVerifierTest already draws.
    @DisplayName("builds a real GcsImageSigner once enabled with a readable key file")
    @Test
    void gcsWhenEnabledAndConfigured(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir)
            throws Exception {
        java.nio.file.Path keyFile = tempDir.resolve("key.json");
        java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        java.security.KeyPair keyPair = generator.generateKeyPair();
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + java.util.Base64.getMimeEncoder(64, "\n".getBytes())
                        .encodeToString(keyPair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        String keyJson = "{"
                + "\"type\":\"service_account\","
                + "\"project_id\":\"test-project\","
                + "\"private_key_id\":\"test-key-id\","
                + "\"private_key\":\"" + pem.replace("\n", "\\n") + "\","
                + "\"client_email\":\"pos-image-signer@test-project.iam.gserviceaccount.com\","
                + "\"client_id\":\"12345\""
                + "}";
        java.nio.file.Files.writeString(keyFile, keyJson);

        ImageSigner signer =
                ImagesConfig.buildSigner(true, "a-bucket", keyFile.toString(), 5_242_880L, 15, 60);

        assertInstanceOf(GcsImageSigner.class, signer);
    }
}

package com.pos.util.images;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unlike {@code NoopRecaptchaVerifierTest}, this proves the opposite of
 * "always passes" — see this class's own Javadoc for why there's no harmless
 * no-op substitute for "upload to a bucket that doesn't exist", and why
 * {@code ProductService}'s {@code pos.images.enabled} check is the real gate.
 */
@DisplayName("NoopImageSigner")
class NoopImageSignerTest {

    private final ImageSigner signer = new NoopImageSigner();

    @Test
    @DisplayName("signUploadUrl throws rather than returning a fake URL")
    void signUploadUrlThrows() {
        assertThrows(UnsupportedOperationException.class, () -> signer.signUploadUrl("42/1/image", "image/jpeg"));
    }

    @Test
    @DisplayName("signReadUrl throws rather than returning a fake URL")
    void signReadUrlThrows() {
        assertThrows(UnsupportedOperationException.class, () -> signer.signReadUrl("42/1/image"));
    }

    @Test
    @DisplayName("delete throws rather than silently doing nothing")
    void deleteThrows() {
        assertThrows(UnsupportedOperationException.class, () -> signer.delete("42/1/image"));
    }
}

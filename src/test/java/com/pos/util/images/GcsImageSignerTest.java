package com.pos.util.images;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import com.google.auth.oauth2.ServiceAccountCredentials;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A pure unit test — no real GCP key, no network call — for the hand-rolled V4
 * signing math itself. The {@code Clock}-injectable constructor is the
 * {@code LoginAttemptGuard}/{@code ApiRateLimiter} reference shape
 * (backend/prompts/CONVENTIONS.md) for a class carrying a production-meaningful
 * value a test needs to pin against a known instant.
 *
 * <p><b>This is not the authoritative correctness proof</b> — that was a real,
 * live round trip against the actual bucket (peer-review commit message):
 * a signed PUT succeeded and was rejected on a wrong {@code Content-Type} or an
 * oversized body, a signed GET round-tripped real content, an unsigned GET got
 * 403, and {@code delete} genuinely removed the object. What this test proves
 * instead is that the signature is real RSA-SHA256 over the exact string this
 * class's own Javadoc says it signs — using a synthetic in-memory keypair
 * generated fresh per run, verified with the matching public key, so a
 * regression here fails a fast test instead of only surfacing against a live
 * bucket.
 */
@DisplayName("GcsImageSigner")
class GcsImageSignerTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-01-15T10:30:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
    private static final String CLIENT_EMAIL = "pos-image-signer@test-project.iam.gserviceaccount.com";
    private static final String BUCKET = "test-project-pos-app-images";

    private static KeyPair keyPair;
    private static ServiceAccountCredentials credentials;

    @BeforeAll
    static void generateSyntheticCredentials() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();

        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                        .encodeToString(keyPair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        credentials = ServiceAccountCredentials.fromPkcs8(
                "test-client-id", CLIENT_EMAIL, pem, "test-key-id", List.of());
    }

    private GcsImageSigner signer() {
        // 5MB, 15-minute upload TTL, 60-minute read TTL -- the real production defaults.
        return new GcsImageSigner(credentials, BUCKET, 5_242_880L, 15, 60, FIXED_CLOCK);
    }

    @Test
    @DisplayName("upload URL: correct host, path, algorithm, credential scope and expiry")
    void uploadUrlShape() {
        SignedUpload upload = signer().signUploadUrl("42/1001/image", "image/jpeg");
        Map<String, String> params = queryParams(upload.url());

        assertTrue(upload.url().startsWith(
                "https://storage.googleapis.com/" + BUCKET + "/42/1001/image?"));
        assertEquals("GOOG4-RSA-SHA256", params.get("X-Goog-Algorithm"));
        assertEquals(CLIENT_EMAIL + "/20260115/auto/storage/goog4_request", params.get("X-Goog-Credential"));
        assertEquals("20260115T103000Z", params.get("X-Goog-Date"));
        assertEquals("900", params.get("X-Goog-Expires"), "15 minutes, in seconds");
        assertEquals("content-type;host;x-goog-content-length-range", params.get("X-Goog-SignedHeaders"));

        assertEquals("image/jpeg", upload.requiredHeaders().get("Content-Type"));
        assertEquals("0,5242880", upload.requiredHeaders().get("x-goog-content-length-range"));
    }

    @Test
    @DisplayName("read URL: same shape, GET's own expiry, no extra signed headers")
    void readUrlShape() {
        String url = signer().signReadUrl("42/1001/image");
        Map<String, String> params = queryParams(url);

        assertTrue(url.startsWith("https://storage.googleapis.com/" + BUCKET + "/42/1001/image?"));
        assertEquals("3600", params.get("X-Goog-Expires"), "60 minutes, in seconds");
        assertEquals("host", params.get("X-Goog-SignedHeaders"));
    }

    @Test
    @DisplayName("rejects a content type outside the allowed set")
    void rejectsDisallowedContentType() {
        assertThrows(IllegalArgumentException.class,
                () -> signer().signUploadUrl("42/1001/image", "application/pdf"));
    }

    @Test
    @DisplayName("the emitted signature is a genuine RSA-SHA256 signature over GOOG4's documented string-to-sign")
    void signatureVerifiesAgainstThePublicKey() throws Exception {
        String url = signer().signReadUrl("42/1001/image");
        Map<String, String> params = queryParams(url);
        byte[] signatureBytes = HexFormat.of().parseHex(params.get("X-Goog-Signature"));

        // Reconstructed independently from https://cloud.google.com/storage/docs/authentication/signatures
        // -- GET, this exact resource path, this exact canonical query string (everything
        // except X-Goog-Signature itself), one signed header ("host"), UNSIGNED-PAYLOAD.
        String resourcePath = "/" + BUCKET + "/42/1001/image";
        String canonicalQueryString = url.substring(url.indexOf('?') + 1, url.indexOf("&X-Goog-Signature="));
        String canonicalRequest = "GET\n"
                + resourcePath + "\n"
                + canonicalQueryString + "\n"
                + "host:storage.googleapis.com\n"
                + "\n"
                + "host\n"
                + "UNSIGNED-PAYLOAD";
        String canonicalRequestHash = sha256Hex(canonicalRequest);
        String stringToSign = "GOOG4-RSA-SHA256\n"
                + "20260115T103000Z\n"
                + "20260115/auto/storage/goog4_request\n"
                + canonicalRequestHash;

        Signature verifier = Signature.getInstance("SHA256withRSA");
        PublicKey publicKey = keyPair.getPublic();
        verifier.initVerify(publicKey);
        verifier.update(stringToSign.getBytes(StandardCharsets.UTF_8));

        assertTrue(verifier.verify(signatureBytes),
                "the signature GcsImageSigner emitted does not verify against the documented "
                        + "GOOG4-RSA-SHA256 string-to-sign for these exact inputs");
    }

    private static String sha256Hex(String value) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static Map<String, String> queryParams(String url) {
        String query = URI.create(url).getRawQuery();
        Map<String, String> result = new java.util.LinkedHashMap<>();
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            result.put(key, value);
        }
        return result;
    }
}

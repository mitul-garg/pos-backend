package com.pos.util.images;

/**
 * Mints short-lived signed GCS URLs and deletes objects for product images
 * (peer-review Phase 3) — the mechanism behind "image bytes never transit the
 * backend": the browser PUTs/GETs the bucket directly, and this is what proves to
 * GCS that a particular upload or read was actually authorized by this backend,
 * after it ran the real auth/role/tenant check.
 *
 * <p>An interface, not a single class, for the identical reason {@code
 * RecaptchaVerifier} is one ({@code RecaptchaConfig}'s Javadoc): {@code mvn test}
 * and an out-of-the-box {@code mvn jetty:run} must never require a real GCP
 * service-account key. {@code ImagesConfig} selects between {@link GcsImageSigner}
 * and {@link NoopImageSigner} the same way {@code RecaptchaConfig} selects a
 * {@code RecaptchaVerifier}.
 *
 * <p><b>Every method takes an object path, never a bucket</b> — there is exactly
 * one bucket (iac's {@code storage.tf}), configured once in {@code ImagesConfig},
 * not a per-call parameter. The path scheme itself
 * ({@code {tenantId}/{productId}/image}, fixed per product) is the caller's
 * concern ({@code ProductService}), not this interface's.
 */
public interface ImageSigner {

    /**
     * @param objectPath  e.g. {@code "42/1001/image"} — no leading slash
     * @param contentType one of the allowed image types ({@link GcsImageSigner#ALLOWED_CONTENT_TYPES}),
     *                    validated by the caller before this is reached
     * @return a signed {@code PUT} URL plus the headers the upload request must carry
     */
    SignedUpload signUploadUrl(String objectPath, String contentType);

    /**
     * @param objectPath e.g. {@code "42/1001/image"}
     * @return a signed {@code GET} URL, valid for {@code pos.images.readUrlTtlMinutes}
     */
    String signReadUrl(String objectPath);

    /**
     * Deletes the object at {@code objectPath} if it exists. A no-op, not an error,
     * if it doesn't — the same idempotent-delete shape {@code ProductService.deactivate}
     * already uses for a product that's already inactive.
     */
    void delete(String objectPath);
}

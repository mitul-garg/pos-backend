package com.pos.util.images;

/**
 * Selected by {@code ImagesConfig} whenever {@code pos.images.enabled=false} — every
 * environment except a deployed one with a real signing key provisioned
 * (iac/prompts/06-product-images.md).
 *
 * <p><b>Unlike {@code LoggingEmailSender}/{@code NoopRecaptchaVerifier}, this is not
 * meant to ever actually run.</b> Those two exist because their surrounding feature
 * (self-registration) is always live — logging an email or always-passing a captcha
 * check is a genuine, useful local-dev substitute for a flow a developer needs to
 * exercise. There is no equivalent harmless substitute for "upload an image to a
 * bucket that doesn't exist" — a fake signed URL would just fail differently (and
 * more confusingly) when the frontend tried to use it. So the real gate is
 * {@code ProductService} checking {@code AppProperties.isImagesEnabled()} up front
 * and rejecting cleanly (400) before ever reaching a signer; this class is the
 * fail-loudly backstop if that check is ever bypassed, the same instinct
 * {@code MailConfig.requireSet} uses for a misconfigured {@code pos.mail.enabled=true}
 * rather than silently constructing something broken.
 */
public class NoopImageSigner implements ImageSigner {

    private static final String MESSAGE =
            "Product images are not enabled on this deployment (pos.images.enabled=false). "
                    + "This should have been rejected before reaching ImageSigner — see "
                    + "ProductService's own pos.images.enabled check.";

    @Override
    public SignedUpload signUploadUrl(String objectPath, String contentType) {
        throw new UnsupportedOperationException(MESSAGE);
    }

    @Override
    public String signReadUrl(String objectPath) {
        throw new UnsupportedOperationException(MESSAGE);
    }

    @Override
    public void delete(String objectPath) {
        throw new UnsupportedOperationException(MESSAGE);
    }
}

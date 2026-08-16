package com.pos.model;

/**
 * Input for {@code POST /api/products/{id}/image-upload-url} (peer-review Phase 3) — the
 * one thing the caller declares, since everything else (object path, TTL, size ceiling)
 * is server-derived. Validated against {@code GcsImageSigner.ALLOWED_CONTENT_TYPES} in
 * {@code ProductService.mintImageUploadUrl}, not here — same "the service validates,
 * the Form just carries" division {@link ProductForm} already follows.
 */
public class ImageUploadUrlForm {

    private String contentType;

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }
}

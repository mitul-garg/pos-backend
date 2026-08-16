package com.pos.util.images;

import java.util.Map;

/**
 * A signed {@code PUT} URL plus the exact headers the client's own upload request
 * must carry (peer-review Phase 3). Not optional extras — {@link GcsImageSigner}
 * bakes {@code Content-Type} and {@code x-goog-content-length-range} into the
 * <i>signed headers</i> that make up the signature itself, so a PUT that omits or
 * changes either one fails GCS's own signature check before any bytes are stored.
 * That is the actual enforcement mechanism for the content-type/size limits this
 * feature's design calls for — not a check this backend performs, one GCS performs
 * on the frontend's direct upload.
 *
 * @param url            the signed {@code PUT} URL, valid until {@code pos.images.uploadUrlTtlMinutes}
 * @param requiredHeaders header name → value, case-insensitive names, every one of
 *                       which the client's PUT request must send verbatim
 */
public record SignedUpload(String url, Map<String, String> requiredHeaders) {
}

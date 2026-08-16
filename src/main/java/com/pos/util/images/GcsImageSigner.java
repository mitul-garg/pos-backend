package com.pos.util.images;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import com.google.auth.oauth2.ServiceAccountCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The real {@link ImageSigner} — hand-rolled GCS V4 URL signing (pom.xml's comment on
 * {@code google-auth-library-oauth2-http} explains why this isn't the full
 * {@code google-cloud-storage} SDK) against Google's own documented algorithm
 * (<a href="https://cloud.google.com/storage/docs/authentication/signatures#signing-process">signing-process</a>),
 * plus one plain REST call for deletion.
 *
 * <p><b>Signing is pure local RSA-SHA256 via {@link ServiceAccountCredentials#sign},
 * with zero network calls</b> — that's the entire reason this design chose a
 * downloaded key over the VM's attached service account via IAM {@code signBlob}
 * (iac/requirements.md decision #21): minting a signed URL is safe to do on every
 * row of a paginated products list without reintroducing the N+1-shaped cost Phase 1
 * spent a whole item eliminating for orders/returns. {@code delete} is the one method
 * here that *does* make a network call (an OAuth2-authenticated {@code DELETE} against
 * the JSON API) — acceptable because it only runs on an explicit, rare, user-triggered
 * "remove this product's image" action, not on every read.
 */
public class GcsImageSigner implements ImageSigner {

    private static final Logger log = LoggerFactory.getLogger(GcsImageSigner.class);

    /**
     * Path-style host, not the bucket-as-subdomain form — a bucket name containing a
     * dot (this project's is {@code <project_id>-pos-app-images}, no dots, but nothing
     * should rely on that) breaks TLS SNI under the vhost-style URL, and path-style
     * keeps the {@code host} canonical header a fixed literal regardless of bucket name.
     */
    private static final String HOST = "storage.googleapis.com";

    /** Enforced by GCS itself via the signed {@code Content-Type} header — see {@link SignedUpload}. */
    public static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private final ServiceAccountCredentials credentials;
    private final String bucket;
    private final long maxSizeBytes;
    private final int uploadUrlTtlSeconds;
    private final int readUrlTtlSeconds;
    private final Clock clock;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public GcsImageSigner(ServiceAccountCredentials credentials, String bucket, long maxSizeBytes,
                          int uploadUrlTtlMinutes, int readUrlTtlMinutes) {
        this(credentials, bucket, maxSizeBytes, uploadUrlTtlMinutes, readUrlTtlMinutes, Clock.systemUTC());
    }

    /**
     * Package-private, clock-injectable constructor for a pure unit test that proves
     * the signed URL's shape (expiry, credential scope, query params) without a real
     * key or network access — {@code LoginAttemptGuard}/{@code ApiRateLimiter}'s
     * reference shape (backend/prompts/CONVENTIONS.md) for a class carrying a real,
     * production-meaningful value that a test needs to pin against a known instant.
     */
    GcsImageSigner(ServiceAccountCredentials credentials, String bucket, long maxSizeBytes,
                   int uploadUrlTtlMinutes, int readUrlTtlMinutes, Clock clock) {
        this.credentials = credentials;
        this.bucket = bucket;
        this.maxSizeBytes = maxSizeBytes;
        this.uploadUrlTtlSeconds = uploadUrlTtlMinutes * 60;
        this.readUrlTtlSeconds = readUrlTtlMinutes * 60;
        this.clock = clock;
    }

    @Override
    public SignedUpload signUploadUrl(String objectPath, String contentType) {
        if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new IllegalArgumentException("Unsupported content type: " + contentType);
        }
        String sizeRange = "0," + maxSizeBytes;

        Map<String, String> signedExtraHeaders = new LinkedHashMap<>();
        signedExtraHeaders.put("content-type", contentType);
        signedExtraHeaders.put("x-goog-content-length-range", sizeRange);
        String url = sign("PUT", objectPath, uploadUrlTtlSeconds, signedExtraHeaders);

        Map<String, String> requiredHeaders = new LinkedHashMap<>();
        requiredHeaders.put("Content-Type", contentType);
        requiredHeaders.put("x-goog-content-length-range", sizeRange);
        return new SignedUpload(url, requiredHeaders);
    }

    @Override
    public String signReadUrl(String objectPath) {
        return sign("GET", objectPath, readUrlTtlSeconds, Map.of());
    }

    /**
     * A plain OAuth2-authenticated JSON API call — {@code credentials.getRequestMetadata}
     * mints (and internally caches/refreshes) the bearer token, no hand-rolled OAuth2
     * flow needed. 404 counts as success: an already-gone object is the same end state
     * as a freshly-deleted one, matching {@code ProductService.deactivate}'s own
     * idempotent-delete shape.
     */
    @Override
    public void delete(String objectPath) {
        URI uri = URI.create("https://storage.googleapis.com/storage/v1/b/" + bucket + "/o/"
                + uriEncode(objectPath, true));
        try {
            Map<String, List<String>> authHeaders = credentials.getRequestMetadata(uri);
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri)
                    .DELETE()
                    .timeout(Duration.ofSeconds(10));
            authHeaders.forEach((name, values) -> values.forEach(value -> requestBuilder.header(name, value)));

            HttpResponse<String> response =
                    httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status != 200 && status != 204 && status != 404) {
                log.error("GCS object delete failed: {} -> HTTP {} {}", objectPath, status, response.body());
                throw new IllegalStateException("Failed to delete image object: HTTP " + status);
            }
        } catch (IOException ex) {
            log.error("GCS object delete call failed for {}", objectPath, ex);
            throw new IllegalStateException("Failed to delete image object", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted deleting image object", ex);
        }
    }

    // --- V4 signing ---------------------------------------------------------------
    // https://cloud.google.com/storage/docs/authentication/signatures#signing-process
    // Same family of algorithm as AWS SigV4 (Google's docs say as much) -- canonical
    // request -> string to sign -> RSA-SHA256 signature -> appended to the query string.

    private String sign(String httpMethod, String objectPath, int ttlSeconds,
                        Map<String, String> extraSignedHeaders) {
        Instant now = clock.instant();
        String timestamp = TIMESTAMP_FORMAT.format(now);
        String datestamp = DATESTAMP_FORMAT.format(now);
        String credentialScope = datestamp + "/auto/storage/goog4_request";
        String credentialParam = credentials.getClientEmail() + "/" + credentialScope;

        TreeMap<String, String> headers = new TreeMap<>();
        headers.put("host", HOST);
        extraSignedHeaders.forEach((name, value) -> headers.put(name.toLowerCase(Locale.ROOT), value.trim()));
        String signedHeaders = String.join(";", headers.keySet());

        TreeMap<String, String> queryParams = new TreeMap<>();
        queryParams.put("X-Goog-Algorithm", "GOOG4-RSA-SHA256");
        queryParams.put("X-Goog-Credential", credentialParam);
        queryParams.put("X-Goog-Date", timestamp);
        queryParams.put("X-Goog-Expires", String.valueOf(ttlSeconds));
        queryParams.put("X-Goog-SignedHeaders", signedHeaders);

        // Query values are percent-encoded WITH slashes escaped (unlike the resource
        // path below) -- X-Goog-Credential's value contains literal '/'s and a query
        // parameter value has no path-separator meaning to preserve.
        String canonicalQueryString = queryParams.entrySet().stream()
                .map(e -> uriEncode(e.getKey(), true) + "=" + uriEncode(e.getValue(), true))
                .collect(Collectors.joining("&"));

        StringBuilder canonicalHeaders = new StringBuilder();
        headers.forEach((name, value) -> canonicalHeaders.append(name).append(':').append(value).append('\n'));

        // Slashes here ARE real path separators (path-style bucket/object addressing),
        // the opposite of the query-string case above -- each segment is encoded on
        // its own and rejoined with a literal '/'.
        String resourcePath = "/" + bucket + "/" + encodePathSegments(objectPath);

        String canonicalRequest = httpMethod + "\n"
                + resourcePath + "\n"
                + canonicalQueryString + "\n"
                + canonicalHeaders + "\n"
                + signedHeaders + "\n"
                + "UNSIGNED-PAYLOAD";

        String stringToSign = "GOOG4-RSA-SHA256\n"
                + timestamp + "\n"
                + credentialScope + "\n"
                + sha256Hex(canonicalRequest);

        String signature = HexFormat.of().formatHex(credentials.sign(stringToSign.getBytes(StandardCharsets.UTF_8)));

        return "https://" + HOST + resourcePath + "?" + canonicalQueryString + "&X-Goog-Signature=" + signature;
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is a JDK-guaranteed algorithm (every conformant JVM ships it) --
            // this can only mean a broken JVM installation, not a reachable runtime state.
            throw new IllegalStateException(ex);
        }
    }

    private static String encodePathSegments(String objectPath) {
        String[] segments = objectPath.split("/", -1);
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                result.append('/');
            }
            result.append(uriEncode(segments[i], true));
        }
        return result.toString();
    }

    /**
     * RFC 3986 unreserved-character percent-encoding — {@code java.net.URLEncoder} is
     * {@code application/x-www-form-urlencoded} (space becomes {@code +}), the wrong
     * scheme for a URI path or query string, which is why this exists instead of it.
     *
     * @param encodeSlash {@code false} to leave {@code /} unescaped (a real path
     *                    separator); {@code true} everywhere else
     */
    private static String uriEncode(String input, boolean encodeSlash) {
        StringBuilder result = new StringBuilder();
        for (byte b : input.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            boolean unreserved = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~';
            if (unreserved || (c == '/' && !encodeSlash)) {
                result.append(c);
            } else {
                result.append('%').append(String.format("%02X", b & 0xFF));
            }
        }
        return result.toString();
    }
}

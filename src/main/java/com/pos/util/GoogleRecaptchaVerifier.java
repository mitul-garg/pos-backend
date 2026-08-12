package com.pos.util;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The real {@link RecaptchaVerifier} — a server-to-server POST to Google's
 * {@code siteverify} endpoint
 * (<a href="https://developers.google.com/recaptcha/docs/verify">docs</a>).
 *
 * <p><b>{@code java.net.http.HttpClient}, not a new dependency</b> — the same
 * instinct {@code FixedWindowLimiter}'s Javadoc names for the rate limiters: this
 * project reaches for a library only once hand-rolling the problem stops being
 * simple, and "POST two form fields, read one JSON boolean" isn't there yet. Jackson
 * is already a dependency (every controller response goes through it), so parsing
 * the reply needs nothing new either — {@code ApiErrorResponder} sets the precedent
 * for a config/util class owning its own {@code ObjectMapper} instance rather than
 * reaching for the DI-managed one.
 */
public class GoogleRecaptchaVerifier implements RecaptchaVerifier {

    private static final Logger log = LoggerFactory.getLogger(GoogleRecaptchaVerifier.class);
    private static final URI SITEVERIFY_URI = URI.create("https://www.google.com/recaptcha/api/siteverify");
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final String secret;
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public GoogleRecaptchaVerifier(String secret) {
        this.secret = secret;
        this.httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    }

    /**
     * <b>Fails CLOSED</b> — a blank token, a token Google rejects, a malformed reply,
     * or simply not being able to reach Google at all all answer {@code false}. This
     * is the opposite of {@code EmailSender}'s log-and-continue on a failed send, and
     * deliberately so: a verification failure there would silently drop a real,
     * already-committed registration's confirmation email, but this check runs
     * *before* anything is written — the whole point of a CAPTCHA is to be the gate,
     * so an outage here should reject a submission (bot and human alike) rather than
     * silently wave every submission through it.
     */
    @Override
    public boolean verify(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        try {
            String body = "secret=" + urlEncode(secret) + "&response=" + urlEncode(token);
            HttpRequest request = HttpRequest.newBuilder(SITEVERIFY_URI)
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode json = mapper.readTree(response.body());
            return json.path("success").asBoolean(false);
        } catch (IOException ex) {
            log.error("reCAPTCHA verification call failed", ex);
            return false;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.error("reCAPTCHA verification call interrupted", ex);
            return false;
        }
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

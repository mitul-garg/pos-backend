package com.pos.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one-line always-pass predicate, given a name and cases anyway — same
 * treatment {@link HoneypotTest} gives {@link Honeypot#isTripped}.
 */
@DisplayName("NoopRecaptchaVerifier")
class NoopRecaptchaVerifierTest {

    private final RecaptchaVerifier verifier = new NoopRecaptchaVerifier();

    @Test
    @DisplayName("passes a real-looking token")
    void passesARealLookingToken() {
        assertTrue(verifier.verify("03AGdBq27abc-a-plausible-looking-token"));
    }

    @Test
    @DisplayName("passes a blank token too — the gate simply isn't there when disabled")
    void passesABlankToken() {
        assertTrue(verifier.verify(""));
        assertTrue(verifier.verify(null));
    }
}

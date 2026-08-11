package com.pos.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Honeypot")
class HoneypotTest {

    @Test
    @DisplayName("not tripped by null -- a real browser never sends the hidden field a value")
    void notTrippedByNull() {
        assertFalse(Honeypot.isTripped(null));
    }

    @Test
    @DisplayName("not tripped by an empty or whitespace-only value")
    void notTrippedByBlank() {
        assertFalse(Honeypot.isTripped(""));
        assertFalse(Honeypot.isTripped("   "));
    }

    @Test
    @DisplayName("tripped by anything else")
    void trippedByAnyContent() {
        assertTrue(Honeypot.isTripped("https://example.com"));
        assertTrue(Honeypot.isTripped("x"));
    }
}

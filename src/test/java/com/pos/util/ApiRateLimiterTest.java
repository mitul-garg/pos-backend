package com.pos.util;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the real production threshold (120 requests / 60 seconds, matching {@code
 * application.properties}' defaults) directly against the public test constructor — no
 * Spring context, so {@code application-test.properties}' generous override (see
 * {@link ApiRateLimiter}'s Javadoc) never enters into what this suite proves. The
 * underlying window/counter mechanism itself, including its concurrency guarantee, is
 * already proven by {@code RegistrationRateLimiterTest}, so this suite doesn't repeat
 * that — only that {@code ApiRateLimiter} is wired to its own numbers.
 */
@DisplayName("ApiRateLimiter")
class ApiRateLimiterTest {

    private static final int MAX_REQUESTS = 120;
    private static final Duration WINDOW = Duration.ofSeconds(60);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final ApiRateLimiter limiter = new ApiRateLimiter(MAX_REQUESTS, WINDOW, clock);

    @Nested
    @DisplayName("within one window")
    class WithinOneWindow {

        @Test
        @DisplayName("allows exactly the first MAX_REQUESTS and refuses the next")
        void allowsUpToTheLimit() {
            for (int i = 0; i < MAX_REQUESTS; i++) {
                assertTrue(limiter.allow("user:1"), "request " + (i + 1) + " should be allowed");
            }
            assertFalse(limiter.allow("user:1"), "the request past the limit should be refused");
        }

        @Test
        @DisplayName("tracks independent users independently")
        void independentKeys() {
            for (int i = 0; i < MAX_REQUESTS; i++) {
                limiter.allow("user:1");
            }
            assertFalse(limiter.allow("user:1"));
            assertTrue(limiter.allow("user:2"), "a different user must have their own budget");
        }
    }

    @Nested
    @DisplayName("across a window boundary")
    class AcrossWindows {

        @Test
        @DisplayName("resets once the window has elapsed")
        void resetsAfterTheWindow() {
            for (int i = 0; i < MAX_REQUESTS; i++) {
                limiter.allow("user:1");
            }
            assertFalse(limiter.allow("user:1"));

            clock.advance(WINDOW.plusSeconds(1));

            assertTrue(limiter.allow("user:1"), "a new window should have a fresh budget");
        }
    }

    /** A {@link Clock} the test can move forward on demand, standing in for real time passing. */
    private static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}

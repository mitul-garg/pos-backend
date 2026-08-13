package com.pos.util.ratelimit;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Confirms {@code LoginRateLimiter} is wired to its own numbers, not
 * {@code RegistrationRateLimiter}'s. The underlying window/counter mechanism
 * ({@link FixedWindowLimiter}) — including the concurrency guarantee — is already
 * proven by {@code RegistrationRateLimiterTest}, so this suite doesn't re-prove it.
 */
@DisplayName("LoginRateLimiter")
class LoginRateLimiterTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final LoginRateLimiter limiter = new LoginRateLimiter(clock);

    @Nested
    @DisplayName("within one window")
    class WithinOneWindow {

        @Test
        @DisplayName("allows exactly the first MAX_REQUESTS and refuses the next")
        void allowsUpToTheLimit() {
            for (int i = 0; i < LoginRateLimiter.MAX_REQUESTS; i++) {
                assertTrue(limiter.allow("1.2.3.4"), "request " + (i + 1) + " should be allowed");
            }
            assertFalse(limiter.allow("1.2.3.4"), "the request past the limit should be refused");
        }

        @Test
        @DisplayName("tracks independent IPs independently")
        void independentKeys() {
            for (int i = 0; i < LoginRateLimiter.MAX_REQUESTS; i++) {
                limiter.allow("1.2.3.4");
            }
            assertFalse(limiter.allow("1.2.3.4"));
            assertTrue(limiter.allow("5.6.7.8"), "a different IP must have its own budget");
        }
    }

    @Nested
    @DisplayName("across a window boundary")
    class AcrossWindows {

        @Test
        @DisplayName("resets once the window has elapsed")
        void resetsAfterTheWindow() {
            for (int i = 0; i < LoginRateLimiter.MAX_REQUESTS; i++) {
                limiter.allow("1.2.3.4");
            }
            assertFalse(limiter.allow("1.2.3.4"));

            clock.advance(LoginRateLimiter.WINDOW.plusSeconds(1));

            assertTrue(limiter.allow("1.2.3.4"), "a new window should have a fresh budget");
        }
    }

    /** A {@link Clock} the test can move forward on demand, standing in for real time passing. */
    private static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(java.time.Duration by) {
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

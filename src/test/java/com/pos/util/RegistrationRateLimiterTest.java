package com.pos.util;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * With a controllable {@link Clock} rather than a real sleep — the window is an hour,
 * and no test here should take one to prove that.
 */
@DisplayName("RegistrationRateLimiter")
class RegistrationRateLimiterTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final RegistrationRateLimiter limiter = new RegistrationRateLimiter(clock);

    @Nested
    @DisplayName("within one window")
    class WithinOneWindow {

        @Test
        @DisplayName("allows exactly the first MAX_REQUESTS and refuses the next")
        void allowsUpToTheLimit() {
            for (int i = 0; i < RegistrationRateLimiter.MAX_REQUESTS; i++) {
                assertTrue(limiter.allow("ip:1.2.3.4"), "request " + (i + 1) + " should be allowed");
            }
            assertFalse(limiter.allow("ip:1.2.3.4"), "the request past the limit should be refused");
        }

        @Test
        @DisplayName("keeps refusing every request after the limit, not just the first one over")
        void staysRefusedAfterTheLimit() {
            for (int i = 0; i < RegistrationRateLimiter.MAX_REQUESTS; i++) {
                limiter.allow("ip:1.2.3.4");
            }
            assertFalse(limiter.allow("ip:1.2.3.4"));
            assertFalse(limiter.allow("ip:1.2.3.4"));
            assertFalse(limiter.allow("ip:1.2.3.4"));
        }

        @Test
        @DisplayName("tracks independent keys independently -- one IP's budget doesn't touch another's")
        void independentKeys() {
            for (int i = 0; i < RegistrationRateLimiter.MAX_REQUESTS; i++) {
                limiter.allow("ip:1.2.3.4");
            }
            assertFalse(limiter.allow("ip:1.2.3.4"));
            assertTrue(limiter.allow("ip:5.6.7.8"), "a different key must have its own budget");
        }

        @Test
        @DisplayName("tracks per-endpoint keys independently -- register and resend don't share a budget")
        void independentEndpointsForTheSameIp() {
            for (int i = 0; i < RegistrationRateLimiter.MAX_REQUESTS; i++) {
                limiter.allow("register:1.2.3.4");
            }
            assertFalse(limiter.allow("register:1.2.3.4"));
            assertTrue(limiter.allow("resend:1.2.3.4"),
                    "resend-verification must have its own budget from the same IP");
        }
    }

    @Nested
    @DisplayName("across a window boundary")
    class AcrossWindows {

        @Test
        @DisplayName("resets once the window has elapsed")
        void resetsAfterTheWindow() {
            for (int i = 0; i < RegistrationRateLimiter.MAX_REQUESTS; i++) {
                limiter.allow("ip:1.2.3.4");
            }
            assertFalse(limiter.allow("ip:1.2.3.4"));

            clock.advance(RegistrationRateLimiter.WINDOW.plusSeconds(1));

            assertTrue(limiter.allow("ip:1.2.3.4"), "a new window should have a fresh budget");
        }

        @Test
        @DisplayName("does not reset a moment before the window elapses")
        void doesNotResetEarly() {
            for (int i = 0; i < RegistrationRateLimiter.MAX_REQUESTS; i++) {
                limiter.allow("ip:1.2.3.4");
            }
            clock.advance(RegistrationRateLimiter.WINDOW.minusSeconds(1));

            assertFalse(limiter.allow("ip:1.2.3.4"), "the old window hasn't elapsed yet");
        }
    }

    @Nested
    @DisplayName("under concurrency")
    class Concurrency {

        /**
         * The read-then-act gap this class's Javadoc claims {@code compute} closes --
         * asserted rather than trusted, the same reasoning {@code VariantSequenceIT}/
         * {@code LastAdminRaceIT} give for testing a lock instead of reviewing it. A
         * naive read-then-increment would let more than {@code MAX_REQUESTS} threads all
         * observe "still room" before any of them writes; this fires many more callers
         * than the limit at one key simultaneously and counts exactly how many were let
         * through.
         */
        @Test
        @DisplayName("never admits more than MAX_REQUESTS for one key, even raced")
        void neverExceedsTheLimitUnderContention() throws InterruptedException {
            int callers = 50;
            ExecutorService pool = Executors.newFixedThreadPool(callers);
            CountDownLatch ready = new CountDownLatch(callers);
            CountDownLatch go = new CountDownLatch(1);
            AtomicInteger admitted = new AtomicInteger(0);

            for (int i = 0; i < callers; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (limiter.allow("ip:1.2.3.4")) {
                        admitted.incrementAndGet();
                    }
                });
            }

            ready.await();
            go.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "callers did not finish in time");

            assertEquals(RegistrationRateLimiter.MAX_REQUESTS, admitted.get());
        }
    }

    /** A {@link Clock} the test can move forward on demand, standing in for a real hour passing. */
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

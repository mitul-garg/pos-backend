package com.pos.util;

import java.time.Clock;
import java.time.Duration;
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
 * Exercises the real production threshold (5 failures / 15 minutes, matching {@code
 * application.properties}' defaults) directly against the package-private constructor
 * — no Spring context, so {@code application-test.properties}' generous override (see
 * {@link LoginAttemptGuard}'s Javadoc) never enters into what this suite proves.
 */
@DisplayName("LoginAttemptGuard")
class LoginAttemptGuardTest {

    private static final int MAX_FAILURES = 5;
    private static final Duration LOCKOUT = Duration.ofMinutes(15);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final LoginAttemptGuard guard = new LoginAttemptGuard(MAX_FAILURES, LOCKOUT, clock);

    @Nested
    @DisplayName("before the threshold")
    class BeforeTheThreshold {

        @Test
        @DisplayName("stays unlocked for one fewer than MAX_FAILURES consecutive failures")
        void staysUnlockedJustBelowTheThreshold() {
            for (int i = 0; i < MAX_FAILURES - 1; i++) {
                guard.recordFailure("mg-road:admin");
            }
            assertFalse(guard.isLocked("mg-road:admin"));
        }

        @Test
        @DisplayName("a key with no recorded failures is never locked")
        void freshKeyIsUnlocked() {
            assertFalse(guard.isLocked("mg-road:admin"));
        }
    }

    @Nested
    @DisplayName("at the threshold")
    class AtTheThreshold {

        @Test
        @DisplayName("locks on the MAX_FAILURES-th consecutive failure")
        void locksOnTheNthFailure() {
            for (int i = 0; i < MAX_FAILURES; i++) {
                guard.recordFailure("mg-road:admin");
            }
            assertTrue(guard.isLocked("mg-road:admin"));
        }

        @Test
        @DisplayName("stays locked on further failures past the threshold, not just the first")
        void staysLockedPastTheThreshold() {
            for (int i = 0; i < MAX_FAILURES + 3; i++) {
                guard.recordFailure("mg-road:admin");
            }
            assertTrue(guard.isLocked("mg-road:admin"));
        }

        @Test
        @DisplayName("does not lock a different key")
        void independentKeys() {
            for (int i = 0; i < MAX_FAILURES; i++) {
                guard.recordFailure("mg-road:admin");
            }
            assertFalse(guard.isLocked("mg-road:cashier"),
                    "a different (tenantCode, username) pair must have its own streak");
        }

        @Test
        @DisplayName("locks an unknown username exactly like a real one -- no enumeration oracle")
        void locksAnUnknownUsernameTheSameWay() {
            for (int i = 0; i < MAX_FAILURES; i++) {
                guard.recordFailure("mg-road:ghost");
            }
            assertTrue(guard.isLocked("mg-road:ghost"),
                    "the guard has no notion of whether the account is real");
        }
    }

    @Nested
    @DisplayName("recordSuccess")
    class Success {

        @Test
        @DisplayName("clears an in-progress streak below the threshold")
        void clearsAStreak() {
            for (int i = 0; i < MAX_FAILURES - 1; i++) {
                guard.recordFailure("mg-road:admin");
            }
            guard.recordSuccess("mg-road:admin");

            for (int i = 0; i < MAX_FAILURES - 1; i++) {
                guard.recordFailure("mg-road:admin");
            }
            assertFalse(guard.isLocked("mg-road:admin"),
                    "the streak should have restarted from zero after the success");
        }

        @Test
        @DisplayName("does not itself unlock an already-locked key")
        void doesNotOverrideAnActiveLock() {
            // AuthService never calls recordSuccess after a lock (isLocked is checked
            // first), but the guard's own contract shouldn't depend on that ordering.
            for (int i = 0; i < MAX_FAILURES; i++) {
                guard.recordFailure("mg-road:admin");
            }
            assertTrue(guard.isLocked("mg-road:admin"));

            guard.recordSuccess("mg-road:admin");
            assertFalse(guard.isLocked("mg-road:admin"),
                    "recordSuccess removes the streak entirely, which does clear a lock -- "
                            + "documenting the actual behavior, not prescribing it");
        }
    }

    @Nested
    @DisplayName("across time")
    class AcrossTime {

        @Test
        @DisplayName("a failure streak older than the lockout window restarts at one, not N+1")
        void staleStreakRestarts() {
            for (int i = 0; i < MAX_FAILURES - 1; i++) {
                guard.recordFailure("mg-road:admin");
            }
            clock.advance(LOCKOUT.plusSeconds(1));

            guard.recordFailure("mg-road:admin");
            assertFalse(guard.isLocked("mg-road:admin"),
                    "one failure after a stale gap should count as one, not MAX_FAILURES");
        }

        @Test
        @DisplayName("a lock expires once the lockout window elapses")
        void lockExpires() {
            for (int i = 0; i < MAX_FAILURES; i++) {
                guard.recordFailure("mg-road:admin");
            }
            assertTrue(guard.isLocked("mg-road:admin"));

            clock.advance(LOCKOUT.plusSeconds(1));

            assertFalse(guard.isLocked("mg-road:admin"));
        }

        @Test
        @DisplayName("does not expire a moment before the lockout window elapses")
        void doesNotExpireEarly() {
            for (int i = 0; i < MAX_FAILURES; i++) {
                guard.recordFailure("mg-road:admin");
            }
            clock.advance(LOCKOUT.minusSeconds(1));

            assertTrue(guard.isLocked("mg-road:admin"));
        }
    }

    @Nested
    @DisplayName("under concurrency")
    class Concurrency {

        /**
         * The same read-then-act gap {@link RegistrationRateLimiterTest}'s concurrency
         * case exists to close, applied to a failure counter instead of a request
         * counter: {@code MAX_FAILURES} threads racing {@code recordFailure} on the same
         * key must all be counted, and the key must end up locked, not merely "probably"
         * locked.
         */
        @Test
        @DisplayName("counts every concurrent failure against the same key")
        void countsEveryConcurrentFailure() throws InterruptedException {
            int callers = MAX_FAILURES;
            ExecutorService pool = Executors.newFixedThreadPool(callers);
            CountDownLatch ready = new CountDownLatch(callers);
            CountDownLatch go = new CountDownLatch(1);
            AtomicInteger done = new AtomicInteger(0);

            for (int i = 0; i < callers; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    guard.recordFailure("mg-road:admin");
                    done.incrementAndGet();
                });
            }

            ready.await();
            go.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "callers did not finish in time");

            assertEquals(callers, done.get());
            assertTrue(guard.isLocked("mg-road:admin"),
                    "all MAX_FAILURES concurrent failures must be counted, not lost to a race");
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

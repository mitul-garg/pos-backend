package com.pos.util.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The shared fixed-window counter behind {@link RegistrationRateLimiter} and
 * {@link LoginRateLimiter} — extracted once a second caller needed the identical shape
 * (CONVENTIONS.md's cross-cutting rule: "a second copy is the moment to extract, by the
 * third it's already drift"). Package-private on purpose: this is plumbing for the rate
 * limiters in this package, not a general-purpose utility other packages should reach
 * for directly — each caller still gets its own {@code @Component} with its own
 * budget/window/keying convention, which is the part that actually varies per use case.
 *
 * <p>Everything below is the reasoning {@code RegistrationRateLimiter} originally
 * carried, unchanged by the extraction: <b>hand-rolled, no new dependency</b> — the same
 * instinct that already produced {@code TenantSequenceDao}'s per-tenant counter and
 * {@code AppUserDao.lockTenant}'s last-admin guard; a library (Bucket4j, Resilience4j)
 * would be more machinery than the problem needs at this project's scale. <b>Fixed
 * window, not sliding or token-bucket</b> — a caller at the boundary of a window can
 * burst up to the limit twice in quick succession (once near the end of one window, once
 * at the start of the next), accepted because this guard's job is "make abuse expensive
 * to clean up after," not "be precise."
 */
final class FixedWindowLimiter {

    private final int maxRequests;
    private final Duration window;
    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    FixedWindowLimiter(int maxRequests, Duration window, Clock clock) {
        this.maxRequests = maxRequests;
        this.window = window;
        this.clock = clock;
    }

    /**
     * Records one request against {@code key} and reports whether it's within budget.
     *
     * <p><b>The whole method is one {@link ConcurrentHashMap#compute}</b> — the
     * increment and the window-expiry check happen inside the map's per-key lock, not as
     * a read then a write. Two threads racing the same key at the same instant can never
     * both observe "one left" and both proceed — the in-memory equivalent of a row lock,
     * for a counter with no database row underneath it at all.
     *
     * @return {@code true} if this request is the {@code maxRequests}th or earlier in
     *         the current window for {@code key}, {@code false} once it's exceeded
     */
    boolean allow(String key) {
        Instant now = clock.instant();
        Window w = windows.compute(key, (k, existing) -> {
            if (existing == null || existing.expiresAt.isBefore(now)) {
                return new Window(now.plus(window), new AtomicInteger(1));
            }
            existing.count.incrementAndGet();
            return existing;
        });
        return w.count.get() <= maxRequests;
    }

    /** One key's current window: when it resets, and how many requests it's counted so far. */
    private record Window(Instant expiresAt, AtomicInteger count) {
    }
}

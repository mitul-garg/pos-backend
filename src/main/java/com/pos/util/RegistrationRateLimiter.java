package com.pos.util;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

/**
 * A per-key, fixed-window abuse guard (C9, {@code tenant-registration-plan.md} §4) for
 * {@code POST /api/tenants/register} and {@code /resend-verification} — both public,
 * both do real work (a DB write, an outbound email), and neither has an authenticated
 * caller to rate-limit by identity instead of IP.
 *
 * <p><b>Hand-rolled, no new dependency</b> — the same instinct that already produced
 * {@code TenantSequenceDao}'s per-tenant counter and {@code AppUserDao.lockTenant}'s
 * last-admin guard: this is simple enough that reaching for a library (Bucket4j,
 * Resilience4j) would be more machinery than the problem needs at this project's scale.
 *
 * <p><b>Keyed by client IP alone, deliberately, not tenant code</b> — there is no tenant
 * yet at {@code register} time, that's the thing being created. {@code
 * TenantRegistrationController} (C9(e)) composes a per-endpoint key (e.g. {@code
 * "register:" + ip}, {@code "resend:" + ip}) so the two routes get independent budgets,
 * but within one route the budget is the IP's alone: 5 registration attempts/hour from
 * one address, however many different tenant codes it tries. Coarse on purpose — a
 * shared IP (an office NAT) registering two unrelated stores in the same hour pays for
 * it, which the plan accepts explicitly as "the deliberately minimal answer for this
 * project's scale" (§8) rather than the more stateful per-code-too alternative, which a
 * bot defeats for free by varying the code anyway.
 *
 * <p><b>Fixed window, not sliding or token-bucket.</b> A caller at the boundary of a
 * window can burst up to {@link #MAX_REQUESTS} twice in quick succession (once near the
 * end of one window, once at the start of the next) — accepted for the same reason: a
 * sliding window is more state and more code for a guard whose job is "make scraping
 * expensive to clean up after", not "be precise".
 */
@Component
public class RegistrationRateLimiter {

    /** 5 requests/hour per key — the plan's own number, not tuned further. */
    static final int MAX_REQUESTS = 5;
    static final Duration WINDOW = Duration.ofHours(1);

    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public RegistrationRateLimiter() {
        this(Clock.systemUTC());
    }

    /**
     * Package-private so {@code RegistrationRateLimiterTest} can control time without a
     * real sleep — {@code JwtTokenService}'s dual-constructor split is the same device,
     * for the same reason: this is pure logic, not a test of Spring.
     */
    RegistrationRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /**
     * Records one request against {@code key} and reports whether it's within budget.
     *
     * <p><b>The whole method is one {@link ConcurrentHashMap#compute}</b> — the
     * increment and the window-expiry check happen inside the map's per-key lock, not as
     * a read then a write, which is exactly the read-then-act gap
     * {@code AppUserDao.lockTenant}'s Javadoc warns a plain read-then-write leaves open
     * for a database row. Two threads racing the same key at the same instant can never
     * both observe "4 used, one left" and both proceed — this is the in-memory
     * equivalent of a row lock, for a counter with no database row underneath it at all.
     *
     * @return {@code true} if this request is the {@link #MAX_REQUESTS}th or earlier in
     *         the current window for {@code key}, {@code false} once it's exceeded
     */
    public boolean allow(String key) {
        Instant now = clock.instant();
        Window window = windows.compute(key, (k, existing) -> {
            if (existing == null || existing.expiresAt.isBefore(now)) {
                return new Window(now.plus(WINDOW), new AtomicInteger(1));
            }
            existing.count.incrementAndGet();
            return existing;
        });
        return window.count.get() <= MAX_REQUESTS;
    }

    /** One key's current window: when it resets, and how many requests it's counted so far. */
    private record Window(Instant expiresAt, AtomicInteger count) {
    }
}

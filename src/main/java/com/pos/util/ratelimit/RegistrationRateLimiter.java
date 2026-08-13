package com.pos.util.ratelimit;

import java.time.Clock;
import java.time.Duration;

import org.springframework.stereotype.Component;

/**
 * A per-key, fixed-window abuse guard (C9, {@code tenant-registration-plan.md} §4) for
 * {@code POST /api/tenants/register} and {@code /resend-verification} — both public,
 * both do real work (a DB write, an outbound email), and neither has an authenticated
 * caller to rate-limit by identity instead of IP.
 *
 * <p>The window/counter mechanism lives in {@link FixedWindowLimiter}, shared with
 * {@link LoginRateLimiter} (peer-review Phase 0) — this class now owns only the numbers
 * and the reasoning specific to registration abuse.
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
 */
@Component
public class RegistrationRateLimiter {

    /** 5 requests/hour per key — the plan's own number, not tuned further. */
    static final int MAX_REQUESTS = 5;
    static final Duration WINDOW = Duration.ofHours(1);

    private final FixedWindowLimiter limiter;

    public RegistrationRateLimiter() {
        this(Clock.systemUTC());
    }

    /**
     * Package-private so {@code RegistrationRateLimiterTest} can control time without a
     * real sleep — {@code JwtTokenService}'s dual-constructor split is the same device,
     * for the same reason: this is pure logic, not a test of Spring.
     */
    RegistrationRateLimiter(Clock clock) {
        this.limiter = new FixedWindowLimiter(MAX_REQUESTS, WINDOW, clock);
    }

    /**
     * @return {@code true} if this request is the {@link #MAX_REQUESTS}th or earlier in
     *         the current window for {@code key}, {@code false} once it's exceeded
     */
    public boolean allow(String key) {
        return limiter.allow(key);
    }
}

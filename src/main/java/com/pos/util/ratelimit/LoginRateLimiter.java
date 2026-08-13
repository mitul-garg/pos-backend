package com.pos.util.ratelimit;

import java.time.Clock;
import java.time.Duration;

import org.springframework.stereotype.Component;

/**
 * A per-IP, fixed-window abuse guard for {@code POST /api/auth/login} (peer-review
 * Phase 0) — the single highest-value pre-auth attack surface in the app: a successful
 * login mints a working session, and until this class existed nothing stood between an
 * anonymous caller and unlimited password guesses.
 *
 * <p><b>Same mechanism as {@link RegistrationRateLimiter}, different numbers.</b> Both
 * delegate to {@link FixedWindowLimiter}; only the budget and the key differ, because
 * the two endpoints have very different legitimate traffic profiles. Registration is
 * rare — one store registers once — so 5/hour/IP is generous. Login happens every
 * shift, from every cashier, often from the same store IP; a budget tuned for
 * registration would lock out a real till on an ordinary morning. {@value
 * #MAX_REQUESTS} requests per IP per {@link #WINDOW} is deliberately loose — this
 * guard's job is to make distributed/scripted brute-forcing from one address expensive,
 * not to catch a legitimate handful of mistyped passwords. See {@link
 * LoginAttemptGuard} for the complementary per-account guard, which is what actually
 * bounds password-guessing against one specific user regardless of which IP it comes
 * from.
 *
 * <p>Keyed on IP alone — there is exactly one action behind this limiter, unlike
 * {@code RegistrationRateLimiter}'s two, so no action prefix is needed in the key.
 */
@Component
public class LoginRateLimiter {

    /** Loose on purpose — see class Javadoc. Not shared with registration's 5/hour. */
    static final int MAX_REQUESTS = 20;
    static final Duration WINDOW = Duration.ofMinutes(15);

    private final FixedWindowLimiter limiter;

    public LoginRateLimiter() {
        this(Clock.systemUTC());
    }

    /** Package-private so {@code LoginRateLimiterTest} can control time without a real sleep. */
    LoginRateLimiter(Clock clock) {
        this.limiter = new FixedWindowLimiter(MAX_REQUESTS, WINDOW, clock);
    }

    /**
     * @return {@code true} if this request is the {@link #MAX_REQUESTS}th or earlier in
     *         the current window for {@code ip}, {@code false} once it's exceeded
     */
    public boolean allow(String ip) {
        return limiter.allow(ip);
    }
}

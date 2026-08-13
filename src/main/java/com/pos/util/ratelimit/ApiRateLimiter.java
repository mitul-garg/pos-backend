package com.pos.util.ratelimit;

import java.time.Clock;
import java.time.Duration;

import com.pos.config.AppProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * A per-user, fixed-window request budget across <b>all</b> authenticated
 * {@code /api/**} traffic (peer-review Phase 0) — not just login, which {@link
 * LoginRateLimiter}/{@link LoginAttemptGuard} already cover. Checked in {@code
 * JwtAuthenticationFilter} right after a token resolves to a session, so the key is
 * always a real, already-authenticated user id — there is no anonymous case to key by
 * IP here, unlike the login guards.
 *
 * <p><b>Why this exists distinctly from login rate limiting.</b> The threat this
 * guards against isn't a login guesser, it's a handful of concurrent authenticated
 * clients — a runaway UI bug, a compromised token, a scripted client — hammering any
 * list/search endpoint. {@code pos.db.pool.maxSize} is deliberately small (5, sized
 * for the e2-micro/free-tier connection cap; see {@link AppProperties}), so exhausting
 * it starves every <i>other</i> tenant sharing the VM, not just the caller — a
 * whole-deployment denial of service from one misbehaving token, not merely a
 * noisy-neighbour problem.
 *
 * <p>Same {@link FixedWindowLimiter} mechanism as the other two rate limiters in this
 * package; the threshold/window come from {@link AppProperties} rather than a
 * hardcoded constant, for the same test-fixture-collision reason {@link
 * LoginAttemptGuard} is configurable — see that class's Javadoc. {@code
 * application-test.properties} raises the threshold far above what the shared IT
 * suite could ever accumulate incidentally; {@code ApiRateLimiterTest} is what proves
 * the real production number.
 */
@Component
public class ApiRateLimiter {

    private final FixedWindowLimiter limiter;

    @Autowired
    public ApiRateLimiter(AppProperties props) {
        this(props.getApiRateLimitMaxRequests(),
                Duration.ofSeconds(props.getApiRateLimitWindowSeconds()),
                Clock.systemUTC());
    }

    /**
     * Public, unlike the equivalent test constructor on {@link RegistrationRateLimiter}/
     * {@link LoginRateLimiter}/{@link LoginAttemptGuard} — this class's one caller,
     * {@code JwtAuthenticationFilter}, lives in {@code com.pos.config}, not {@code
     * com.pos.util}, and its own test constructs collaborators by hand with no Spring
     * context (matching {@code StubServiceConfig}'s philosophy). {@link AppProperties}
     * exposes no setters (by design — see its Javadoc), so a package-private
     * constructor here would leave that test with no way to build a controllable
     * instance at all.
     */
    public ApiRateLimiter(int maxRequests, Duration window, Clock clock) {
        this.limiter = new FixedWindowLimiter(maxRequests, window, clock);
    }

    /**
     * @param key typically {@code "user:" + userId} — see {@code
     *            JwtAuthenticationFilter}
     * @return {@code true} if {@code key} is still within budget for the current window
     */
    public boolean allow(String key) {
        return limiter.allow(key);
    }
}

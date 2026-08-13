package com.pos.util.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

import com.pos.config.AppProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * A per-account lockout guard for {@code POST /api/auth/login} (peer-review Phase 0) —
 * the complement to {@link LoginRateLimiter}. The IP guard bounds how fast one address
 * can guess; this one bounds how many wrong passwords one {@code (tenantCode, username)}
 * pair will tolerate before it stops trying entirely, which is what actually stops a
 * distributed or low-and-slow attacker spreading guesses for one target account across
 * many IPs.
 *
 * <p><b>Keyed the same way regardless of whether the account exists</b> — {@code
 * AuthService} calls {@link #recordFailure} for an unknown username exactly as it does
 * for a wrong password on a real one. A locked-out response therefore never tells a
 * caller whether the account is real, the same enumeration-safety property {@code
 * InvalidCredentialsException}'s uniform 401 already protects elsewhere in that class.
 *
 * <p><b>Known trade-off, accepted rather than engineered around:</b> because the key is
 * whatever {@code (tenantCode, username)} a caller types, an attacker who already knows
 * or guesses a real one can lock out its legitimate owner with as few as {@code
 * pos.login.lockout.maxFailures} (5, in production) deliberately-wrong passwords — a
 * small, time-boxed denial of service against one account, not an account-takeover
 * risk. Accepted because the alternative — no lockout — is the strictly worse
 * "unlimited guesses" status quo this class replaces, and the lock is deliberately
 * short ({@code pos.login.lockout.minutes}, 15 in production) specifically to bound how
 * long that DoS can last. A system with more budget would pair this with step-up
 * friction (CAPTCHA) instead of a hard lock; noted as a future refinement, not built
 * here.
 *
 * <p>In-memory, like {@link RegistrationRateLimiter} — resets on every redeploy.
 * Accepted for the same reason: this is a single-VM deployment where a lockout
 * surviving a restart isn't worth a schema change yet.
 *
 * <p><b>Threshold and lockout duration come from {@link AppProperties}</b>
 * ({@code pos.login.lockout.maxFailures}/{@code .minutes}), not a hardcoded constant —
 * the real, enforced numbers are the defaults in {@code application.properties} (5
 * failures / 15 minutes). {@code application-test.properties} raises the threshold far
 * above that: this class is a Spring-managed singleton shared by every IT class with an
 * identical {@code @ContextConfiguration}, and dozens of them deliberately send a wrong
 * password against the same handful of shared seed accounts (e.g. {@code mg-road:admin})
 * to test the generic-401 path. At the real threshold, that cross-test churn locks those
 * accounts out of every <i>other</i> test's login-as-fixture setup — a collision with no
 * per-call workaround the way {@link LoginRateLimiter}'s IP can be faked, since the
 * account key here has to be the real identity under test. {@code
 * LoginAttemptGuardTest} exercises the real threshold directly, with no Spring context
 * at all, so the test-profile override doesn't weaken what's actually proven.
 */
@Component
public class LoginAttemptGuard {

    private final int maxConsecutiveFailures;
    private final Duration lockout;
    private final Clock clock;
    private final ConcurrentHashMap<String, Attempt> attempts = new ConcurrentHashMap<>();

    @Autowired
    public LoginAttemptGuard(AppProperties props) {
        this(props.getLoginLockoutMaxFailures(),
                Duration.ofMinutes(props.getLoginLockoutMinutes()),
                Clock.systemUTC());
    }

    /** Package-private so {@code LoginAttemptGuardTest} can control time (and the
     *  threshold) without a Spring context — a lockout test that needs one is a test
     *  of Spring, not of this class's logic. */
    LoginAttemptGuard(int maxConsecutiveFailures, Duration lockout, Clock clock) {
        this.maxConsecutiveFailures = maxConsecutiveFailures;
        this.lockout = lockout;
        this.clock = clock;
    }

    /** @return {@code true} if {@code key} is currently locked out. */
    public boolean isLocked(String key) {
        Attempt attempt = attempts.get(key);
        return attempt != null && attempt.lockedUntil != null
                && attempt.lockedUntil.isAfter(clock.instant());
    }

    /**
     * Records one failed attempt against {@code key}. The whole update happens inside
     * one {@link ConcurrentHashMap#compute}, the same device {@link FixedWindowLimiter}
     * uses, so two failures racing the same key can never both under-count.
     *
     * <p>A failure streak older than {@link #lockout} is treated as stale and restarted
     * at one — a cashier who mistypes twice at 9am and once at 5pm shouldn't be one bad
     * password away from locked.
     */
    public void recordFailure(String key) {
        Instant now = clock.instant();
        attempts.compute(key, (k, existing) -> {
            boolean streakAlive = existing != null
                    && existing.lastFailureAt.plus(lockout).isAfter(now);
            int failures = (streakAlive ? existing.failures : 0) + 1;
            Instant lockedUntil = failures >= maxConsecutiveFailures ? now.plus(lockout) : null;
            return new Attempt(failures, now, lockedUntil);
        });
    }

    /** Clears any streak for {@code key} — called the moment a password is proved correct. */
    public void recordSuccess(String key) {
        attempts.remove(key);
    }

    /** One key's current streak: how many failures, when the last one was, and until when (if) it's locked. */
    private record Attempt(int failures, Instant lastFailureAt, Instant lockedUntil) {
    }
}

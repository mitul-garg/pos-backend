package com.pos.util;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

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
 * or guesses a real one can lock out its legitimate owner with {@value
 * #MAX_CONSECUTIVE_FAILURES} deliberately-wrong passwords — a small, time-boxed denial
 * of service against one account, not an account-takeover risk. Accepted because the
 * alternative — no lockout — is the strictly worse "unlimited guesses" status quo this
 * class replaces, and the lock is deliberately short ({@link #LOCKOUT}) specifically to
 * bound how long that DoS can last. A system with more budget would pair this with
 * step-up friction (CAPTCHA) instead of a hard lock; noted as a future refinement, not
 * built here.
 *
 * <p>In-memory, like {@link RegistrationRateLimiter} — resets on every redeploy.
 * Accepted for the same reason: this is a single-VM deployment where a lockout
 * surviving a restart isn't worth a schema change yet.
 */
@Component
public class LoginAttemptGuard {

    /** Consecutive failures before a key locks. */
    static final int MAX_CONSECUTIVE_FAILURES = 5;

    /** How long a lock lasts, and also how long a failure streak survives a gap. */
    static final Duration LOCKOUT = Duration.ofMinutes(15);

    private final Clock clock;
    private final ConcurrentHashMap<String, Attempt> attempts = new ConcurrentHashMap<>();

    public LoginAttemptGuard() {
        this(Clock.systemUTC());
    }

    /** Package-private so {@code LoginAttemptGuardTest} can control time without a real sleep. */
    LoginAttemptGuard(Clock clock) {
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
     * <p>A failure streak more than {@link #LOCKOUT} old is treated as stale and
     * restarted at one — a cashier who mistypes twice at 9am and once at 5pm shouldn't
     * be one bad password away from locked.
     */
    public void recordFailure(String key) {
        Instant now = clock.instant();
        attempts.compute(key, (k, existing) -> {
            boolean streakAlive = existing != null
                    && existing.lastFailureAt.plus(LOCKOUT).isAfter(now);
            int failures = (streakAlive ? existing.failures : 0) + 1;
            Instant lockedUntil = failures >= MAX_CONSECUTIVE_FAILURES ? now.plus(LOCKOUT) : null;
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

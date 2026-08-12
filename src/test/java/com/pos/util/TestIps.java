package com.pos.util;

import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Shared by every IT whose {@code POST /api/auth/login} calls must not share
 * {@link LoginRateLimiter}'s per-IP budget with every other IT in the same test run.
 *
 * <p><b>Why this exists.</b> Spring's test-context caching means any two IT classes
 * with an identical {@code @ContextConfiguration} share one Spring context, and
 * therefore one singleton {@code LoginRateLimiter}. Real MockMvc requests all resolve
 * to the same loopback address unless told otherwise. Most ITs in this suite call
 * login only as test setup — to get a bearer token before testing something
 * unrelated — so without a fresh fake IP per call, one IT's setup logins silently
 * spend another IT's budget, and a full {@code mvn test} run trips 429s that have
 * nothing to do with what any individual test is actually asserting.
 *
 * <p>Extracted once a second IT needed the exact {@code freshIp()}/{@code remoteAddr()}
 * pair {@code AuthControllerIT} and {@code TenantRegistrationIT} had each grown their
 * own copy of (CONVENTIONS.md's "a second copy is the moment to extract").
 */
public final class TestIps {

    private static final AtomicInteger SEQ = new AtomicInteger(0);

    private TestIps() {
    }

    /** A fresh, never-before-used source IP, so this call can never share a rate-limit budget. */
    public static String fresh() {
        return "10.0.0." + (SEQ.incrementAndGet() % 250 + 1);
    }

    /** Wraps {@code ip} (typically {@link #fresh()}) as a MockMvc request post-processor. */
    public static RequestPostProcessor remoteAddr(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }
}

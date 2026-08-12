package com.pos.config;

import com.pos.exception.ForbiddenException;
import com.pos.exception.InvalidCredentialsException;
import com.pos.model.SessionUserData;
import com.pos.pojo.Role;
import com.pos.service.AuthService;
import com.pos.util.TenantContext;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.util.matcher.RequestMatcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code finally}, tested directly — <b>the highest-severity failure available in this
 * design</b> (backend-plan.md section 10, risk 1).
 *
 * <p>A request that ends without clearing {@link TenantContext} leaves its tenant on a
 * pooled Jetty thread, and the next request to land there queries with it. That is a
 * cross-tenant read rather than a stale value, and no ordinary test can see it: with one
 * thread the leaked tenant is always your own. {@code TenantThreadLocalIT} attacks it with
 * real concurrency; this attacks it at the source, by checking every exit path from the
 * filter leaves the thread clean — including the ones that are awkward to provoke through
 * HTTP.
 *
 * <p>No database and no Spring context. The collaborator is a hand-written stub rather
 * than a mocking framework, matching {@code StubServiceConfig}: the point is that
 * {@code AuthService} is not what is under test here.
 */
@DisplayName("JwtAuthenticationFilter — the tenant it sets, and always clears")
class JwtAuthenticationFilterTest {

    private static final Long TENANT_ID = 42L;
    private static final String TOKEN = "Bearer any-non-empty-string";

    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @AfterEach
    void clear() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("puts the token's tenant on the thread for the rest of the request")
    void setsTheTenantForTheChain() throws Exception {
        request.addHeader(HttpHeaders.AUTHORIZATION, TOKEN);
        Long[] seenInsideTheChain = new Long[1];

        filterFor(session(TENANT_ID)).doFilter(request, response,
                (req, res) -> seenInsideTheChain[0] = new TenantContext.Resolver().get());

        // Asserted from INSIDE the chain: by the time doFilter returns the tenant is gone,
        // so checking afterwards would pass whether or not it had ever been set.
        assertEquals(TENANT_ID, seenInsideTheChain[0]);
    }

    @Test
    @DisplayName("clears it once the request completes")
    void clearsAfterTheChain() throws Exception {
        request.addHeader(HttpHeaders.AUTHORIZATION, TOKEN);

        filterFor(session(TENANT_ID)).doFilter(request, response, (req, res) -> { });

        assertFalse(TenantContext.isPresent(), "the thread was handed back still carrying a tenant");
    }

    @Test
    @DisplayName("clears it even when the request blows up")
    void clearsWhenTheChainThrows() {
        request.addHeader(HttpHeaders.AUTHORIZATION, TOKEN);

        // The case the `finally` exists for, and the one a happy-path test never reaches.
        // Any handler that throws -- a 500, a constraint violation, a bug -- would
        // otherwise hand the thread back still carrying this tenant.
        assertThrows(ServletException.class, () ->
                filterFor(session(TENANT_ID)).doFilter(request, response, (req, res) -> {
                    throw new ServletException("handler exploded");
                }));

        assertFalse(TenantContext.isPresent(), "an exception skipped the clear");
    }

    @Test
    @DisplayName("leaves a platform SUPER_ADMIN with no tenant at all")
    void platformUserGetsNoTenant() throws Exception {
        request.addHeader(HttpHeaders.AUTHORIZATION, TOKEN);
        Long[] seenInsideTheChain = new Long[1];

        filterFor(session(null)).doFilter(request, response,
                (req, res) -> seenInsideTheChain[0] = new TenantContext.Resolver().get());

        // Not an oversight -- it is what makes every filtered query a SUPER_ADMIN could
        // reach return nothing. The 403 that makes it legible rather than merely empty is
        // TenantContext.requireTenant(), asserted in TenantIsolationIT.
        assertEquals(TenantContext.NO_TENANT, seenInsideTheChain[0]);
    }

    @Test
    @DisplayName("sets nothing for an unauthenticated request")
    void anonymousGetsNoTenant() throws Exception {
        Long[] seenInsideTheChain = new Long[1];

        // No Authorization header at all. The request continues unauthenticated and the
        // chain's URL rules decide -- so this path must not leave a tenant behind either.
        filterFor(session(TENANT_ID)).doFilter(request, response,
                (req, res) -> seenInsideTheChain[0] = new TenantContext.Resolver().get());

        assertEquals(TenantContext.NO_TENANT, seenInsideTheChain[0]);
        assertFalse(TenantContext.isPresent());
    }

    @Test
    @DisplayName("leaves nothing behind when it rejects the token")
    void clearsOnRejection() throws Exception {
        request.addHeader(HttpHeaders.AUTHORIZATION, TOKEN);
        boolean[] chainRan = { false };

        JwtAuthenticationFilter filter = filterRejectingWith(new InvalidCredentialsException());
        filter.doFilter(request, response, (req, res) -> chainRan[0] = true);

        assertEquals(401, response.getStatus());
        assertFalse(chainRan[0], "a rejected request must not reach the handler");
        assertFalse(TenantContext.isPresent());
    }

    @Test
    @DisplayName("leaves nothing behind when a mid-session 403 stops the request")
    void clearsOnMidSessionForbidden() throws Exception {
        request.addHeader(HttpHeaders.AUTHORIZATION, TOKEN);

        // A tenant suspended, or a user deactivated, after the token was issued.
        JwtAuthenticationFilter filter =
                filterRejectingWith(new ForbiddenException("This store has been suspended."));
        filter.doFilter(request, response, (req, res) -> { });

        assertEquals(403, response.getStatus());
        assertFalse(TenantContext.isPresent());
        assertTrue(response.getContentAsString().contains("suspended"),
                "the specific message is the point of a mid-session 403");
    }

    @Test
    @DisplayName("BUGS.md #15 -- never rejects a public path, even with an unusable token")
    void publicPathIsSkippedRegardlessOfTheToken() throws Exception {
        request.addHeader(HttpHeaders.AUTHORIZATION, TOKEN);
        boolean[] chainRan = { false };

        // A matcher that says yes to everything, standing in for SecurityConfig's real
        // one matching e.g. POST /api/auth/login -- the exact path this bug broke. The
        // stub throws on any resolveSession call, so the ONLY way this test passes is if
        // the filter never asks it to.
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(
                new StubAuthService(null, new InvalidCredentialsException()),
                new ApiErrorResponder(), req -> true);

        filter.doFilter(request, response, (req, res) -> chainRan[0] = true);

        assertTrue(chainRan[0], "a public path must reach the handler regardless of the token");
        assertEquals(200, response.getStatus(), "nothing here should have written a rejection");
        assertFalse(TenantContext.isPresent());
    }

    // --- helpers -----------------------------------------------------------------

    /** Matches nothing, so every test above exercises the ordinary (non-public-path) flow. */
    private static final RequestMatcher NO_PUBLIC_PATHS = req -> false;

    private JwtAuthenticationFilter filterFor(SessionUserData session) {
        return new JwtAuthenticationFilter(new StubAuthService(session, null),
                new ApiErrorResponder(), NO_PUBLIC_PATHS);
    }

    private JwtAuthenticationFilter filterRejectingWith(RuntimeException failure) {
        return new JwtAuthenticationFilter(new StubAuthService(null, failure),
                new ApiErrorResponder(), NO_PUBLIC_PATHS);
    }

    private SessionUserData session(Long tenantId) {
        return new SessionUserData(1L, tenantId, "admin", "Admin", Role.ADMIN, true,
                tenantId == null ? null : "mg-road", tenantId == null ? null : "MG Road Store");
    }

    /**
     * Answers one thing and knows nothing else, so a failure here is unambiguously the
     * filter's. Subclassing rather than mocking keeps the test framework-free, and
     * {@code AuthService}'s constructor tolerates nulls because nothing else on it is
     * reached.
     */
    private static final class StubAuthService extends AuthService {

        private final SessionUserData session;
        private final RuntimeException failure;

        private StubAuthService(SessionUserData session, RuntimeException failure) {
            super(null, null, null, null, null);
            this.session = session;
            this.failure = failure;
        }

        @Override
        public SessionUserData resolveSession(String token) {
            if (failure != null) {
                throw failure;
            }
            return session;
        }
    }
}

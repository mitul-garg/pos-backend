package com.pos.config;

import java.io.IOException;
import java.util.List;

import com.pos.exception.ForbiddenException;
import com.pos.exception.InvalidCredentialsException;
import com.pos.model.SessionUserData;
import com.pos.service.AuthService;
import com.pos.util.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reads the {@code Authorization: Bearer} header and establishes the session (C3).
 *
 * <p>Kept thin on purpose: every decision — is the token valid, is the account usable,
 * is the tenant still active — belongs to {@code AuthService}, so it stays testable
 * without a servlet and reusable off the web. What this class owns is the plumbing
 * around it: where the token comes from, what the {@code SecurityContext} gets, and what
 * a caller sees when it fails.
 *
 * <p><b>A public path is skipped outright, before the token is even read (BUGS.md #15).</b>
 * The original version of this class skipped processing only for a <i>missing</i> header,
 * on the theory that "the request continues unauthenticated and the chain's URL rules
 * decide" — true for {@code authorizeHttpRequests}, but this filter runs
 * <i>in front of</i> that decision (see {@code SecurityConfig#securityFilterChain}'s
 * {@code addFilterBefore}), so a <i>present but unusable</i> token — expired, signed by
 * a rotated key, simply stale in the client — was rejected right here regardless of
 * {@code permitAll()}. In practice: a client that keeps its last token in
 * {@code localStorage} and attaches it to every request, the frontend's own pattern,
 * could never log back in with fresh credentials once that token expired, because the
 * login attempt itself never reached {@code AuthController}. Matching against
 * {@link #publicPathMatcher} up front makes a present-but-bad token on a public path
 * behave exactly like a missing one, rather than only reasoning about the header.
 *
 * <p><b>This is also where {@link TenantContext} is populated (C4)</b> — and, the part
 * that matters, cleared in a {@code finally}. Jetty pools threads, so a context left
 * behind is inherited by whatever request lands on that thread next, which is a
 * cross-tenant read rather than a stale value. The ThreadLocal already in play here,
 * {@code SecurityContextHolder}, is cleared for us by Spring Security's own
 * {@code SecurityContextHolderFilter}; this one is not, so the {@code finally} below is
 * load-bearing rather than tidy.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final AuthService authService;
    private final ApiErrorResponder responder;

    /** {@code SecurityConfig}'s own public-path list, wrapped for a single {@code matches()}. */
    private final RequestMatcher publicPathMatcher;

    public JwtAuthenticationFilter(AuthService authService, ApiErrorResponder responder,
                                   RequestMatcher publicPathMatcher) {
        this.authService = authService;
        this.responder = responder;
        this.publicPathMatcher = publicPathMatcher;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (publicPathMatcher.matches(request)) {
            // BUGS.md #15. A public path needs no session at all, so nothing here should
            // depend on whatever token the client happens to be carrying -- not even to
            // reject it. TenantContext is never touched on this path either, matching
            // the missing-header case just below.
            chain.doFilter(request, response);
            return;
        }

        String token = bearerToken(request);
        if (token == null) {
            chain.doFilter(request, response);
            return;
        }

        try {
            SessionUserData session = authService.resolveSession(token);
            SecurityContextHolder.getContext().setAuthentication(authenticationFor(session));
            // Null for a platform SUPER_ADMIN, which is correct: it has no tenant
            // context, so every filtered query it could reach resolves to NO_TENANT and
            // returns nothing. The 403 that makes that legible rather than merely empty
            // is TenantContext.requireTenant(), in the service layer.
            TenantContext.set(session.getTenantId());
        } catch (InvalidCredentialsException ex) {
            reject(response, HttpStatus.UNAUTHORIZED, InvalidCredentialsException.MESSAGE);
            return;
        } catch (ForbiddenException ex) {
            // A deactivated user or a suspended tenant, detected mid-session rather than
            // at login. Specific for the same reason it is specific at login: reaching
            // here required a signed token, so the password was already proved.
            reject(response, HttpStatus.FORBIDDEN, ex.getMessage());
            return;
        }

        try {
            chain.doFilter(request, response);
        } finally {
            // The single most consequential line in C4. Jetty hands this thread to the
            // next request; anything left here becomes that request's tenant. In a
            // finally rather than after the call because a handler that throws must not
            // be able to skip it.
            TenantContext.clear();
        }
    }

    /**
     * The two statuses this filter can produce, and the only place outside
     * {@code ApiExceptionHandler} that picks one. Two cases rather than the full matrix,
     * because a filter can only fail at authentication — everything else is raised by a
     * controller or a service, where the advice does see it.
     */
    private void reject(HttpServletResponse response, HttpStatus status, String message)
            throws IOException {
        SecurityContextHolder.clearContext();
        // Unreachable in practice -- the tenant is set on the last line of the try block,
        // so nothing that lands here has set one. Cleared anyway, because the cost is a
        // ThreadLocal removal and the alternative failure is a cross-tenant read.
        TenantContext.clear();
        responder.write(response, status, message);
    }

    private Authentication authenticationFor(SessionUserData session) {
        // Spring Security's hasRole("ADMIN") looks for the authority "ROLE_ADMIN"; the
        // prefix is convention, not decoration. C8's URL rules depend on it.
        List<GrantedAuthority> authorities =
                List.of(new SimpleGrantedAuthority("ROLE_" + session.getRole().name()));
        // Credentials are null because they were verified at login and this request has
        // none -- keeping a password anywhere near the SecurityContext is how it ends up
        // in a heap dump or a log line.
        return UsernamePasswordAuthenticationToken.authenticated(session, null, authorities);
    }

    private String bearerToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            return null;
        }
        String token = header.substring(BEARER.length()).trim();
        return token.isEmpty() ? null : token;
    }
}

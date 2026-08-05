package com.pos.config;

import java.io.IOException;
import java.util.List;

import com.pos.exception.ForbiddenException;
import com.pos.exception.InvalidCredentialsException;
import com.pos.model.SessionUserData;
import com.pos.service.AuthService;
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
 * <p><b>A missing header is not a rejection.</b> The request continues unauthenticated
 * and the chain's URL rules decide — that is what lets {@code /api/health} and
 * {@code POST /api/auth/login} stay open while everything else 401s. Rejecting here
 * would make every public endpoint need an exception.
 *
 * <p>From C4 this filter is also where {@code TenantContext} is populated and — the part
 * that matters — cleared in a {@code finally}. Jetty pools threads, so a context left
 * behind is inherited by whatever request lands on that thread next, which is a
 * cross-tenant read rather than a stale value. The ThreadLocal already in play here,
 * {@code SecurityContextHolder}, is cleared for us by Spring Security's own
 * {@code SecurityContextHolderFilter}; C4's will not be.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final AuthService authService;
    private final ApiErrorResponder responder;

    public JwtAuthenticationFilter(AuthService authService, ApiErrorResponder responder) {
        this.authService = authService;
        this.responder = responder;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String token = bearerToken(request);
        if (token == null) {
            chain.doFilter(request, response);
            return;
        }

        try {
            SessionUserData session = authService.resolveSession(token);
            SecurityContextHolder.getContext().setAuthentication(authenticationFor(session));
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

        chain.doFilter(request, response);
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

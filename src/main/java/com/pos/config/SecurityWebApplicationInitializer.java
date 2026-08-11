package com.pos.config;

import jakarta.servlet.ServletContext;
import org.springframework.security.web.context.AbstractSecurityWebApplicationInitializer;
import org.springframework.web.filter.ForwardedHeaderFilter;

/**
 * Registers Spring Security's filter chain with the servlet container — the
 * {@code <filter>} element a {@code web.xml} would have held.
 *
 * <p>Two filters, not an empty class that does real work in just one of them. The
 * superclass adds a {@code DelegatingFilterProxy} named {@code springSecurityFilterChain},
 * mapped to {@code /*}, which resolves the bean {@code @EnableWebSecurity} built in the
 * <b>root</b> context and delegates to it. Without this the chain exists as a bean and is
 * never invoked, so every endpoint stays open while the startup log looks entirely healthy.
 *
 * <p>Separate from {@link WebAppInitializer} because the container discovers both
 * independently through {@code SpringServletContainerInitializer}, and the superclasses
 * are unrelated. Ordering is not left to chance:
 * {@code AbstractSecurityWebApplicationInitializer} declares order 100 while the
 * dispatcher initializer declares none, so Spring's comparator runs security first —
 * which is what puts the filter in front of the {@code DispatcherServlet}.
 */
public class SecurityWebApplicationInitializer extends AbstractSecurityWebApplicationInitializer {

    /**
     * Deployed (iac/05-https.md), Nginx terminates TLS and reverse-proxies to Jetty over
     * plain HTTP, already setting {@code X-Forwarded-Proto: https} — but nothing on this
     * side reads it, so {@code request.getScheme()} still reports {@code http}. That broke
     * {@link SecurityConfig#corsConfigurationSource()}'s whole premise: Spring only applies
     * its allowed-origins check when the {@code Origin} header's scheme/host/port
     * <i>disagrees</i> with what the server thinks the request's own origin is
     * ({@code CorsUtils.isCorsRequest}). Same-origin proxying means that should never
     * happen for a request from the deployed frontend — until the scheme mismatch (browser
     * says {@code https}, Jetty says {@code http}) made every one of them look
     * cross-origin, and the allowlist (only the Vite dev origin) rejected them with
     * {@code 403 Invalid CORS request} — including {@code /api/auth/login}.
     *
     * <p>{@link ForwardedHeaderFilter} is Spring's own fix for exactly this: it rewrites
     * {@code getScheme()}/{@code getServerName()}/{@code isSecure()} (and the like) from
     * {@code X-Forwarded-*} for the rest of the chain. It has to run <b>before</b>
     * {@code springSecurityFilterChain} — CORS is decided inside that chain — which is
     * what {@code beforeSpringSecurityFilterChain} + {@code insertFilters} is for; appending
     * it instead would run it too late to matter.
     */
    @Override
    protected void beforeSpringSecurityFilterChain(ServletContext servletContext) {
        insertFilters(servletContext, new ForwardedHeaderFilter());
    }
}

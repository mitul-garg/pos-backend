package com.pos.config;

import org.springframework.security.web.context.AbstractSecurityWebApplicationInitializer;

/**
 * Registers Spring Security's filter chain with the servlet container — the
 * {@code <filter>} element a {@code web.xml} would have held.
 *
 * <p>An empty class that does real work. The superclass adds a
 * {@code DelegatingFilterProxy} named {@code springSecurityFilterChain}, mapped to
 * {@code /*}, which resolves the bean {@code @EnableWebSecurity} built in the <b>root</b>
 * context and delegates to it. Without this the chain exists as a bean and is never
 * invoked, so every endpoint stays open while the startup log looks entirely healthy.
 *
 * <p>Separate from {@link WebAppInitializer} because the container discovers both
 * independently through {@code SpringServletContainerInitializer}, and the superclasses
 * are unrelated. Ordering is not left to chance:
 * {@code AbstractSecurityWebApplicationInitializer} declares order 100 while the
 * dispatcher initializer declares none, so Spring's comparator runs security first —
 * which is what puts the filter in front of the {@code DispatcherServlet}.
 */
public class SecurityWebApplicationInitializer extends AbstractSecurityWebApplicationInitializer {
}

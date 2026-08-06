package com.pos.config;

import java.util.Arrays;
import java.util.List;

import com.pos.exception.InvalidCredentialsException;
import com.pos.pojo.Role;
import com.pos.service.AuthService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * The security chain (C3) — everything Spring Boot's security auto-configuration would
 * assemble, written out.
 *
 * <p><b>A root-context config, and that is structural rather than stylistic.</b> Spring
 * Security is a servlet <i>filter</i>, and filters are built from the root context — they
 * cannot see the servlet context (see {@link WebAppInitializer}). Declared alongside
 * {@link WebConfig} this would produce a chain that is constructed, logged at startup,
 * and never invoked, which looks exactly like security silently not working.
 *
 * <p>{@code @EnableWebSecurity} only builds the {@code springSecurityFilterChain} bean;
 * {@link SecurityWebApplicationInitializer} is what registers it with the container.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** The Vite dev server — see {@link #corsConfigurationSource()}. */
    private static final String FRONTEND_DEV_ORIGIN = "http://localhost:5173";

    /**
     * Reachable with no token at all. Everything absent from this list requires one.
     *
     * <p>{@code /api/auth/logout} is deliberately <b>not</b> here. A stateless server has
     * nothing to revoke, so an open logout would work — but it would bake in "logout has
     * no actor", and the day it gains a token denylist or an audit entry
     * (backend-plan.md section 11) it has to know who is calling. The cost of requiring a
     * token is nil: the frontend clears its own state and redirects to login on any 401,
     * which is the end state a logout wants anyway.
     */
    private static final String[] PUBLIC_PATHS = {
            "/api/health",
            "/api/auth/login",
            "/api/openapi.json",
            "/swagger-ui",
            "/swagger-ui/**",
    };

    /**
     * BCrypt at its default strength (10).
     *
     * <p>The cost is the feature. Roughly 100ms per verification is what makes an offline
     * attack on a leaked {@code password_hash} column expensive — and it is also why
     * {@code AuthService} spends a verification on an unknown username too, so the
     * uniform 401 does not leak through response time what it refuses to say in its
     * message.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public ApiErrorResponder apiErrorResponder() {
        return new ApiErrorResponder();
    }

    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter(AuthService authService,
                                                           ApiErrorResponder responder) {
        return new JwtAuthenticationFilter(authService, responder, publicPathMatcher());
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthenticationFilter jwtFilter,
                                                   ApiErrorResponder responder) throws Exception {
        return http
                // Disabled honestly, not for convenience. CSRF exploits the browser
                // attaching a credential by itself; this API's credential is a bearer
                // token the frontend reads from localStorage and sets as a header, which
                // a forged cross-site request cannot cause to happen. The day a session
                // cookie appears, this line becomes wrong.
                .csrf(AbstractHttpConfigurer::disable)

                // CORS lives here now, not in WebConfig. MVC's CORS support runs inside
                // the DispatcherServlet and this chain is in front of it -- a preflight
                // OPTIONS carries no Authorization header, so it would be answered 401
                // before MVC ever saw it and every cross-origin call from the dev server
                // would fail. WebConfig#addCorsMappings was removed in the same change.
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))

                // No HttpSession, ever: the token is the whole session. Without this,
                // Spring Security still creates one to hold the SecurityContext, quietly
                // reintroducing the server-side state that makes cold starts and multiple
                // instances a problem (backend-plan.md section 3).
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // All three would otherwise answer an unauthenticated request with a
                // redirect or a browser credential prompt. This API only speaks JSON.
                // logout() in particular would map its own POST /logout, which is not
                // our endpoint and would not be stateless.
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)

                .authorizeHttpRequests(auth -> auth
                        // Preflight requests are unauthenticated by definition; the CORS
                        // filter configured above is what answers them.
                        .requestMatchers(AntPathRequestMatcher.antMatcher(HttpMethod.OPTIONS, "/**")).permitAll()
                        .requestMatchers(publicMatchers()).permitAll()
                        // Catalogue management is an ADMIN's (requirements.md 13.2); a
                        // CASHIER reads the same catalogue and writes none of it. Note
                        // these are METHOD-scoped: GET /api/products stays open to both,
                        // so the rules cannot be collapsed into one path pattern.
                        .requestMatchers(adminMatchers()).hasRole(Role.ADMIN.name())
                        // The platform surface (C8) -- every /api/tenants/** verb, and
                        // only a SUPER_ADMIN reaches it. Unlike adminMatchers() this is
                        // NOT method-scoped: requirements.md section 5.10 gates the
                        // whole screen, so even GET /api/tenants requires the role.
                        .requestMatchers(platformMatchers()).hasRole(Role.SUPER_ADMIN.name())
                        // Default-deny. A new endpoint is protected the moment it exists
                        // rather than when someone remembers to list it -- the inverse
                        // rule is how an endpoint ships unguarded.
                        .anyRequest().authenticated())

                .exceptionHandling(handling -> handling
                        // No token at all, or one this chain rejected before the filter
                        // ran. Same {message} envelope as every other error, because a
                        // @ControllerAdvice cannot see a failure that happens in a filter.
                        .authenticationEntryPoint((request, response, ex) ->
                                responder.write(response, HttpStatus.UNAUTHORIZED,
                                        InvalidCredentialsException.MESSAGE))
                        // Authenticated, but the role is wrong. Unreachable until C5,
                        // which added the first rule that discriminates by role: a
                        // CASHIER writing to the catalogue lands here.
                        .accessDeniedHandler((request, response, ex) ->
                                responder.write(response, HttpStatus.FORBIDDEN,
                                        "You do not have permission to perform this action.")))

                // Where Spring Security expects an authentication mechanism to sit.
                // Nothing authenticates downstream of it here, but the position is what
                // the rest of the chain is built around.
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    /**
     * {@link #PUBLIC_PATHS} as explicit Ant matchers.
     *
     * <p><b>Why not just {@code requestMatchers("/api/health", ...)}.</b> That overload
     * builds an {@code MvcRequestMatcher} whenever Spring MVC is on the classpath, which
     * needs the {@code mvcHandlerMappingIntrospector} bean — and that bean is created by
     * {@code @EnableWebMvc}, so it lives in the <b>servlet</b> context, which this
     * root-context config cannot see. The application then fails to start with
     * "Please ensure Spring Security &amp; Spring MVC are configured in a shared
     * ApplicationContext", which they deliberately are not.
     *
     * <p>Ant matching is not a downgrade here: {@code MvcRequestMatcher} exists to account
     * for a servlet path prefix, and the {@code DispatcherServlet} is mapped at
     * {@code "/"}, so the two agree on every URL this application has.
     *
     * <p>This is the one C3 failure that no test caught before {@code mvn jetty:run} did —
     * {@code AuthControllerIT} flattens both contexts into one, where the introspector is
     * present. {@code SecurityConfigIT} now boots the root context alone, as the container
     * does, so it cannot happen again.
     */
    private RequestMatcher[] publicMatchers() {
        return Arrays.stream(PUBLIC_PATHS)
                .map(AntPathRequestMatcher::antMatcher)
                .toArray(RequestMatcher[]::new);
    }

    /**
     * {@link #publicMatchers()}, combined into the single matcher
     * {@code JwtAuthenticationFilter} needs (BUGS.md #15) — that filter runs in front of
     * {@code authorizeHttpRequests} entirely, so it cannot ask "is this request already
     * permitted" the way the DSL above does; it can only ask "does this request's path
     * match the same list". {@code OrRequestMatcher} is that one-matcher form of an array
     * of alternatives, built from the identical {@link #PUBLIC_PATHS} so the two can never
     * name different paths.
     */
    private RequestMatcher publicPathMatcher() {
        return new OrRequestMatcher(publicMatchers());
    }

    /**
     * The write half of the catalogue (C5). <b>The complete list of role-restricted URLs
     * in the application</b> — keeping it here rather than as {@code @PreAuthorize} on
     * scattered handlers is what makes "which endpoints does a CASHIER not reach?" a
     * question one file answers.
     *
     * <p>A {@code CASHIER} reaching one of these gets the chain's 403, in the same
     * {@code ApiError} envelope as everything else, from the access-denied handler
     * configured above. A {@code SUPER_ADMIN} gets the same 403 — it is not an
     * {@code ADMIN} and has no tenant to write into either, so both guards agree.
     *
     * <p>Ant matchers rather than the {@code String} overload, for the reason on
     * {@link #publicMatchers()}: this is a root-context config and the MVC introspector
     * lives in the servlet one.
     */
    private RequestMatcher[] adminMatchers() {
        return new RequestMatcher[] {
                AntPathRequestMatcher.antMatcher(HttpMethod.POST, "/api/products"),
                AntPathRequestMatcher.antMatcher(HttpMethod.PUT, "/api/products/*"),
                AntPathRequestMatcher.antMatcher(HttpMethod.DELETE, "/api/products/*"),
                // Variants. The single-star pattern matters: "/api/products/*/variants"
                // matches the sub-resource and NOT "/api/products/{id}", which the PUT
                // rule above already covers with its own verb.
                AntPathRequestMatcher.antMatcher(HttpMethod.POST, "/api/products/*/variants"),
                AntPathRequestMatcher.antMatcher(HttpMethod.PUT, "/api/variants/*"),
                AntPathRequestMatcher.antMatcher(HttpMethod.DELETE, "/api/variants/*"),
                AntPathRequestMatcher.antMatcher(HttpMethod.POST, "/api/variants/*/qr-code"),
                // User management (C8) -- requirements.md section 5.7 gates the whole
                // screen, not just its writes, so GET /api/users joins the other three
                // verbs here rather than staying open the way GET /api/products is.
                AntPathRequestMatcher.antMatcher(HttpMethod.GET, "/api/users"),
                AntPathRequestMatcher.antMatcher(HttpMethod.POST, "/api/users"),
                AntPathRequestMatcher.antMatcher(HttpMethod.PUT, "/api/users/*"),
                AntPathRequestMatcher.antMatcher(HttpMethod.DELETE, "/api/users/*"),
        };
    }

    /**
     * The platform surface (C8) — every verb {@code TenantController} exposes.
     * {@code SUPER_ADMIN}-gated in the chain above rather than by a check inside
     * {@code TenantService}, the same division of labour {@link #adminMatchers()}
     * already establishes for {@code ADMIN}. Kept as its own array rather than folded
     * into {@code adminMatchers()}: the two lists are gated on different roles, and
     * merging them would mean this method's name stops describing what it returns.
     */
    private RequestMatcher[] platformMatchers() {
        return new RequestMatcher[] {
                AntPathRequestMatcher.antMatcher(HttpMethod.GET, "/api/tenants"),
                AntPathRequestMatcher.antMatcher(HttpMethod.GET, "/api/tenants/*"),
                AntPathRequestMatcher.antMatcher(HttpMethod.POST, "/api/tenants"),
                AntPathRequestMatcher.antMatcher(HttpMethod.PATCH, "/api/tenants/*"),
        };
    }

    /**
     * The single CORS definition, moved out of {@link WebConfig} in C3.
     *
     * <p>The origin stays a literal rather than a wildcard: the CORS specification
     * forbids pairing {@code allowCredentials} with {@code *}, and a browser handles the
     * combination by silently dropping the response rather than reporting a
     * misconfiguration. {@code allowCredentials} itself is what permits the
     * {@code Authorization} header.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(FRONTEND_DEV_ORIGIN));
        configuration.setAllowedMethods(
                List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }
}

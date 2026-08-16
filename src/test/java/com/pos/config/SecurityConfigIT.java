package com.pos.config;

import jakarta.servlet.Filter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boots the <b>root context on its own</b>, exactly as the servlet container does — no
 * {@code WebConfig}, no {@code @EnableWebMvc}, no handler mappings.
 *
 * <p><b>This suite exists because of a bug that reached {@code mvn jetty:run}.</b>
 * {@code AuthControllerIT} flattens the root and servlet contexts into one, which is
 * convenient and, for one class of failure, actively misleading: anything
 * {@code SecurityConfig} accidentally takes from the servlet context resolves fine there
 * and is missing in production. The first version of the chain used
 * {@code requestMatchers(String...)}, which builds an {@code MvcRequestMatcher} and needs
 * {@code @EnableWebMvc}'s {@code mvcHandlerMappingIntrospector} — so every test passed
 * and the application would not start.
 *
 * <p>So the assertions here are deliberately shallow. <b>What is under test is that the
 * context refreshes at all</b>, with the same visibility the container gives it. The
 * chain's behaviour belongs to {@code AuthControllerIT}; its constructibility belongs
 * here, and nothing else can check it.
 *
 * <p>Keep it this way. Adding {@code WebConfig} to make some assertion easier would
 * silently delete the only thing this file is for.
 */
@ExtendWith(SpringExtension.class)
// A web context, but only the root half. Both halves of that matter: the fallback to Ant
// matchers only misbehaves in a WebApplicationContext, so a plain context would go green
// on the very bug this catches.
@WebAppConfiguration
@ContextConfiguration(classes = {
        RootConfig.class, PersistenceConfig.class, SecurityConfig.class, MailConfig.class,
        RecaptchaConfig.class, ImagesConfig.class })
@TestPropertySource("classpath:application-test.properties")
@DisplayName("SecurityConfig in the root context alone, as the container builds it")
class SecurityConfigIT {

    @Autowired
    private SecurityFilterChain securityFilterChain;

    /**
     * The bean {@code SecurityWebApplicationInitializer}'s {@code DelegatingFilterProxy}
     * looks up by name. If it is absent or renamed, the container registers a proxy that
     * resolves to nothing and every endpoint is silently open.
     */
    @Autowired
    @Qualifier("springSecurityFilterChain")
    private Filter springSecurityFilterChain;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("builds the chain without reaching into the servlet context")
    void buildsTheChain() {
        assertNotNull(securityFilterChain);
        assertNotNull(springSecurityFilterChain);
        assertTrue(securityFilterChain.getFilters().stream()
                        .anyMatch(JwtAuthenticationFilter.class::isInstance),
                () -> "the JWT filter is not in the chain, so no request can authenticate. "
                        + "Filters: " + securityFilterChain.getFilters());
    }

    @Test
    @DisplayName("encodes passwords with BCrypt, and never to the same hash twice")
    void encodesWithBcrypt() {
        String hash = passwordEncoder.encode("admin123");

        assertTrue(hash.startsWith("$2"), () -> "not a BCrypt hash: " + hash);
        assertTrue(passwordEncoder.matches("admin123", hash));
        // A per-hash salt is what stops one leaked table from revealing that two users
        // share a password -- and the seed data deliberately gives four users the same
        // two passwords.
        assertTrue(!hash.equals(passwordEncoder.encode("admin123")),
                "the same password hashed twice produced the same output, so there is no salt");
    }
}

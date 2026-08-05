package com.pos.config;

import com.pos.service.AuthService;
import com.pos.service.ProductService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Lets a test boot the <b>servlet context alone</b> — no root context, no database.
 *
 * <p>{@link WebConfig} component-scans {@code com.pos.controller}, so every controller
 * in the application gets built whether or not a given suite is about it. Until C3 that
 * cost nothing, because {@code HealthController} and {@code OpenApiController} have no
 * collaborators. {@code AuthController} has one, and every controller from C5 onwards
 * will too — so a suite that only wants to assert JSON wiring or the generated OpenAPI
 * document would otherwise have to stand up persistence and security to get there,
 * against CONVENTIONS.md's "not every test needs a database".
 *
 * <p><b>The stubs are inert on purpose.</b> Their collaborators are null, so calling one
 * fails immediately and loudly rather than returning a plausible answer. That is the
 * boundary being drawn: these suites assert that the wiring exists and that the
 * serialization is right, never that authentication is correct. Authentication behaviour
 * belongs to {@code AuthControllerIT}, which boots the real thing against real MySQL.
 *
 * <p><b>Extending this</b> is one {@code @Bean} per new controller dependency, in the
 * same change that adds the controller. If a stub ever needs real behaviour, that is the
 * signal the suite wanting it should have been an {@code IT} all along.
 */
@Configuration
public class StubServiceConfig {

    @Bean
    public AuthService authService() {
        return new AuthService(null, null, null, null);
    }

    @Bean
    public ProductService productService() {
        return new ProductService(null);
    }
}

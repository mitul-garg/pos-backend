package com.pos.config;

import com.pos.util.OpenApiGenerator;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Wires the API documentation: the generated spec at {@code /api/openapi.json} and
 * Swagger UI at {@code /swagger-ui/}.
 *
 * <p>Lives in the <b>servlet</b> context, not the root one, because
 * {@link RequestMappingHandlerMapping} is created by {@code @EnableWebMvc} and is only
 * visible there.
 */
@Configuration
public class OpenApiConfig implements WebMvcConfigurer {

    /**
     * Must match {@code <swagger.ui.version>} in {@code pom.xml} — the webjar puts its
     * assets under a version-numbered path. {@code SwaggerUiResourcesTest} fails the
     * build if a version bump forgets this line, which is what makes the constant safe.
     */
    static final String SWAGGER_UI_VERSION = "5.20.1";

    private static final String WEBJAR_LOCATION =
            "classpath:/META-INF/resources/webjars/swagger-ui/" + SWAGGER_UI_VERSION + "/";

    @Bean
    public OpenApiGenerator openApiGenerator(RequestMappingHandlerMapping handlerMapping) {
        Info info = new Info()
                .title("PoS API")
                .version("1.0.0")
                .description("Point-of-sale backend. Multi-tenant: every request is scoped "
                        + "to the tenant in the caller's token, never to a request parameter.");
        return new OpenApiGenerator(handlerMapping, info);
    }

    /**
     * Two locations under one handler, tried in order: our own {@code index.html} wins,
     * and every other asset falls through to the webjar. That keeps the URL clean
     * ({@code /swagger-ui/}) without unpacking or re-hosting the distribution.
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/swagger-ui/**")
                .addResourceLocations("classpath:/swagger-ui/", WEBJAR_LOCATION);
    }

    /**
     * Serving a classpath directory does not imply an index page the way a web server
     * does, so {@code /swagger-ui/} would otherwise 404 while
     * {@code /swagger-ui/index.html} worked — which is exactly the URL a person types.
     */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/swagger-ui", "/swagger-ui/index.html");
        registry.addRedirectViewController("/swagger-ui/", "/swagger-ui/index.html");
    }
}

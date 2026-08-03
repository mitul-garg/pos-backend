package com.pos.config;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link OpenApiConfig#SWAGGER_UI_VERSION} has to repeat the webjar version, because
 * the jar lays its assets out under a version-numbered path and a resource location
 * cannot wildcard. This test is what stops that constant from silently going stale:
 * bump {@code <swagger.ui.version>} in the POM without updating the constant and the
 * build fails here, rather than the docs page 404ing in a browser later.
 */
class SwaggerUiResourcesTest {

    private static final String WEBJAR_ROOT =
            "/META-INF/resources/webjars/swagger-ui/" + OpenApiConfig.SWAGGER_UI_VERSION + "/";

    @ParameterizedTest
    @ValueSource(strings = { "swagger-ui.css", "swagger-ui-bundle.js", "swagger-ui-standalone-preset.js" })
    void webjarAssetsResolveAtTheConfiguredVersion(String asset) {
        assertNotNull(getClass().getResource(WEBJAR_ROOT + asset),
                "missing " + WEBJAR_ROOT + asset
                        + " -- OpenApiConfig.SWAGGER_UI_VERSION is out of step with the POM");
    }

    /** Our own page shadows the webjar's index and points it at the generated spec. */
    @Test
    void ourIndexPageIsOnTheClasspath() {
        assertNotNull(getClass().getResource("/swagger-ui/index.html"));
    }
}

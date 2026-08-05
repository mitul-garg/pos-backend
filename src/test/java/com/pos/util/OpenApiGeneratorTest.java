package com.pos.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.pos.config.OpenApiConfig;
import com.pos.config.StubServiceConfig;
import com.pos.config.WebConfig;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;

/**
 * The generator replaces springdoc, so it needs the tests springdoc's maintainers
 * would otherwise have written for us. Runs against the real handler mappings, which
 * means it also fails if a controller stops being discoverable.
 */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = { WebConfig.class, OpenApiConfig.class, StubServiceConfig.class })
class OpenApiGeneratorTest {

    @Autowired
    private OpenApiGenerator generator;

    private OpenAPI api;

    @BeforeEach
    void setUp() {
        api = generator.generate();
    }

    @Test
    void describesTheApi() {
        assertNotNull(api.getInfo());
        assertEquals("PoS API", api.getInfo().getTitle());
    }

    @Test
    void findsHandlerMappedPaths() {
        assertTrue(api.getPaths().containsKey("/api/health"),
                "expected /api/health in " + api.getPaths().keySet());
    }

    @Test
    void mapsTheHttpMethod() {
        PathItem health = api.getPaths().get("/api/health");
        assertNotNull(health.getGet(), "GET /api/health should be documented");
        assertNull(health.getPost(), "no POST handler is mapped on /api/health");
    }

    /** Prose from {@code @Operation} on the handler carries into the document. */
    @Test
    void readsOperationAnnotations() {
        Operation get = api.getPaths().get("/api/health").getGet();
        assertEquals("Liveness check", get.getSummary());
        assertEquals("health", get.getOperationId());
    }

    /** Controllers become tags, so the UI groups endpoints by resource. */
    @Test
    void tagsByController() {
        Operation get = api.getPaths().get("/api/health").getGet();
        assertEquals(List.of("Health"), get.getTags());
    }

    /**
     * The part that actually needs swagger-core: the handler returns {@code HealthData}
     * and the document must carry its schema, not just the path.
     */
    @Test
    void resolvesResponseSchemaFromTheReturnType() {
        Operation get = api.getPaths().get("/api/health").getGet();
        var json = get.getResponses().get("200").getContent().get("application/json");
        assertNotNull(json, "200 response should have a JSON body");
        assertEquals("#/components/schemas/HealthData", json.getSchema().get$ref());

        var healthData = api.getComponents().getSchemas().get("HealthData");
        assertNotNull(healthData, "HealthData schema should be registered in components");
        assertTrue(healthData.getProperties().containsKey("status"));
        assertTrue(healthData.getProperties().containsKey("application"));
        assertTrue(healthData.getProperties().containsKey("time"));
    }

    /**
     * A handler that answers something other than 200 must be documented as answering
     * it. Both directions are asserted, because the failure this guards against is a
     * document that promises 200 everywhere — which a generated client would read as
     * "201 is an error".
     */
    @Test
    void readsTheSuccessStatusFromResponseStatus() {
        Operation create = api.getPaths().get("/api/products").getPost();
        assertNotNull(create.getResponses().get("201"), "POST /api/products answers 201");
        assertNull(create.getResponses().get("200"), "and only 201");

        Operation logout = api.getPaths().get("/api/auth/logout").getPost();
        assertNotNull(logout.getResponses().get("204"), "POST /api/auth/logout answers 204");

        // The default is unchanged for a handler that says nothing.
        assertNotNull(api.getPaths().get("/api/health").getGet().getResponses().get("200"));
    }

    /** Cached: the handler map is fixed after startup, so rebuilding it is waste. */
    @Test
    void reusesTheGeneratedDocument() {
        assertSame(api, generator.generate(), "generate() should return the cached document");
    }
}

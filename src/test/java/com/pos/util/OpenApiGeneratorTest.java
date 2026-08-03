package com.pos.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.pos.config.OpenApiConfig;
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
@ContextConfiguration(classes = { WebConfig.class, OpenApiConfig.class })
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

    /** Cached: the handler map is fixed after startup, so rebuilding it is waste. */
    @Test
    void reusesTheGeneratedDocument() {
        assertSame(api, generator.generate(), "generate() should return the cached document");
    }
}

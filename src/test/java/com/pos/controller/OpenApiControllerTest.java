package com.pos.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pos.config.OpenApiConfig;
import com.pos.config.StubServiceConfig;
import com.pos.config.WebConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The docs are served over HTTP, so they need an HTTP-level test — the generator unit
 * test can pass while the spec is unreachable or the UI is wired to the wrong URL.
 */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = { WebConfig.class, OpenApiConfig.class, StubServiceConfig.class })
class OpenApiControllerTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void servesTheSpecAsJson() throws Exception {
        mvc.perform(get("/api/openapi.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").exists())
                .andExpect(jsonPath("$.info.title").value("PoS API"))
                .andExpect(jsonPath("$.paths['/api/health'].get.operationId").value("health"));
    }

    /**
     * Serialized by swagger-core's mapper, not the app's. If it were the app's, {@code $ref}
     * would come out as a plain {@code ref} property and the document would not validate.
     */
    @Test
    void usesSwaggersOwnSerializer() throws Exception {
        mvc.perform(get("/api/openapi.json"))
                .andExpect(jsonPath("$.paths['/api/health'].get.responses.200.content"
                        + "['application/json'].schema.$ref")
                        .value("#/components/schemas/HealthData"));
    }

    /**
     * A classpath resource directory has no implicit index page, so this is the URL a
     * person types and the one most likely to break.
     */
    @ParameterizedTest
    @ValueSource(strings = { "/swagger-ui", "/swagger-ui/" })
    void redirectsBareDocsUrlToTheIndexPage(String path) throws Exception {
        mvc.perform(get(path))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/swagger-ui/index.html"));
    }
}

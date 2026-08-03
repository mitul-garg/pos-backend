package com.pos.controller;

import com.pos.util.OpenApiGenerator;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the generated OpenAPI document that Swagger UI reads.
 */
@RestController
public class OpenApiController {

    private final OpenApiGenerator generator;

    @Autowired
    public OpenApiController(OpenApiGenerator generator) {
        this.generator = generator;
    }

    /**
     * Serialized with swagger-core's own {@link Json} mapper rather than the
     * application's {@code ObjectMapper}. The OpenAPI model classes carry custom
     * serializers — {@code $ref} handling and extension fields in particular — and a
     * plain mapper produces a document that looks right but does not validate.
     */
    @Operation(summary = "This API's OpenAPI 3 document",
               description = "Generated at runtime from Spring's handler mappings, so it "
                       + "cannot drift from the controllers.")
    @GetMapping(value = "/api/openapi.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public String spec() {
        return Json.pretty(generator.generate());
    }
}

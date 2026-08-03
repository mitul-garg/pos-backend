package com.pos.controller;

import java.time.Instant;

import com.pos.model.HealthData;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liveness check. Unauthenticated by design — C3 leaves this outside the security
 * chain, because a probe that needs a token cannot tell "the app is down" apart from
 * "the token expired".
 *
 * <p>C2 will not extend this into a database check: a health endpoint that queries
 * MySQL turns a slow database into an apparently dead app, and on the free tier it
 * would also burn a connection from a pool capped around five.
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    @Operation(summary = "Liveness check",
               description = "Returns UP whenever the application is serving requests.")
    @GetMapping("/health")
    public HealthData health() {
        return new HealthData("UP", "pos-backend", Instant.now());
    }
}

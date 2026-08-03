package com.pos.model;

import java.time.Instant;

/**
 * Output DTO for {@code GET /api/health}.
 *
 * <p>The {@code Data} suffix marks it as a response shape; inbound DTOs get {@code Form}.
 * The split makes an entity accidentally appearing in a controller signature obvious.
 */
public class HealthData {

    private final String status;
    private final String application;
    private final Instant time;

    public HealthData(String status, String application, Instant time) {
        this.status = status;
        this.application = application;
        this.time = time;
    }

    public String getStatus() {
        return status;
    }

    public String getApplication() {
        return application;
    }

    public Instant getTime() {
        return time;
    }
}

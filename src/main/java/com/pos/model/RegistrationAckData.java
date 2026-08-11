package com.pos.model;

/**
 * Output DTO for {@code POST /api/tenants/resend-verification} (C9) — a single fixed
 * {@code message}, identical whether or not {@code { tenantCode, adminEmail }} matched
 * a real pending tenant. No enumeration oracle, matching every other auth-adjacent
 * endpoint in this codebase (login's uniform 401).
 */
public class RegistrationAckData {

    private final String message;

    public RegistrationAckData(String message) {
        this.message = message;
    }

    public String getMessage() {
        return message;
    }
}

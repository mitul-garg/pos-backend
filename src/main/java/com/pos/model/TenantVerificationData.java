package com.pos.model;

/**
 * Output DTO for {@code POST /api/tenants/verify} (C9) — {@code frontend/
 * requirements.md} §9: {@code { tenantCode }}, enough for the frontend to redirect to
 * {@code /login} with the tenant code already known.
 */
public class TenantVerificationData {

    private final String tenantCode;

    public TenantVerificationData(String tenantCode) {
        this.tenantCode = tenantCode;
    }

    public String getTenantCode() {
        return tenantCode;
    }
}

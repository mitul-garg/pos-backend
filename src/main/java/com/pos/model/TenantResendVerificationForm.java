package com.pos.model;

/**
 * Input DTO for {@code POST /api/tenants/resend-verification} (C9, public) — body
 * {@code { tenantCode, adminEmail }}. Both fields are required to re-mint a token: the
 * pair is the ownership check, the same reason {@code register} required
 * {@code adminEmail} in the first place.
 */
public class TenantResendVerificationForm {

    private String tenantCode;
    private String adminEmail;

    public String getTenantCode() {
        return tenantCode;
    }

    public void setTenantCode(String tenantCode) {
        this.tenantCode = tenantCode;
    }

    public String getAdminEmail() {
        return adminEmail;
    }

    public void setAdminEmail(String adminEmail) {
        this.adminEmail = adminEmail;
    }
}

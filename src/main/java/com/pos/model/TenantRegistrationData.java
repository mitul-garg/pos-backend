package com.pos.model;

/**
 * Output DTO for {@code POST /api/tenants/register} (C9) — {@code frontend/
 * requirements.md} §9: {@code { tenantCode, adminEmail }}.
 *
 * <p><b>Deliberately does not carry the verification token.</b> It only ever travels
 * inside the emailed link — returning it here would let anyone who can observe the
 * response (a proxy log, a browser extension) activate a tenant they don't own,
 * defeating the entire point of email verification.
 */
public class TenantRegistrationData {

    private final String tenantCode;
    private final String adminEmail;

    public TenantRegistrationData(String tenantCode, String adminEmail) {
        this.tenantCode = tenantCode;
        this.adminEmail = adminEmail;
    }

    public String getTenantCode() {
        return tenantCode;
    }

    public String getAdminEmail() {
        return adminEmail;
    }
}

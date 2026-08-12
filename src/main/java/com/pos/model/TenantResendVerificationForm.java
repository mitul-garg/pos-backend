package com.pos.model;

/**
 * Input DTO for {@code POST /api/tenants/resend-verification} (C9, public) — body
 * {@code { tenantCode, adminEmail }}. Both fields are required to re-mint a token: the
 * pair is the ownership check, the same reason {@code register} required
 * {@code adminEmail} in the first place.
 *
 * <p>{@code recaptchaToken} (peer-review Phase 0) — same widget, same check as
 * {@link TenantRegistrationForm}'s field of the same name. This endpoint does real
 * work (a DB write, an outbound email) from an unauthenticated caller too, so it's in
 * scope for the same reason {@code RegistrationRateLimiter} already treats it as a
 * peer of {@code register} rather than of {@code verify}.
 */
public class TenantResendVerificationForm {

    private String tenantCode;
    private String adminEmail;
    private String recaptchaToken;

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

    public String getRecaptchaToken() {
        return recaptchaToken;
    }

    public void setRecaptchaToken(String recaptchaToken) {
        this.recaptchaToken = recaptchaToken;
    }
}

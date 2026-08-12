package com.pos.model;

/**
 * Input DTO for {@code POST /api/tenants/register} (C9, public, unauthenticated) —
 * {@code frontend/requirements.md} §9's exact field list, matching
 * {@code tenantRegistrationService.http.js}'s {@code register()} payload verbatim so
 * the frontend built against the mock needs no shape change to go live.
 *
 * <p>A separate class from {@link TenantForm} despite the overlap (both create a
 * tenant + first admin) — this one carries {@code adminEmail}/{@code website} that
 * {@link TenantForm} doesn't, and comes from an unauthenticated caller, which
 * {@link TenantForm} never does. Sharing the class would make an accidental honeypot
 * field appear on the {@code SUPER_ADMIN} platform form's wire contract too.
 *
 * <p>{@code website} is the honeypot ({@code Honeypot.isTripped}) — a hidden field a
 * real browser never populates. No bean-validation annotations, matching every other
 * write form here: {@code TenantRegistrationWriter.register} reports every broken
 * field in one 400 rather than the first.
 *
 * <p>{@code recaptchaToken} (peer-review Phase 0) is the frontend's v2 checkbox
 * widget response, checked by {@code RecaptchaVerifier} — a real, loud gate, unlike
 * the honeypot's silent one. See {@code TenantRegistrationService.register}.
 */
public class TenantRegistrationForm {

    private String storeName;
    private String tenantCode;
    private String adminDisplayName;
    private String adminUsername;
    private String adminEmail;
    private String adminPassword;
    private String website;
    private String recaptchaToken;

    public String getStoreName() {
        return storeName;
    }

    public void setStoreName(String storeName) {
        this.storeName = storeName;
    }

    public String getTenantCode() {
        return tenantCode;
    }

    public void setTenantCode(String tenantCode) {
        this.tenantCode = tenantCode;
    }

    public String getAdminDisplayName() {
        return adminDisplayName;
    }

    public void setAdminDisplayName(String adminDisplayName) {
        this.adminDisplayName = adminDisplayName;
    }

    public String getAdminUsername() {
        return adminUsername;
    }

    public void setAdminUsername(String adminUsername) {
        this.adminUsername = adminUsername;
    }

    public String getAdminEmail() {
        return adminEmail;
    }

    public void setAdminEmail(String adminEmail) {
        this.adminEmail = adminEmail;
    }

    public String getAdminPassword() {
        return adminPassword;
    }

    public void setAdminPassword(String adminPassword) {
        this.adminPassword = adminPassword;
    }

    /** The honeypot. A real user never sees or fills this — see {@code Honeypot}. */
    public String getWebsite() {
        return website;
    }

    public void setWebsite(String website) {
        this.website = website;
    }

    /** The v2 checkbox widget's response token — see {@code RecaptchaVerifier}. */
    public String getRecaptchaToken() {
        return recaptchaToken;
    }

    public void setRecaptchaToken(String recaptchaToken) {
        this.recaptchaToken = recaptchaToken;
    }
}

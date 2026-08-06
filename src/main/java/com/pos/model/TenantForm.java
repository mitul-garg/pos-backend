package com.pos.model;

/**
 * Input DTO for {@code POST /api/tenants} (C8) — creates the tenant <b>and</b> its first
 * {@code ADMIN} in one call, matching {@code tenantService.create}'s
 * {@code {name, code, adminUsername, adminPassword}} shape exactly.
 *
 * <p>Deliberately a separate class from {@link TenantStatusForm} rather than one form
 * shared across every tenant write, the way {@code ProductForm} covers both
 * {@code POST} and {@code PUT}. A create and a suspend/reactivate share no fields at all
 * — this carries the new admin's credentials and {@link TenantStatusForm} carries none
 * of these plus a {@code status} this one doesn't have — so a single merged class would
 * only be four always-irrelevant-to-half-the-request fields, not a merge patch over one
 * resource shape.
 *
 * <p>No bean-validation annotations, matching every other write form here:
 * {@code TenantService.create} reports every broken field in one 400 rather than the
 * first, and a blank check belongs there for the same reason a blank product name does.
 */
public class TenantForm {

    private String name;
    private String code;
    private String adminUsername;
    private String adminPassword;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getAdminUsername() {
        return adminUsername;
    }

    public void setAdminUsername(String adminUsername) {
        this.adminUsername = adminUsername;
    }

    public String getAdminPassword() {
        return adminPassword;
    }

    public void setAdminPassword(String adminPassword) {
        this.adminPassword = adminPassword;
    }
}

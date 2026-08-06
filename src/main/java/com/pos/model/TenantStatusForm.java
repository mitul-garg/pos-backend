package com.pos.model;

import com.pos.pojo.TenantStatus;

/**
 * Input DTO for {@code PATCH /api/tenants/{id}} (C8) — suspend or reactivate, the only
 * thing this endpoint patches.
 *
 * <p><b>{@code status} typed as the enum, matching {@code OrderForm}'s identical field</b>
 * rather than a {@code String} re-validated by hand: an unrecognised value fails Jackson
 * binding and answers the same "Malformed request body" 400 an invalid
 * {@code OrderForm.status} already does. That is a deliberate departure from the mock's
 * own {@code "Invalid tenant status"} message — recorded here rather than reproduced,
 * the same way C6 recorded its own status-string decisions rather than silently taking
 * them.
 *
 * <p>Nothing else about a tenant is patchable here. Its {@code code} is the login
 * discriminator — changing it would lock out everyone who knows the old one — and its
 * {@code name} has no endpoint at all yet; both are absent from this form rather than
 * merely unused, so a client sending either is a silent no-op.
 */
public class TenantStatusForm {

    private TenantStatus status;

    public TenantStatus getStatus() {
        return status;
    }

    public void setStatus(TenantStatus status) {
        this.status = status;
    }
}

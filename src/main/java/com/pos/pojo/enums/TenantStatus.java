package com.pos.pojo.enums;

/**
 * Tenant lifecycle (requirements.md section 13). A {@code SUSPENDED} tenant cannot log
 * in — its users get a 403 even with correct credentials — and cannot transact. Only a
 * {@code SUPER_ADMIN} flips it.
 *
 * <p>The backend checks this <b>per request</b>, not only at login: the frontend mock
 * re-checked on {@code me()} alone, so a suspended tenant's open tab kept working until
 * refresh (backend-plan.md section 1, deferred obligation 5).
 *
 * <p><b>{@code PENDING_VERIFICATION}</b> (C9, `tenant-registration-plan.md`) — a
 * self-registered tenant's starting status, set by {@code POST /api/tenants/register}
 * and left only by a successful {@code POST /api/tenants/verify} (→ {@code ACTIVE}) or a
 * {@code SUPER_ADMIN} suspension (→ {@code SUSPENDED}, still reachable from here — a
 * still-pending tenant can be blocked before it ever goes live). Login's 403 for it is
 * distinguishable from {@link #SUSPENDED}'s, same reasoning as the existing suspended
 * check: only reachable once the password is proved, so naming the reason leaks nothing.
 * {@code PATCH /api/tenants/{id}} must never accept this as a *target* status — this
 * surface has no way to mint the token that status depends on, so admitting it here would
 * strand a row with no way out (mirrors the frontend's {@code PATCHABLE_STATUSES}).
 */
public enum TenantStatus {
    ACTIVE,
    SUSPENDED,
    PENDING_VERIFICATION
}

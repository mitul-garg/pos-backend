package com.pos.pojo;

/**
 * Tenant lifecycle (requirements.md section 13). A {@code SUSPENDED} tenant cannot log
 * in — its users get a 403 even with correct credentials — and cannot transact. Only a
 * {@code SUPER_ADMIN} flips it.
 *
 * <p>The backend checks this <b>per request</b>, not only at login: the frontend mock
 * re-checked on {@code me()} alone, so a suspended tenant's open tab kept working until
 * refresh (backend-plan.md section 1, deferred obligation 5).
 */
public enum TenantStatus {
    ACTIVE,
    SUSPENDED
}

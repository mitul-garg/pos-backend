package com.pos.pojo;

import java.util.List;

/**
 * Who a user is. Mirrors the frontend's {@code domain/roles.js} exactly — these strings
 * cross the wire, so renaming one is an API change.
 *
 * <p>{@code SUPER_ADMIN} is a <b>platform</b> role, not a superset of the tenant roles:
 * it manages tenants and belongs to none of them, so it deliberately cannot reach the
 * POS, catalogue or history surfaces (requirements.md section 13.2).
 */
public enum Role {

    SUPER_ADMIN,
    ADMIN,
    CASHIER;

    /**
     * The roles a tenant ADMIN may assign, mirroring the frontend's {@code TENANT_ROLES}.
     *
     * <p>{@code SUPER_ADMIN} is absent deliberately, and this list exists so that the
     * absence is stated somewhere a reviewer can see rather than implied. Letting a
     * tenant admin mint a {@code SUPER_ADMIN} is privilege escalation out of the tenant
     * — exactly the hole that opened in the frontend when the role was added to the
     * shared list (BUGS.md, Phase 8/B4). C8 enforces it on user creation.
     */
    public static final List<Role> TENANT_ROLES = List.of(ADMIN, CASHIER);
}

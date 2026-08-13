package com.pos.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pos.pojo.enums.Role;

/**
 * Who the caller is, as the database currently says. Returned by
 * {@code GET /api/auth/me}, nested inside {@link LoginData}, and carried as the
 * authenticated principal for the life of a request.
 *
 * <p>Mirrors the frontend's {@code toSessionUser()} field for field: the user minus the
 * credential, plus the resolved tenant's display fields so the header can name the
 * active store without a second fetch. Usernames repeat across tenants, so "admin"
 * alone does not tell an operator whose till they are on.
 *
 * <p><b>There is no password field and there must never be one.</b> That is the whole
 * reason CONVENTIONS.md forbids returning entities: {@code AppUserPojo} carries
 * {@code passwordHash}, and every "we accidentally returned the password hash" incident
 * starts with serializing one. {@code AuthControllerIT} asserts no response body ever
 * contains a BCrypt prefix.
 *
 * <p><b>A platform {@code SUPER_ADMIN} reports all three tenant fields as null</b>, even
 * though its row points at the reserved {@code platform} tenant. That row is a storage
 * detail — it exists because MySQL treats NULLs as distinct in a unique index, so a
 * nullable {@code tenant_id} would leave platform usernames unconstrained (see
 * {@code TenantPojo#isPlatform()}). The wire contract in requirements.md section 3 says
 * {@code tenantId: null}, and the frontend branches on it.
 */
public class SessionUserData {

    @JsonId
    private final Long id;

    /** Null for a platform user — see the class comment. */
    @JsonId
    private final Long tenantId;

    private final String username;
    private final String displayName;
    private final Role role;

    /**
     * {@code isActive} on the wire, not {@code active}: Jackson would name the property
     * after the field, and the frontend's shapes (requirements.md section 3) say
     * {@code isActive}.
     */
    @JsonProperty("isActive")
    private final boolean active;

    private final String tenantCode;
    private final String tenantName;

    public SessionUserData(Long id, Long tenantId, String username, String displayName,
                           Role role, boolean active, String tenantCode, String tenantName) {
        this.id = id;
        this.tenantId = tenantId;
        this.username = username;
        this.displayName = displayName;
        this.role = role;
        this.active = active;
        this.tenantCode = tenantCode;
        this.tenantName = tenantName;
    }

    public Long getId() {
        return id;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public String getUsername() {
        return username;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Role getRole() {
        return role;
    }

    public boolean isActive() {
        return active;
    }

    public String getTenantCode() {
        return tenantCode;
    }

    public String getTenantName() {
        return tenantName;
    }
}

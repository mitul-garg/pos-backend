package com.pos.model;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pos.pojo.Role;

/**
 * A user on the wire — requirements.md section 3, field for field with the frontend's
 * mock rows minus the credential (C8).
 *
 * <p><b>There is no password field and there must never be one.</b> Unlike the mock's
 * {@code sanitize()}, which strips {@code password} from an object that briefly carried
 * it, this shape never had one to begin with — {@code UserService} builds it from
 * {@link com.pos.pojo.AppUser} fields one at a time and simply never reads
 * {@code passwordHash}. That is the reason CONVENTIONS.md gives for DTOs over entities in
 * the first place: every "we accidentally returned the password hash" incident starts
 * with serializing the entity instead.
 */
public class UserData {

    @JsonId
    private final Long id;

    @JsonId
    private final Long tenantId;

    private final String username;
    private final String displayName;
    private final Role role;

    /** {@code isActive} on the wire — Jackson would otherwise name it after the field. */
    @JsonProperty("isActive")
    private final boolean active;

    private final Instant createdAt;

    public UserData(Long id, Long tenantId, String username, String displayName, Role role,
                    boolean active, Instant createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.username = username;
        this.displayName = displayName;
        this.role = role;
        this.active = active;
        this.createdAt = createdAt;
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

    public Instant getCreatedAt() {
        return createdAt;
    }
}

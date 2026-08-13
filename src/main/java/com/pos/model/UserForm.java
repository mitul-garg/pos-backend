package com.pos.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pos.pojo.enums.Role;

/**
 * Input DTO for {@code POST /api/users} and {@code PUT /api/users/{id}} (C8) — one form
 * for both, matching {@code ProductForm}'s pattern.
 *
 * <p><b>{@code PUT} is a merge patch, so every field is nullable and absent means "leave
 * alone"</b> — the same rule as every other write form in this application. It is also
 * narrower than the others on purpose: {@code UserService.update} never reads
 * {@link #getUsername()} at all, matching {@code userService.js}'s {@code update}, which
 * only ever assigns {@code displayName}, {@code role}, {@code password} and
 * {@code isActive} onto the existing row. A username survives its account for as long as
 * the account exists — changing it would mean nothing else on the wire needs a
 * {@code username} setter for a {@code PUT}, but the field stays on this shared DTO
 * rather than forking a second class for one missing setter's worth of difference.
 *
 * <p>No bean-validation annotations, for the same reason as every other merge-patch form:
 * {@code UserService} validates the field it is about to change, not "this request names
 * a user".
 */
public class UserForm {

    private String username;
    private String password;
    private String displayName;
    private Role role;

    /**
     * {@code isActive} on the wire. Read only by {@code update} — {@code create} always
     * produces an active account, matching {@code ProductForm.active}'s identical rule
     * for a created product.
     */
    @JsonProperty("isActive")
    private Boolean active;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }
}

package com.pos.controller;

import java.util.List;

import com.pos.model.UserData;
import com.pos.model.UserForm;
import com.pos.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/users} — requirements.md section 9, {@code ADMIN} only (C8).
 *
 * <p><b>Every method here is {@code ADMIN}-only, unlike {@code ProductController}.</b>
 * The catalogue's reads stay open to a {@code CASHIER}; user management does not —
 * requirements.md section 5.7 gates the whole screen, not just its writes, so
 * {@code SecurityConfig.adminMatchers()} lists {@code GET /api/users} alongside the
 * other three verbs rather than leaving it public the way {@code GET /api/products} is.
 *
 * <p>No {@code tenantId} on any signature, for the same reason as every other
 * controller: tenancy rides on the token, never the URL or the body.
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    @Autowired
    private UserService userService;

    @Operation(summary = "List users",
               description = "ADMIN only. The caller's own tenant only, sorted by "
                       + "username, credentials never included.")
    @GetMapping
    public List<UserData> list() {
        return userService.list();
    }

    /**
     * No {@code @Valid}: {@link UserForm} carries no constraints, because the same DTO
     * serves the merge-patch {@code PUT}. {@code UserService} validates the fields it is
     * about to act on instead.
     */
    @Operation(summary = "Create a user",
               description = "ADMIN only. 400 with field -> message for a blank "
                       + "username/password, a role outside ADMIN/CASHIER (a tenant "
                       + "admin may never mint a SUPER_ADMIN), or a username already "
                       + "taken in this tenant.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserData create(@RequestBody UserForm form) {
        return userService.create(form);
    }

    @Operation(summary = "Update a user",
               description = "ADMIN only, and a **merge patch** over displayName, role, "
                       + "password and isActive. `username` is never changed by this "
                       + "endpoint even if the body includes one. 404 for an id in "
                       + "another tenant.")
    @PutMapping("/{id}")
    public UserData update(@PathVariable Long id, @RequestBody UserForm form) {
        return userService.update(id, form);
    }

    @Operation(summary = "Deactivate a user",
               description = "ADMIN only. A soft delete, idempotent, returning the "
                       + "updated user. 400 if this is the tenant's last active ADMIN.")
    @DeleteMapping("/{id}")
    public UserData deactivate(@PathVariable Long id) {
        return userService.deactivate(id);
    }
}

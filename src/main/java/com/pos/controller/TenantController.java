package com.pos.controller;

import java.util.List;

import com.pos.model.TenantData;
import com.pos.model.TenantForm;
import com.pos.model.TenantStatusForm;
import com.pos.service.TenantService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/tenants} — requirements.md section 9 / section 13, {@code SUPER_ADMIN}
 * only (C8). The one controller whose every method reaches across the tenant boundary
 * on purpose; see {@code TenantService}'s Javadoc for why the gate lives in
 * {@code SecurityConfig} rather than here or there.
 *
 * <p>No {@code tenantId} on any signature — a platform caller has none of its own to
 * substitute, and the id in every path here names the tenant being <i>managed</i>,
 * never the caller's.
 */
@RestController
@RequestMapping("/api/tenants")
public class TenantController {

    @Autowired
    private TenantService tenantService;

    @Operation(summary = "List tenants",
               description = "SUPER_ADMIN only. Every tenant but the reserved platform "
                       + "row, newest first, each with the user/product/order counts a "
                       + "suspension decision needs.")
    @GetMapping
    public List<TenantData> list() {
        return tenantService.list();
    }

    @Operation(summary = "Get one tenant",
               description = "SUPER_ADMIN only. 404 for an id that doesn't exist and "
                       + "for the reserved platform row itself, which this surface "
                       + "does not manage.")
    @GetMapping("/{id}")
    public TenantData get(@PathVariable Long id) {
        return tenantService.get(id);
    }

    @Operation(summary = "Create a tenant",
               description = "SUPER_ADMIN only. Creates the store and its first ADMIN "
                       + "together, atomically -- a tenant with no admin could never be "
                       + "logged into. 400 with field -> message for a blank name, a "
                       + "malformed/reserved/duplicate code, or a blank admin "
                       + "username/password.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TenantData create(@RequestBody TenantForm form) {
        return tenantService.create(form);
    }

    @Operation(summary = "Suspend or reactivate a tenant",
               description = "SUPER_ADMIN only. The only patchable field is `status`; "
                       + "`code` and `name` are not editable here. A suspended tenant's "
                       + "users are locked out on their very next request, not merely "
                       + "at their next login.")
    @PatchMapping("/{id}")
    public TenantData updateStatus(@PathVariable Long id, @RequestBody TenantStatusForm form) {
        return tenantService.updateStatus(id, form);
    }
}

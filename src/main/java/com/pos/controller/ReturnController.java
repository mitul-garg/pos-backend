package com.pos.controller;

import com.pos.model.PageData;
import com.pos.model.ReturnData;
import com.pos.model.ReturnForm;
import com.pos.service.ReturnService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/returns} — requirements.md section 9 (C7). The fourth endpoint this
 * feature owns, {@code GET /api/orders/lookup}, lives on {@link OrderController}
 * instead, because its URL does — see that controller's Javadoc.
 *
 * <p>No endpoint here takes a {@code tenantId} or a {@code processedBy} on the write —
 * tenant and actor both ride the token, exactly as {@link OrderController} states for
 * orders. Reachable by both tenant roles: requirements.md section 5 gives returns to a
 * {@code CASHIER} exactly as it gives checkout, so {@code SecurityConfig} carries no
 * matcher for this path either. A {@code SUPER_ADMIN} is turned away by
 * {@code TenantContext.requireTenant()} in the service layer, the same tenancy-not-role
 * reason {@code OrderController} documents.
 */
@RestController
@RequestMapping("/api/returns")
public class ReturnController {

    @Autowired
    private ReturnService returnService;

    @Operation(summary = "List returns",
               description = "One page of the caller's own tenant, newest first. A "
                       + "CASHIER always sees only returns they processed — "
                       + "`processedBy` is forced to their session id regardless of "
                       + "what is passed. An ADMIN sees all by default, or one "
                       + "operator's with `processedBy`.")
    @GetMapping
    public PageData<ReturnData> list(
            @RequestParam(required = false) Long processedBy,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return returnService.list(processedBy, page, pageSize);
    }

    @Operation(summary = "Get one return",
               description = "404 if the id does not exist or belongs to another store.")
    @GetMapping("/{id}")
    public ReturnData get(@PathVariable Long id) {
        return returnService.get(id);
    }

    @Operation(summary = "Create a return",
               description = "Refunds against a completed order's own snapshotted sale "
                       + "prices and tax rates, never the variant's current row. "
                       + "Rejects any requested quantity beyond what remains "
                       + "unreturned on that line, restores stock for every unit "
                       + "returned, and mints this store's next return number. "
                       + "Tenant and processedBy come from the token; refundMethod "
                       + "defaults to the order's own payment method when omitted.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ReturnData create(@RequestBody ReturnForm form) {
        return returnService.create(form);
    }
}

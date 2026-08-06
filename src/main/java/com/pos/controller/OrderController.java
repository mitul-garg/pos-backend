package com.pos.controller;

import com.pos.model.OrderData;
import com.pos.model.OrderForm;
import com.pos.model.OrderLookupData;
import com.pos.model.PageData;
import com.pos.model.PaymentForm;
import com.pos.pojo.OrderStatus;
import com.pos.service.OrderService;
import com.pos.service.PaymentService;
import com.pos.service.ReturnService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/orders} — requirements.md section 9 (C6, plus the {@code /lookup}
 * endpoint C7 adds).
 *
 * <p><b>No endpoint here takes a {@code tenantId}, a {@code cashierId} on a write, or a
 * {@code processedBy}.</b> Tenant and actor both ride the token, exactly as
 * {@code ProductController}'s Javadoc states for the catalogue — an order endpoint that
 * accepted either would be a bug, not a shortcut.
 *
 * <p>Reachable by both tenant roles, unlike the catalogue: {@code SecurityConfig} carries
 * no matcher for this path, because requirements.md section 5's RBAC table gives checkout,
 * payment and hold/resume to a {@code CASHIER} as much as an {@code ADMIN}. A
 * {@code SUPER_ADMIN} is still turned away — not by a role rule, but by
 * {@code TenantContext.requireTenant()}, since it has no store to hold an order in.
 *
 * <p><b>{@code GET /lookup} belongs to returns, not orders</b> (C7) — it exists so a
 * return can be started from an order number — but it lives here because its URL does:
 * requirements.md section 9 names it {@code /api/orders/lookup}, not
 * {@code /api/returns/lookup}. {@code ReturnController} owns the rest of the feature.
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    @Autowired
    private OrderService orderService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private ReturnService returnService;

    @Operation(summary = "Look up a completed order for return",
               description = "Resolves within the caller's own tenant only — order "
                       + "numbers repeat across stores, each running its own "
                       + "ORD-YYYY-#### sequence from 1. 404 if no such order exists in "
                       + "this tenant; 400 if it exists but was never completed. Each "
                       + "line carries returnedQuantity/returnableQuantity alongside "
                       + "its usual fields.")
    @GetMapping("/lookup")
    public OrderLookupData lookup(@RequestParam String orderNumber) {
        return returnService.lookupOrder(orderNumber);
    }

    @Operation(summary = "List orders",
               description = "One page of the caller's own tenant, newest first. A "
                       + "CASHIER always sees only their own orders — `cashierId` is "
                       + "forced to their session id regardless of what is passed. An "
                       + "ADMIN sees all by default, or one operator's with `cashierId`.")
    @GetMapping
    public PageData<OrderData> list(
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) Long cashierId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return orderService.list(status, cashierId, page, pageSize);
    }

    @Operation(summary = "Get one order",
               description = "404 if the id does not exist or belongs to another store. "
                       + "Open to either role regardless of who rang it up — a reprint or "
                       + "a resume-by-id is not the cashier-scoped view `list` is.")
    @GetMapping("/{id}")
    public OrderData get(@PathVariable Long id) {
        return orderService.get(id);
    }

    @Operation(summary = "Create an order",
               description = "The cart becoming a DRAFT (on the way to Payment) or a "
                       + "HELD order (Checkout's Hold button). Every line's price and tax "
                       + "rate is re-derived from the variant's current row — nothing in "
                       + "the request body is trusted. Tenant and cashier come from the "
                       + "token.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderData create(@RequestBody OrderForm form) {
        return orderService.create(form);
    }

    @Operation(summary = "Hold / resume-and-reprice / cancel an order",
               description = "A merge patch. `items` replaces the line set and re-prices "
                       + "it from current variant rows; `orderDiscount` alone re-totals "
                       + "the existing lines; `status` transitions to HELD or CANCELLED "
                       + "only — COMPLETED is `POST .../payments` alone to set. 400 once "
                       + "the order is COMPLETED, from either side.")
    @PatchMapping("/{id}")
    public OrderData update(@PathVariable Long id, @RequestBody OrderForm form) {
        return orderService.update(id, form);
    }

    @Operation(summary = "Pay an order",
               description = "Single payment covering the full amount. Decrements stock "
                       + "line by line with an atomic conditional update — the hard "
                       + "availability check the client only ever warns about — and 400s "
                       + "naming the short line if any decrement fails, rolling back every "
                       + "decrement already applied in this call. Flips the order "
                       + "COMPLETED on success.")
    @PostMapping("/{id}/payments")
    public OrderData pay(@PathVariable Long id, @RequestBody PaymentForm form) {
        return paymentService.pay(id, form);
    }
}

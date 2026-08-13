package com.pos.model;

import java.math.BigDecimal;
import java.util.List;

import com.pos.pojo.enums.OrderStatus;

/**
 * Input DTO for {@code POST /api/orders} and {@code PATCH /api/orders/{id}} (C6) — one
 * form for both, matching {@code ProductForm}'s and {@code VariantForm}'s pattern.
 *
 * <p><b>{@code PATCH} is a merge patch, so every field is nullable and means "leave alone"
 * when absent</b> — the same rule as the {@code PUT} forms, spelled {@code PATCH} here
 * because that is the verb requirements.md section 9 actually specifies for orders (a
 * transition or a re-price, not a full replacement). {@code items} absent keeps the
 * order's current lines; {@code orderDiscount} absent keeps the current discount;
 * {@code status} absent leaves the lifecycle where it is. Sending {@code items} replaces
 * the line set wholesale — there is no per-line patch, matching
 * {@code orderService.update} in the mock, which always recomputes from a full array.
 *
 * <p><b>No {@code cashierId}.</b> The absence is the contract (backend-plan.md section 1):
 * the acting user comes from the JWT subject on every write, never the body.
 *
 * <p>No bean validation, for the same reason as the other merge-patch forms: a patch that
 * only changes {@code status} to {@code CANCELLED} carries no items at all, and a
 * {@code @NotEmpty} on the list would reject a perfectly legal request.
 */
public class OrderForm {

    private List<OrderLineForm> items;
    private BigDecimal orderDiscount;
    private OrderStatus status;

    public List<OrderLineForm> getItems() {
        return items;
    }

    public void setItems(List<OrderLineForm> items) {
        this.items = items;
    }

    public BigDecimal getOrderDiscount() {
        return orderDiscount;
    }

    public void setOrderDiscount(BigDecimal orderDiscount) {
        this.orderDiscount = orderDiscount;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
    }
}

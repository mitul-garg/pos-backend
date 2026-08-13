package com.pos.model;

import java.math.BigDecimal;

import com.pos.pojo.enums.PaymentMethod;

/**
 * Input DTO for {@code POST /api/orders/{id}/payments} (C6) — requirements.md section 3's
 * {@code Payment}, minus {@code amount}: the amount paid is always the order's own
 * {@code grandTotal}, recomputed server-side, never a figure the client states.
 *
 * <p>{@code amountTendered} is required, and validated, only for {@link PaymentMethod#CASH}.
 * {@code reference} is optional for every method — a dummy transaction id for card/UPI,
 * matching {@code paymentService.pay}'s {@code MOCK-<method>-<orderNumber>} fallback when
 * omitted.
 */
public class PaymentForm {

    private PaymentMethod method;
    private BigDecimal amountTendered;
    private String reference;

    public PaymentMethod getMethod() {
        return method;
    }

    public void setMethod(PaymentMethod method) {
        this.method = method;
    }

    public BigDecimal getAmountTendered() {
        return amountTendered;
    }

    public void setAmountTendered(BigDecimal amountTendered) {
        this.amountTendered = amountTendered;
    }

    public String getReference() {
        return reference;
    }

    public void setReference(String reference) {
        this.reference = reference;
    }
}

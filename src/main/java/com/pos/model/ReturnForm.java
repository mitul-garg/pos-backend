package com.pos.model;

import java.util.List;

import com.pos.pojo.PaymentMethod;

/**
 * Input DTO for {@code POST /api/returns} (C7) — requirements.md section 9.
 *
 * <p><b>No {@code processedBy}.</b> The absence is the contract, identical to
 * {@link OrderForm}'s missing {@code cashierId} (backend-plan.md section 1): the acting
 * user comes from the JWT subject, never the body.
 *
 * <p>{@code refundMethod} is optional and defaults to the original order's own payment
 * method when absent — {@code ReturnService.create} fills it in, matching
 * {@code returnService.create}'s {@code refundMethod || order.payment?.method} fallback
 * in the mock.
 */
public class ReturnForm {

    @JsonId
    private Long originalOrderId;

    private List<ReturnLineForm> items;
    private PaymentMethod refundMethod;
    private String reason;

    public Long getOriginalOrderId() {
        return originalOrderId;
    }

    public void setOriginalOrderId(Long originalOrderId) {
        this.originalOrderId = originalOrderId;
    }

    public List<ReturnLineForm> getItems() {
        return items;
    }

    public void setItems(List<ReturnLineForm> items) {
        this.items = items;
    }

    public PaymentMethod getRefundMethod() {
        return refundMethod;
    }

    public void setRefundMethod(PaymentMethod refundMethod) {
        this.refundMethod = refundMethod;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}

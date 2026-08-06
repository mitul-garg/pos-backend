package com.pos.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.pos.pojo.PaymentMethod;

/**
 * A return on the wire — requirements.md section 3's {@code Return}, field for field
 * with the mock so the C9 swap is body-only (C7).
 *
 * <p>Every amount here is computed from {@code originalOrder}'s own snapshotted lines,
 * never from a current variant price — see {@code ReturnService.create}.
 */
public class ReturnData {

    @JsonId
    private final Long id;

    @JsonId
    private final Long tenantId;

    private final String returnNumber;

    @JsonId
    private final Long originalOrderId;

    private final String originalOrderNumber;
    private final List<ReturnLineData> items;
    private final BigDecimal refundSubtotal;
    private final BigDecimal refundTax;
    private final BigDecimal roundOff;
    private final BigDecimal refundTotal;
    private final PaymentMethod refundMethod;
    private final String reason;

    @JsonId
    private final Long processedBy;

    private final Instant createdAt;

    public ReturnData(Long id, Long tenantId, String returnNumber, Long originalOrderId,
                      String originalOrderNumber, List<ReturnLineData> items,
                      BigDecimal refundSubtotal, BigDecimal refundTax, BigDecimal roundOff,
                      BigDecimal refundTotal, PaymentMethod refundMethod, String reason,
                      Long processedBy, Instant createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.returnNumber = returnNumber;
        this.originalOrderId = originalOrderId;
        this.originalOrderNumber = originalOrderNumber;
        this.items = items;
        this.refundSubtotal = refundSubtotal;
        this.refundTax = refundTax;
        this.roundOff = roundOff;
        this.refundTotal = refundTotal;
        this.refundMethod = refundMethod;
        this.reason = reason;
        this.processedBy = processedBy;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public String getReturnNumber() {
        return returnNumber;
    }

    public Long getOriginalOrderId() {
        return originalOrderId;
    }

    public String getOriginalOrderNumber() {
        return originalOrderNumber;
    }

    public List<ReturnLineData> getItems() {
        return items;
    }

    public BigDecimal getRefundSubtotal() {
        return refundSubtotal;
    }

    public BigDecimal getRefundTax() {
        return refundTax;
    }

    public BigDecimal getRoundOff() {
        return roundOff;
    }

    public BigDecimal getRefundTotal() {
        return refundTotal;
    }

    public PaymentMethod getRefundMethod() {
        return refundMethod;
    }

    public String getReason() {
        return reason;
    }

    public Long getProcessedBy() {
        return processedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

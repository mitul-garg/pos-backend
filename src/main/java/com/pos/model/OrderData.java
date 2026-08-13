package com.pos.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.pos.pojo.enums.OrderStatus;

/**
 * An order on the wire — requirements.md section 3's {@code Order}, field for field with
 * the mock so the C9 swap is body-only (C6).
 *
 * <p>{@code payment} is {@code null} until {@code POST /api/orders/{id}/payments}
 * completes it; every field below it is a snapshot, never recomputed from the variant's
 * current row after the fact. See {@code PosOrderPojo}'s Javadoc for why payment is embedded
 * rather than a child table.
 */
public class OrderData {

    @JsonId
    private final Long id;

    @JsonId
    private final Long tenantId;

    private final String orderNumber;
    private final List<OrderLineData> items;
    private final BigDecimal subtotal;
    private final BigDecimal totalTax;
    private final BigDecimal orderDiscount;
    private final BigDecimal roundOff;
    private final BigDecimal grandTotal;
    private final OrderStatus status;
    private final PaymentData payment;

    @JsonId
    private final Long cashierId;

    private final Instant createdAt;

    public OrderData(Long id, Long tenantId, String orderNumber, List<OrderLineData> items,
                     BigDecimal subtotal, BigDecimal totalTax, BigDecimal orderDiscount,
                     BigDecimal roundOff, BigDecimal grandTotal, OrderStatus status,
                     PaymentData payment, Long cashierId, Instant createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.orderNumber = orderNumber;
        this.items = items;
        this.subtotal = subtotal;
        this.totalTax = totalTax;
        this.orderDiscount = orderDiscount;
        this.roundOff = roundOff;
        this.grandTotal = grandTotal;
        this.status = status;
        this.payment = payment;
        this.cashierId = cashierId;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public List<OrderLineData> getItems() {
        return items;
    }

    public BigDecimal getSubtotal() {
        return subtotal;
    }

    public BigDecimal getTotalTax() {
        return totalTax;
    }

    public BigDecimal getOrderDiscount() {
        return orderDiscount;
    }

    public BigDecimal getRoundOff() {
        return roundOff;
    }

    public BigDecimal getGrandTotal() {
        return grandTotal;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public PaymentData getPayment() {
        return payment;
    }

    public Long getCashierId() {
        return cashierId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

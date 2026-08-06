package com.pos.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.pos.pojo.OrderStatus;

/**
 * {@code GET /api/orders/lookup?orderNumber=}'s response (C7) — the order shape with
 * returnable quantities folded into each line, matching the mock's
 * {@code returnService.lookupOrder}, which spreads {@code {...order, items}} over the
 * order it found.
 *
 * <p>A standalone class rather than reusing {@link OrderData}: that class's
 * {@code items} is typed {@code List<OrderLineData>}, and every other field here is
 * otherwise identical to it.
 */
public class OrderLookupData {

    @JsonId
    private final Long id;

    @JsonId
    private final Long tenantId;

    private final String orderNumber;
    private final List<OrderLookupLineData> items;
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

    public OrderLookupData(Long id, Long tenantId, String orderNumber,
                           List<OrderLookupLineData> items, BigDecimal subtotal,
                           BigDecimal totalTax, BigDecimal orderDiscount, BigDecimal roundOff,
                           BigDecimal grandTotal, OrderStatus status, PaymentData payment,
                           Long cashierId, Instant createdAt) {
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

    public List<OrderLookupLineData> getItems() {
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

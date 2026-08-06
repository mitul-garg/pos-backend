package com.pos.model;

import java.math.BigDecimal;

/**
 * One line of {@code GET /api/orders/lookup}'s response (C7) — {@link OrderLineData}'s
 * shape plus the two fields a return screen needs and an order screen doesn't: how much
 * of this line has already been returned, and how much of it remains returnable.
 *
 * <p>A separate class rather than adding the fields to {@link OrderLineData}: every
 * other reader of that class (an order's own {@code GET}/{@code list}) has no
 * already-returned quantity to report and shouldn't grow one just because this one
 * endpoint needs it.
 */
public class OrderLookupLineData {

    @JsonId
    private final Long variantId;

    private final String name;
    private final String qrCode;
    private final int quantity;
    private final BigDecimal unitPrice;
    private final BigDecimal taxRatePercent;
    private final BigDecimal lineDiscount;
    private final BigDecimal lineTotal;
    private final int returnedQuantity;
    private final int returnableQuantity;

    public OrderLookupLineData(Long variantId, String name, String qrCode, int quantity,
                               BigDecimal unitPrice, BigDecimal taxRatePercent,
                               BigDecimal lineDiscount, BigDecimal lineTotal,
                               int returnedQuantity, int returnableQuantity) {
        this.variantId = variantId;
        this.name = name;
        this.qrCode = qrCode;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.taxRatePercent = taxRatePercent;
        this.lineDiscount = lineDiscount;
        this.lineTotal = lineTotal;
        this.returnedQuantity = returnedQuantity;
        this.returnableQuantity = returnableQuantity;
    }

    public Long getVariantId() {
        return variantId;
    }

    public String getName() {
        return name;
    }

    public String getQrCode() {
        return qrCode;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public BigDecimal getTaxRatePercent() {
        return taxRatePercent;
    }

    public BigDecimal getLineDiscount() {
        return lineDiscount;
    }

    public BigDecimal getLineTotal() {
        return lineTotal;
    }

    public int getReturnedQuantity() {
        return returnedQuantity;
    }

    public int getReturnableQuantity() {
        return returnableQuantity;
    }
}

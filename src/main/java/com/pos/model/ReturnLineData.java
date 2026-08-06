package com.pos.model;

import java.math.BigDecimal;

/**
 * One line of a return on the wire — requirements.md section 3's return {@code items[]}
 * shape verbatim: {@code variantId, name, quantity, unitPrice, taxRatePercent,
 * lineRefund} (C7).
 *
 * <p>No {@code qrCode} or {@code lineDiscount} the way {@link OrderLineData} has — a
 * return line never discounts, and {@code ReturnLine} (the entity) carries no QR
 * snapshot either, matching the mock's return items shape exactly.
 */
public class ReturnLineData {

    @JsonId
    private final Long variantId;

    private final String name;
    private final int quantity;
    private final BigDecimal unitPrice;
    private final BigDecimal taxRatePercent;
    private final BigDecimal lineRefund;

    public ReturnLineData(Long variantId, String name, int quantity, BigDecimal unitPrice,
                          BigDecimal taxRatePercent, BigDecimal lineRefund) {
        this.variantId = variantId;
        this.name = name;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.taxRatePercent = taxRatePercent;
        this.lineRefund = lineRefund;
    }

    public Long getVariantId() {
        return variantId;
    }

    public String getName() {
        return name;
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

    public BigDecimal getLineRefund() {
        return lineRefund;
    }
}

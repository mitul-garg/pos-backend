package com.pos.model;

import java.math.BigDecimal;

/**
 * One line of an order on the wire — requirements.md section 3's {@code OrderLinePojo},
 * unchanged from the mock's shape (C6).
 *
 * <p><b>Every field here is a snapshot</b> taken when the line was priced — {@code name}
 * and {@code qrCode} record what was sold and under what label, {@code unitPrice} and
 * {@code taxRatePercent} record the terms. A later change to the variant's price or the
 * product's GST slab must not rewrite this line, and a refund (C7) reads these back
 * verbatim rather than re-pricing against the variant's current row.
 */
public class OrderLineData {

    @JsonId
    private final Long variantId;

    private final String name;
    private final String qrCode;
    private final int quantity;
    private final BigDecimal unitPrice;
    private final BigDecimal taxRatePercent;
    private final BigDecimal lineDiscount;
    private final BigDecimal lineTotal;

    public OrderLineData(Long variantId, String name, String qrCode, int quantity,
                         BigDecimal unitPrice, BigDecimal taxRatePercent,
                         BigDecimal lineDiscount, BigDecimal lineTotal) {
        this.variantId = variantId;
        this.name = name;
        this.qrCode = qrCode;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.taxRatePercent = taxRatePercent;
        this.lineDiscount = lineDiscount;
        this.lineTotal = lineTotal;
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
}

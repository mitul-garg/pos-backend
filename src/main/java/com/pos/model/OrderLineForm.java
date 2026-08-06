package com.pos.model;

import java.math.BigDecimal;

/**
 * One requested line of {@code POST /api/orders} or a {@code PATCH} that supplies
 * {@code items} (C6).
 *
 * <p><b>Deliberately thin.</b> The frontend's cart item also carries {@code name},
 * {@code qrCode}, {@code unitPrice} and {@code taxRatePercent} — all of them client-side
 * copies taken at scan time, and none of them trusted here (backend-plan.md section 1,
 * deferred obligation 2: "recompute every amount"). This form has no setters for any of
 * them, so a client that sends them anyway has those fields silently dropped by the
 * parser, exactly as an unexpected {@code tenantId} is on {@link ProductForm}.
 * {@code OrderService} re-derives all four from the variant itself, resolved through the
 * tenant filter.
 */
public class OrderLineForm {

    @JsonId
    private Long variantId;

    private Integer quantity;

    /** Defaults to zero when absent — matching the mock's {@code lineDiscount || 0}. */
    private BigDecimal lineDiscount;

    public Long getVariantId() {
        return variantId;
    }

    public void setVariantId(Long variantId) {
        this.variantId = variantId;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public BigDecimal getLineDiscount() {
        return lineDiscount;
    }

    public void setLineDiscount(BigDecimal lineDiscount) {
        this.lineDiscount = lineDiscount;
    }
}

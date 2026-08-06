package com.pos.model;

/**
 * One requested line of {@code POST /api/returns} (C7).
 *
 * <p>Deliberately thin, the same shape {@link OrderLineForm} takes and for the same
 * reason: nothing about price is trusted from the client. {@code ReturnService.create}
 * re-derives {@code unitPrice} and {@code taxRatePercent} from the <b>original order
 * line's own snapshot</b>, never from the variant's current row — a return refunds
 * what was actually charged, not today's price.
 */
public class ReturnLineForm {

    @JsonId
    private Long variantId;

    private Integer quantity;

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
}

package com.pos.model;

import java.math.BigDecimal;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pos.pojo.UnitOfMeasure;

/**
 * Input DTO for {@code POST /api/products/{productId}/variants} and
 * {@code PUT /api/variants/{id}} (C5).
 *
 * <p>One form for both, every field nullable, and no bean validation — all three for the
 * reasons written out on {@link ProductForm}. {@code PUT} is a merge patch, the frontend
 * reactivates with {@code {"isActive": true}} alone, and {@code VariantService} validates
 * the merged row rather than the request.
 *
 * <p><b>There is no {@code qrCode} field, and that is the contract rather than an
 * omission.</b> Requirements.md section 6 puts code generation on the server: the payload
 * embeds the tenant and takes the next value from that tenant's own sequence, so a client
 * that could supply one could mint a code belonging to another store's run. Re-issuing a
 * code is {@code POST /api/variants/{id}/qr-code}, which generates rather than accepts.
 *
 * <p><b>{@code sku} is optional on create</b>, matching the frontend's "auto-generated"
 * placeholder. Left blank, the server derives one from the same sequence value that names
 * the QR code, so the two agree and the result is unique within the tenant by the same
 * constraint.
 */
public class VariantForm {

    private String variantLabel;

    /** Free-form {@code {"size": "500ml"}}; replaced wholesale on update, not merged. */
    private Map<String, String> attributes;

    private String sku;

    private BigDecimal mrp;
    private BigDecimal sellingPrice;

    /** {@code Integer}, not {@code int}: absent has to be distinguishable from zero. */
    private Integer stockQuantity;

    private UnitOfMeasure unitOfMeasure;

    /** {@code isActive} on the wire. Honoured on update only; a new variant is active. */
    @JsonProperty("isActive")
    private Boolean active;

    public String getVariantLabel() {
        return variantLabel;
    }

    public void setVariantLabel(String variantLabel) {
        this.variantLabel = variantLabel;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }

    public void setAttributes(Map<String, String> attributes) {
        this.attributes = attributes;
    }

    public String getSku() {
        return sku;
    }

    public void setSku(String sku) {
        this.sku = sku;
    }

    public BigDecimal getMrp() {
        return mrp;
    }

    public void setMrp(BigDecimal mrp) {
        this.mrp = mrp;
    }

    public BigDecimal getSellingPrice() {
        return sellingPrice;
    }

    public void setSellingPrice(BigDecimal sellingPrice) {
        this.sellingPrice = sellingPrice;
    }

    public Integer getStockQuantity() {
        return stockQuantity;
    }

    public void setStockQuantity(Integer stockQuantity) {
        this.stockQuantity = stockQuantity;
    }

    public UnitOfMeasure getUnitOfMeasure() {
        return unitOfMeasure;
    }

    public void setUnitOfMeasure(UnitOfMeasure unitOfMeasure) {
        this.unitOfMeasure = unitOfMeasure;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }
}

package com.pos.model;

import java.math.BigDecimal;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pos.pojo.UnitOfMeasure;

/**
 * A variant on the wire (C5) — requirements.md section 3, plus the four <b>enriched</b>
 * fields the mock's {@code withProduct()} adds.
 *
 * <p><b>Why enrichment is part of the shape rather than a second endpoint.</b> A scan
 * resolves to one variant and the checkout screen has to build a cart line from it
 * immediately: the line needs a display name and the GST slab, and both live on the parent
 * product. Returning the bare row would make every scan two round trips, and the second
 * one would happen while a customer waits at a till.
 *
 * <p><b>Every response carries them, including the by-product list</b>, where the mock
 * returned the bare rows. One shape for one resource: the alternative is two DTOs whose
 * difference nobody can remember, and the extra fields cost one already-fetched
 * association. The frontend ignores what it does not use.
 *
 * <p>{@code taxRatePercent} and {@code hsnCode} are the parent's values copied onto the
 * variant, which is not denormalisation to tidy up later — <b>tax lives on the product
 * because every variant of it shares a slab</b> (see {@code Product}), and the client
 * needs it per line.
 */
public class VariantData {

    @JsonId
    private final Long id;

    @JsonId
    private final Long tenantId;

    @JsonId
    private final Long productId;

    private final String variantLabel;

    /** Free-form {@code {"size": "500ml"}}, stored as JSON on the row. */
    private final Map<String, String> attributes;

    private final String sku;

    /** The scanned payload, {@code POS-QR-{tenantId}-{seq}}. The image is never stored. */
    private final String qrCode;

    private final BigDecimal mrp;
    private final BigDecimal sellingPrice;
    private final int stockQuantity;
    private final UnitOfMeasure unitOfMeasure;

    /** {@code isActive} on the wire, for the reason on {@link ProductData}. */
    @JsonProperty("isActive")
    private final boolean active;

    // --- enriched from the parent product ---------------------------------------

    /** {@code "Amul Taaza Toned Milk — 500 ml"}: what a cart line is labelled. */
    private final String name;

    private final BigDecimal taxRatePercent;
    private final String hsnCode;
    private final ProductRefData product;

    public VariantData(Long id, Long tenantId, Long productId, String variantLabel,
                       Map<String, String> attributes, String sku, String qrCode,
                       BigDecimal mrp, BigDecimal sellingPrice, int stockQuantity,
                       UnitOfMeasure unitOfMeasure, boolean active,
                       String name, BigDecimal taxRatePercent, String hsnCode,
                       ProductRefData product) {
        this.id = id;
        this.tenantId = tenantId;
        this.productId = productId;
        this.variantLabel = variantLabel;
        this.attributes = attributes;
        this.sku = sku;
        this.qrCode = qrCode;
        this.mrp = mrp;
        this.sellingPrice = sellingPrice;
        this.stockQuantity = stockQuantity;
        this.unitOfMeasure = unitOfMeasure;
        this.active = active;
        this.name = name;
        this.taxRatePercent = taxRatePercent;
        this.hsnCode = hsnCode;
        this.product = product;
    }

    public Long getId() {
        return id;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public Long getProductId() {
        return productId;
    }

    public String getVariantLabel() {
        return variantLabel;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }

    public String getSku() {
        return sku;
    }

    public String getQrCode() {
        return qrCode;
    }

    public BigDecimal getMrp() {
        return mrp;
    }

    public BigDecimal getSellingPrice() {
        return sellingPrice;
    }

    public int getStockQuantity() {
        return stockQuantity;
    }

    public UnitOfMeasure getUnitOfMeasure() {
        return unitOfMeasure;
    }

    public boolean isActive() {
        return active;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getTaxRatePercent() {
        return taxRatePercent;
    }

    public String getHsnCode() {
        return hsnCode;
    }

    public ProductRefData getProduct() {
        return product;
    }
}

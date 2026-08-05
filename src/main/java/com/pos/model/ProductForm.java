package com.pos.model;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Input DTO for {@code POST /api/products} and {@code PUT /api/products/{id}} (C5).
 *
 * <p><b>One form serves both, and every field is nullable, because the {@code PUT} is a
 * merge patch.</b> The frontend reactivates a soft-deleted product by sending
 * {@code {"isActive": true}} and nothing else ({@code pages/Products/Products.jsx}), so a
 * field absent from the body means "leave it alone" rather than "set it to null". That is
 * why {@code active} is a {@code Boolean} and not a {@code boolean}: the wrapper is what
 * lets "not supplied" exist at all.
 *
 * <p><b>Deliberately carries no bean-validation annotations.</b> {@code @NotBlank} on
 * {@code name} would reject that reactivation patch, which is a legal request. The rule
 * being enforced is not "this request names a product" but "the product that results is
 * valid", so {@code ProductService} validates the <i>merged</i> row — exactly what the
 * mock does with {@code validateProduct(merged)} in {@code productService.js}. The
 * messages match it word for word so C9 needs no change on either side.
 *
 * <p><b>Note what is absent: {@code id}, {@code tenantId} and {@code createdAt}.</b> The
 * frontend's edit form PUTs the whole product it loaded, which includes all three, and the
 * absence of the fields here is precisely what makes a client-supplied {@code tenantId} a
 * no-op instead of a trusted input — {@code WebConfig}'s mapper ignores unknown
 * properties, so it is dropped in the parser and never reaches a query.
 */
public class ProductForm {

    private String name;
    private String brand;
    private String category;
    private String description;
    private String hsnCode;

    /** One of the GST slabs in {@code ProductService.TAX_RATES}: 0 / 5 / 12 / 18 / 28. */
    private BigDecimal taxRatePercent;

    /**
     * {@code isActive} on the wire — the field is {@code active} for the same reason
     * {@link ProductData}'s is. Honoured on update only; a created product is always
     * active, so sending {@code false} to {@code POST} does not create a dead row.
     */
    @JsonProperty("isActive")
    private Boolean active;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getBrand() {
        return brand;
    }

    public void setBrand(String brand) {
        this.brand = brand;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getHsnCode() {
        return hsnCode;
    }

    public void setHsnCode(String hsnCode) {
        this.hsnCode = hsnCode;
    }

    public BigDecimal getTaxRatePercent() {
        return taxRatePercent;
    }

    public void setTaxRatePercent(BigDecimal taxRatePercent) {
        this.taxRatePercent = taxRatePercent;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }
}

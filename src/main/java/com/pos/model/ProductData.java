package com.pos.model;

import java.math.BigDecimal;
import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A product on the wire — requirements.md section 3, field for field with the frontend's
 * mock rows so the mock-to-HTTP swap in C9 is body-only.
 *
 * <p><b>{@code tenantId} is reported, and that is not a contradiction of "the tenant is
 * never a parameter".</b> The rule is about <i>input</i>: no caller may state which
 * tenant it wants. Saying which tenant a row belongs to on the way out reveals nothing —
 * it is always the caller's own, because a row from any other tenant cannot be loaded in
 * the first place. The frontend's isolation suite asserts on this field, and
 * {@code TenantIsolationIT} ports those assertions unchanged.
 */
public class ProductData {

    @JsonId
    private final Long id;

    @JsonId
    private final Long tenantId;

    private final String name;
    private final String brand;
    private final String category;
    private final String description;
    private final String hsnCode;
    private final BigDecimal taxRatePercent;

    /** {@code isActive} on the wire — Jackson would otherwise name it after the field. */
    @JsonProperty("isActive")
    private final boolean active;

    private final Instant createdAt;

    public ProductData(Long id, Long tenantId, String name, String brand, String category,
                       String description, String hsnCode, BigDecimal taxRatePercent,
                       boolean active, Instant createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.name = name;
        this.brand = brand;
        this.category = category;
        this.description = description;
        this.hsnCode = hsnCode;
        this.taxRatePercent = taxRatePercent;
        this.active = active;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public String getName() {
        return name;
    }

    public String getBrand() {
        return brand;
    }

    public String getCategory() {
        return category;
    }

    public String getDescription() {
        return description;
    }

    public String getHsnCode() {
        return hsnCode;
    }

    public BigDecimal getTaxRatePercent() {
        return taxRatePercent;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

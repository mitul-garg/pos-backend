package com.pos.pojo;

import java.math.BigDecimal;
import java.time.Instant;

import com.pos.util.tenancy.TenantContext;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;

/**
 * The parent concept — "Amul Milk". <b>Not the thing that gets scanned</b>: that is
 * {@link VariantPojo}, and keeping the two apart is the core domain decision in
 * requirements.md section 2.
 *
 * <p>Tax lives here rather than on the variant, because every variant of a product
 * shares its GST slab.
 */
@Entity
@Filter(name = TenantContext.FILTER_NAME, condition = TenantContext.CONDITION)
@Table(
        name = "product",
        indexes = {
                @Index(name = "idx_product_tenant", columnList = "tenant_id"),
                @Index(name = "idx_product_tenant_category", columnList = "tenant_id, category")
        }
)
public class ProductPojo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "tenant_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_product_tenant")
    )
    private TenantPojo tenant;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "brand", length = 120)
    private String brand;

    /** Free text. The filter dropdown is a {@code DISTINCT} over this within a tenant. */
    @Column(name = "category", length = 80)
    private String category;

    @Column(name = "description", length = 500)
    private String description;

    /** India GST HSN code. Optional for now. */
    @Column(name = "hsn_code", length = 16)
    private String hsnCode;

    /**
     * GST slab: 0 / 5 / 12 / 18 / 28.
     *
     * <p>{@code DECIMAL(5,2)} rather than {@code INT} so that a half-percent slab would
     * not need a schema change. The allowed set is validated in the application layer.
     */
    @Column(name = "tax_rate_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal taxRatePercent;

    /** Soft delete. */
    @Column(name = "is_active", nullable = false)
    @ColumnDefault("true")
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public TenantPojo getTenant() {
        return tenant;
    }

    public void setTenant(TenantPojo tenant) {
        this.tenant = tenant;
    }

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

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}

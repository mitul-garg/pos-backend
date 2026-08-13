package com.pos.pojo;

import com.pos.pojo.enums.UnitOfMeasure;
import com.pos.util.tenancy.TenantContext;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.util.Map;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The SKU: what actually gets scanned, priced and stocked. <b>The QR code lives here,
 * never on the product</b> (requirements.md section 6).
 *
 * <p><b>Stock is authoritative here and nowhere else.</b> The client only warns when a
 * cart quantity exceeds it, because stock can change between terminals; the backend
 * rejects. C6 does that with an atomic conditional update
 * ({@code ... WHERE stock_quantity >= ?}) treating zero affected rows as the rejection,
 * rather than a read, a check and a write with a gap in between.
 */
@Entity
@Filter(name = TenantContext.FILTER_NAME, condition = TenantContext.CONDITION)
@Table(
        name = "variant",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_variant_tenant_sku",
                        columnNames = { "tenant_id", "sku" }
                ),
                @UniqueConstraint(
                        name = "uk_variant_tenant_qrcode",
                        columnNames = { "tenant_id", "qr_code" }
                )
        },
        indexes = {
                @Index(name = "idx_variant_tenant", columnList = "tenant_id"),
                @Index(name = "idx_variant_product", columnList = "product_id")
        }
)
// MRP is the legal tax-inclusive ceiling, and stock cannot go negative -- the atomic
// decrement in C6 relies on the latter as its backstop. Both need MySQL 8.0.16+; below
// that they parse and are silently ignored, which is why the application-level
// validation is NOT redundant.
@Check(name = "ck_variant_price_within_mrp", constraints = "selling_price <= mrp")
@Check(name = "ck_variant_stock_not_negative", constraints = "stock_quantity >= 0")
public class VariantPojo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * The id only, not the entity — peer-review Phase 2 decision: minimize
     * {@code @ManyToOne} navigation, keep the DB-level FK constraint. See
     * {@link #tenantFk}'s Javadoc for the mechanism.
     */
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    /**
     * <b>DDL-only shadow association — never navigate this.</b> Exists purely so Hibernate's
     * schema generation still emits {@code fk_variant_tenant}. See
     * {@code ProductPojo#tenantFk}'s Javadoc for the full reasoning, identical here.
     */
    @SuppressWarnings("unused")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "tenant_id",
            insertable = false,
            updatable = false,
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_variant_tenant")
    )
    private TenantPojo tenantFk;

    /** The id only, not the entity — same reasoning as {@link #tenantId} above. */
    @Column(name = "product_id", nullable = false)
    private Long productId;

    /**
     * <b>DDL-only shadow association — never navigate this.</b> Exists purely so Hibernate's
     * schema generation still emits {@code fk_variant_product}. See
     * {@code ProductPojo#tenantFk}'s Javadoc for the full reasoning, identical here.
     */
    @SuppressWarnings("unused")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "product_id",
            insertable = false,
            updatable = false,
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_variant_product")
    )
    private ProductPojo productFk;

    /** Human label — "500 ml", "Large / Red". */
    @Column(name = "variant_label", nullable = false, length = 120)
    private String variantLabel;

    /** Flexible key/values, e.g. {@code {"size": "500ml"}}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attributes")
    private Map<String, String> attributes;

    /** Internal code. Unique per tenant — the seeds use {@code BISLERI-1L} in both. */
    @Column(name = "sku", nullable = false, length = 64)
    private String sku;

    /**
     * The scanned payload, {@code POS-QR-{tenantId}-{seq}}. The QR <i>image</i> is
     * rendered from this on the frontend and never stored (a Phase-2 decision that pays
     * off at deploy time: nothing needs a writable disk).
     *
     * <p>Unique per tenant. The tenant segment makes printed labels globally distinct
     * anyway, so a label from one store can never resolve in another — and the lookup is
     * tenant-scoped besides, so it 404s rather than matching.
     */
    @Column(name = "qr_code", nullable = false, length = 64)
    private String qrCode;

    /** Tax-inclusive ceiling. */
    @Column(name = "mrp", nullable = false, precision = 12, scale = 2)
    private BigDecimal mrp;

    @Column(name = "selling_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal sellingPrice;

    /** Decremented on payment, restored on refund. */
    @Column(name = "stock_quantity", nullable = false)
    @ColumnDefault("0")
    private int stockQuantity;

    // VARCHAR, not MySQL's native ENUM: adding a value to a MySQL ENUM is a table
    // alter, and hbm2ddl.auto=update will not perform it. Hibernate 6 defaults to the
    // native type on MySQL, so this has to be said explicitly on every enum field.
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "unit_of_measure", nullable = false, length = 8)
    @ColumnDefault("'EACH'")
    private UnitOfMeasure unitOfMeasure = UnitOfMeasure.EACH;

    /** Soft delete. An inactive variant stays scannable but is not sellable. */
    @Column(name = "is_active", nullable = false)
    @ColumnDefault("true")
    private boolean active = true;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

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

    public String getQrCode() {
        return qrCode;
    }

    public void setQrCode(String qrCode) {
        this.qrCode = qrCode;
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

    public int getStockQuantity() {
        return stockQuantity;
    }

    public void setStockQuantity(int stockQuantity) {
        this.stockQuantity = stockQuantity;
    }

    public UnitOfMeasure getUnitOfMeasure() {
        return unitOfMeasure;
    }

    public void setUnitOfMeasure(UnitOfMeasure unitOfMeasure) {
        this.unitOfMeasure = unitOfMeasure;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}

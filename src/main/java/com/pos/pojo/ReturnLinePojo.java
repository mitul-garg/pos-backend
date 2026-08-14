package com.pos.pojo;

import java.math.BigDecimal;

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
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * One returned line.
 *
 * <p>The prices here are <b>snapshots from the original sale</b>, not current prices —
 * refund maths reuses the sale's own numbers so a price change between sale and return
 * cannot alter what the customer gets back.
 *
 * <p><b>Reached only through {@code ReturnLineDao}</b>, never through a navigable
 * association — peer-review Phase 2 dropped every {@code @ManyToOne} here (and the
 * matching {@code @OneToMany} on {@link SalesReturnPojo}) the same way it did across
 * every other entity. See {@link #tenantFk}'s Javadoc for the shadow-association
 * mechanism that keeps the FK constraints without the navigation.
 */
@Entity
@Filter(name = TenantContext.FILTER_NAME, condition = TenantContext.CONDITION)
@Table(
        name = "return_line",
        indexes = @Index(name = "idx_returnline_return", columnList = "return_id")
)
public class ReturnLinePojo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * Denormalised for the same reason as {@link OrderLinePojo}'s. The tenant's id, not
     * the entity — peer-review Phase 2 decision: minimize {@code @ManyToOne} navigation,
     * keep the DB-level FK constraint. See {@link #tenantFk}'s Javadoc for the mechanism.
     */
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    /**
     * <b>DDL-only shadow association — never navigate this.</b> Exists purely so Hibernate's
     * schema generation still emits {@code fk_return_line_tenant}, the real
     * database-enforced constraint this project keeps even though the Java-level
     * relationship is gone. {@code insertable = false, updatable = false} means it never
     * participates in a write — {@link #tenantId} above is what {@code ReturnLineDao}'s
     * queries actually use — and it has deliberately no getter or setter. Every entity in
     * this codebase uses field access (the {@code @Id} is placed on the field), so
     * Hibernate never needs an accessor either; with none exposed, nothing outside this
     * file can reach it. If you find yourself wanting to add one, that's a sign a
     * {@code ReturnLineDao}/{@code TenantDao} method is missing instead.
     */
    @SuppressWarnings("unused")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "tenant_id",
            insertable = false,
            updatable = false,
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_return_line_tenant")
    )
    private TenantPojo tenantFk;

    /**
     * The parent return's id, not the entity — same peer-review Phase 2 decision as
     * {@link #tenantId}. {@code ReturnLineDao.findByReturn}/{@code findByReturns} is how
     * a caller reads the lines of a given return; there is no reverse navigation from here.
     */
    @Column(name = "return_id", nullable = false)
    private Long returnId;

    /**
     * <b>DDL-only shadow association — never navigate this.</b> See {@link #tenantFk}.
     * {@code @OnDelete} still applies to schema generation from this read-only mapping —
     * lines die with their return, and this is what puts {@code ON DELETE CASCADE} in the
     * DDL so the database enforces it even though the JPA-level cascade
     * ({@code SalesReturnPojo.lines}' old {@code cascade = ALL, orphanRemoval = true}) is
     * gone; {@code ReturnService}/{@code ReturnLineDao} handle the insert explicitly now.
     */
    @SuppressWarnings("unused")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "return_id",
            insertable = false,
            updatable = false,
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_return_line_sales_return")
    )
    @OnDelete(action = OnDeleteAction.CASCADE)
    private SalesReturnPojo returnFk;

    /**
     * What was returned — its id, not the entity. Same peer-review Phase 2 decision as
     * {@link #tenantId}; call {@code VariantDao.find(getVariantId())} on the rare
     * occasion a caller actually needs the related row.
     */
    @Column(name = "variant_id", nullable = false)
    private Long variantId;

    /** <b>DDL-only shadow association — never navigate this.</b> See {@link #tenantFk}. */
    @SuppressWarnings("unused")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "variant_id",
            insertable = false,
            updatable = false,
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_return_line_variant")
    )
    private VariantPojo variantFk;

    /** <b>Snapshot</b>. */
    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    /** <b>Snapshot from the original sale</b>, not the current price. */
    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    /** <b>Snapshot</b>. */
    @Column(name = "tax_rate_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal taxRatePercent;

    @Column(name = "line_refund", nullable = false, precision = 12, scale = 2)
    private BigDecimal lineRefund;

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

    public Long getReturnId() {
        return returnId;
    }

    public void setReturnId(Long returnId) {
        this.returnId = returnId;
    }

    public Long getVariantId() {
        return variantId;
    }

    public void setVariantId(Long variantId) {
        this.variantId = variantId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public void setUnitPrice(BigDecimal unitPrice) {
        this.unitPrice = unitPrice;
    }

    public BigDecimal getTaxRatePercent() {
        return taxRatePercent;
    }

    public void setTaxRatePercent(BigDecimal taxRatePercent) {
        this.taxRatePercent = taxRatePercent;
    }

    public BigDecimal getLineRefund() {
        return lineRefund;
    }

    public void setLineRefund(BigDecimal lineRefund) {
        this.lineRefund = lineRefund;
    }
}

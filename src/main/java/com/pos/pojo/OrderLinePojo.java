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
import org.hibernate.annotations.Check;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * One line of a sale.
 *
 * <p><b>The snapshot columns are the point of this table</b>, not denormalisation to be
 * cleaned up later. {@code variantId} records <i>what</i> was sold; {@code name},
 * {@code unitPrice} and {@code taxRatePercent} record <i>on what terms</i>. A later
 * price change must not rewrite history, and a refund reads these back verbatim.
 *
 * <p><b>Reached only through {@code OrderLineDao}</b>, never through a navigable
 * association — peer-review Phase 2 dropped every {@code @ManyToOne} here (and the
 * matching {@code @OneToMany} on {@link PosOrderPojo}) the same way it did across every
 * other entity. See {@link #tenantFk}'s Javadoc for the shadow-association mechanism
 * that keeps the FK constraints without the navigation.
 */
@Entity
@Filter(name = TenantContext.FILTER_NAME, condition = TenantContext.CONDITION)
@Table(
        name = "order_line",
        indexes = @Index(name = "idx_orderline_order", columnList = "order_id")
)
// A zero-quantity line is meaningless -- removing the line is how you reach zero.
@Check(name = "ck_order_line_quantity_positive", constraints = "quantity > 0")
public class OrderLinePojo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * Denormalised deliberately. A line is only reachable through its order, so this
     * column is redundant on paper — but it lets the C4 tenant filter apply uniformly to
     * <b>every</b> entity, so no table depends on a <i>join</i> being scoped correctly
     * in order to stay isolated. Each table is independently safe.
     *
     * <p>The tenant's id, not the entity — peer-review Phase 2 decision: minimize
     * {@code @ManyToOne} navigation, keep the DB-level FK constraint. See
     * {@link #tenantFk}'s Javadoc for the mechanism.
     */
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    /**
     * <b>DDL-only shadow association — never navigate this.</b> Exists purely so Hibernate's
     * schema generation still emits {@code fk_order_line_tenant}, the real database-enforced
     * constraint this project keeps even though the Java-level relationship is gone.
     * {@code insertable = false, updatable = false} means it never participates in a write —
     * {@link #tenantId} above is what {@code OrderLineDao}'s queries actually use — and it
     * has deliberately no getter or setter. Every entity in this codebase uses field access
     * (the {@code @Id} is placed on the field), so Hibernate never needs an accessor either;
     * with none exposed, nothing outside this file can reach it. If you find yourself wanting
     * to add one, that's a sign an {@code OrderLineDao}/{@code TenantDao} method is missing
     * instead.
     */
    @SuppressWarnings("unused")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "tenant_id",
            insertable = false,
            updatable = false,
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_order_line_tenant")
    )
    private TenantPojo tenantFk;

    /**
     * The parent order's id, not the entity — same peer-review Phase 2 decision as
     * {@link #tenantId}. {@code OrderLineDao.findByOrder}/{@code findByOrders} is how a
     * caller reads the lines of a given order; there is no reverse navigation from here.
     */
    @Column(name = "order_id", nullable = false)
    private Long orderId;

    /**
     * <b>DDL-only shadow association — never navigate this.</b> See {@link #tenantFk}.
     * {@code @OnDelete} still applies to schema generation from this read-only mapping —
     * lines die with their order, and this is what puts {@code ON DELETE CASCADE} in the
     * DDL so the database enforces it even though the JPA-level cascade
     * ({@code PosOrderPojo.lines}' old {@code cascade = ALL, orphanRemoval = true}) is
     * gone; {@code OrderService}/{@code OrderLineDao} handle the application-level side
     * explicitly now.
     */
    @SuppressWarnings("unused")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "order_id",
            insertable = false,
            updatable = false,
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_order_line_pos_order")
    )
    @OnDelete(action = OnDeleteAction.CASCADE)
    private PosOrderPojo orderFk;

    /**
     * What was sold — its id, not the entity. Same peer-review Phase 2 decision as
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
            foreignKey = @ForeignKey(name = "fk_order_line_variant")
    )
    private VariantPojo variantFk;

    /** <b>Snapshot</b> — product name plus variant label, as they read at sale time. */
    @Column(name = "name", nullable = false, length = 255)
    private String name;

    /** <b>Snapshot</b>. */
    @Column(name = "qr_code", length = 64)
    private String qrCode;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    /** <b>Snapshot</b> of the selling price actually applied. */
    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    /** <b>Snapshot</b> of the product's GST slab. */
    @Column(name = "tax_rate_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal taxRatePercent;

    @Column(name = "line_discount", nullable = false, precision = 12, scale = 2)
    @ColumnDefault("0")
    private BigDecimal lineDiscount = BigDecimal.ZERO;

    @Column(name = "line_total", nullable = false, precision = 12, scale = 2)
    private BigDecimal lineTotal;

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

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
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

    public String getQrCode() {
        return qrCode;
    }

    public void setQrCode(String qrCode) {
        this.qrCode = qrCode;
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

    public BigDecimal getLineDiscount() {
        return lineDiscount;
    }

    public void setLineDiscount(BigDecimal lineDiscount) {
        this.lineDiscount = lineDiscount;
    }

    public BigDecimal getLineTotal() {
        return lineTotal;
    }

    public void setLineTotal(BigDecimal lineTotal) {
        this.lineTotal = lineTotal;
    }
}

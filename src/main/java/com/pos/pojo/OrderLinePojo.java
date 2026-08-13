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
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "tenant_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_order_line_tenant")
    )
    private TenantPojo tenant;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "order_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_order_line_pos_order")
    )
    // Lines die with their order. The JPA cascade on PosOrderPojo handles the ORM side;
    // this puts ON DELETE CASCADE in the DDL so the database enforces it too.
    @OnDelete(action = OnDeleteAction.CASCADE)
    private PosOrderPojo order;

    /** What was sold. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "variant_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_order_line_variant")
    )
    private VariantPojo variant;

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

    public TenantPojo getTenant() {
        return tenant;
    }

    public void setTenant(TenantPojo tenant) {
        this.tenant = tenant;
    }

    public PosOrderPojo getOrder() {
        return order;
    }

    public void setOrder(PosOrderPojo order) {
        this.order = order;
    }

    public VariantPojo getVariant() {
        return variant;
    }

    public void setVariant(VariantPojo variant) {
        this.variant = variant;
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

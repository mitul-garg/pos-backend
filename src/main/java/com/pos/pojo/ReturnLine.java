package com.pos.pojo;

import java.math.BigDecimal;

import com.pos.util.TenantContext;
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
 */
@Entity
@Filter(name = TenantContext.FILTER_NAME, condition = TenantContext.CONDITION)
@Table(
        name = "return_line",
        indexes = @Index(name = "idx_returnline_return", columnList = "return_id")
)
public class ReturnLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** Denormalised for the same reason as {@link OrderLine}'s. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "tenant_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_return_line_tenant")
    )
    private Tenant tenant;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "return_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_return_line_sales_return")
    )
    @OnDelete(action = OnDeleteAction.CASCADE)
    private SalesReturn salesReturn;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "variant_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_return_line_variant")
    )
    private Variant variant;

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

    public Tenant getTenant() {
        return tenant;
    }

    public void setTenant(Tenant tenant) {
        this.tenant = tenant;
    }

    public SalesReturn getSalesReturn() {
        return salesReturn;
    }

    public void setSalesReturn(SalesReturn salesReturn) {
        this.salesReturn = salesReturn;
    }

    public Variant getVariant() {
        return variant;
    }

    public void setVariant(Variant variant) {
        this.variant = variant;
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

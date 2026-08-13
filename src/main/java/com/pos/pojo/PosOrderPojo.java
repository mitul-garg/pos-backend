package com.pos.pojo;

import com.pos.pojo.enums.OrderStatus;
import com.pos.pojo.enums.PaymentMethod;
import com.pos.util.TenantContext;
import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One real-world sale. The table is {@code pos_order} because {@code order} is a SQL
 * reserved word.
 *
 * <p><b>Payment is embedded, not a child table.</b> v1 is a single payment covering the
 * full amount, with no split tender (requirements.md section 3). If split tender ever
 * arrives, these five columns are the join to extract.
 *
 * <p>All totals are <b>recomputed server-side</b> — a client-sent amount is never
 * trusted. A {@code COMPLETED} order is immutable.
 */
@Entity
@Filter(name = TenantContext.FILTER_NAME, condition = TenantContext.CONDITION)
@Table(
        name = "pos_order",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_order_tenant_number",
                columnNames = { "tenant_id", "order_number" }
        ),
        indexes = {
                @Index(name = "idx_order_tenant_status", columnList = "tenant_id, status"),
                @Index(name = "idx_order_tenant_cashier", columnList = "tenant_id, cashier_id"),
                @Index(name = "idx_order_tenant_created", columnList = "tenant_id, created_at")
        }
)
public class PosOrderPojo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "tenant_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_pos_order_tenant")
    )
    private TenantPojo tenant;

    /**
     * {@code ORD-YYYY-NNNN}, unique per tenant — both seeded stores have an
     * {@code ORD-2026-0001}.
     *
     * <p>Minted from {@link TenantSequencePojo} under {@code SELECT ... FOR UPDATE} in the
     * same transaction as this insert (C6). {@code MAX(number)+1} races, and going
     * per-tenant is what cost us {@code AUTO_INCREMENT} here in the first place.
     */
    @Column(name = "order_number", nullable = false, length = 32)
    private String orderNumber;

    // VARCHAR, not MySQL's native ENUM: adding a value to a MySQL ENUM is a table
    // alter, and hbm2ddl.auto=update will not perform it. Hibernate 6 defaults to the
    // native type on MySQL, so this has to be said explicitly on every enum field.
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 16)
    private OrderStatus status = OrderStatus.DRAFT;

    @Column(name = "subtotal", nullable = false, precision = 12, scale = 2)
    private BigDecimal subtotal;

    /** GST contained <i>within</i> the inclusive prices, not added on top. */
    @Column(name = "total_tax", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalTax;

    @Column(name = "order_discount", nullable = false, precision = 12, scale = 2)
    @ColumnDefault("0")
    private BigDecimal orderDiscount = BigDecimal.ZERO;

    /** The delta from rounding the grand total to the nearest rupee. */
    @Column(name = "round_off", nullable = false, precision = 12, scale = 2)
    private BigDecimal roundOff = BigDecimal.ZERO;

    @Column(name = "grand_total", nullable = false, precision = 12, scale = 2)
    private BigDecimal grandTotal;

    // --- Payment (embedded; null until paid) ---------------------------------------

    // VARCHAR, not MySQL's native ENUM: adding a value to a MySQL ENUM is a table
    // alter, and hbm2ddl.auto=update will not perform it. Hibernate 6 defaults to the
    // native type on MySQL, so this has to be said explicitly on every enum field.
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "payment_method", length = 16)
    private PaymentMethod paymentMethod;

    /** Equals {@link #grandTotal} — no partial payment in v1. */
    @Column(name = "payment_amount", precision = 12, scale = 2)
    private BigDecimal paymentAmount;

    /** Cash only. */
    @Column(name = "amount_tendered", precision = 12, scale = 2)
    private BigDecimal amountTendered;

    /** Cash only. */
    @Column(name = "change_due", precision = 12, scale = 2)
    private BigDecimal changeDue;

    /** Transaction or UPI reference. */
    @Column(name = "payment_reference", length = 64)
    private String paymentReference;

    // --------------------------------------------------------------------------------

    /**
     * Who rang it up. <b>From the JWT subject, never the request body</b> — the frontend
     * service signature deliberately has no {@code cashierId} parameter, and that
     * absence is part of the contract.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "cashier_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_pos_order_cashier")
    )
    private AppUserPojo cashier;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Cascades because lines have no life of their own — they are part of the order, not
     * an associated record. {@code orphanRemoval} means dropping a line from this list
     * deletes the row, which is what editing a held order's cart does.
     */
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderLinePojo> lines = new ArrayList<>();

    /** Keeps both sides of the association consistent, which cascading relies on. */
    public void addLine(OrderLinePojo line) {
        lines.add(line);
        line.setOrder(this);
    }

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

    public String getOrderNumber() {
        return orderNumber;
    }

    public void setOrderNumber(String orderNumber) {
        this.orderNumber = orderNumber;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
    }

    public BigDecimal getSubtotal() {
        return subtotal;
    }

    public void setSubtotal(BigDecimal subtotal) {
        this.subtotal = subtotal;
    }

    public BigDecimal getTotalTax() {
        return totalTax;
    }

    public void setTotalTax(BigDecimal totalTax) {
        this.totalTax = totalTax;
    }

    public BigDecimal getOrderDiscount() {
        return orderDiscount;
    }

    public void setOrderDiscount(BigDecimal orderDiscount) {
        this.orderDiscount = orderDiscount;
    }

    public BigDecimal getRoundOff() {
        return roundOff;
    }

    public void setRoundOff(BigDecimal roundOff) {
        this.roundOff = roundOff;
    }

    public BigDecimal getGrandTotal() {
        return grandTotal;
    }

    public void setGrandTotal(BigDecimal grandTotal) {
        this.grandTotal = grandTotal;
    }

    public PaymentMethod getPaymentMethod() {
        return paymentMethod;
    }

    public void setPaymentMethod(PaymentMethod paymentMethod) {
        this.paymentMethod = paymentMethod;
    }

    public BigDecimal getPaymentAmount() {
        return paymentAmount;
    }

    public void setPaymentAmount(BigDecimal paymentAmount) {
        this.paymentAmount = paymentAmount;
    }

    public BigDecimal getAmountTendered() {
        return amountTendered;
    }

    public void setAmountTendered(BigDecimal amountTendered) {
        this.amountTendered = amountTendered;
    }

    public BigDecimal getChangeDue() {
        return changeDue;
    }

    public void setChangeDue(BigDecimal changeDue) {
        this.changeDue = changeDue;
    }

    public String getPaymentReference() {
        return paymentReference;
    }

    public void setPaymentReference(String paymentReference) {
        this.paymentReference = paymentReference;
    }

    public AppUserPojo getCashier() {
        return cashier;
    }

    public void setCashier(AppUserPojo cashier) {
        this.cashier = cashier;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public List<OrderLinePojo> getLines() {
        return lines;
    }

    public void setLines(List<OrderLinePojo> lines) {
        this.lines = lines;
    }
}

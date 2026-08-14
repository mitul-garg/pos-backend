package com.pos.pojo;

import com.pos.pojo.enums.OrderStatus;
import com.pos.pojo.enums.PaymentMethod;
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
import java.time.Instant;
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
 *
 * <p><b>Its lines are not a navigable collection.</b> Peer-review Phase 2 dropped the old
 * {@code @OneToMany(mappedBy = "order", cascade = ALL, orphanRemoval = true) lines} field
 * along with every other {@code @ManyToOne} on this entity — see {@link #tenantFk}'s
 * Javadoc for the mechanism. {@code OrderLineDao} is now how a caller reads, inserts or
 * replaces this order's lines; {@code OrderService.rebuildLines}'s
 * {@code order.getLines().clear()} + {@code order.addLine(line)} became an explicit
 * {@code orderLineDao.deleteByOrder}/{@code insertAll} pair.
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

    /**
     * The tenant's id, not the entity — peer-review Phase 2 decision: minimize
     * {@code @ManyToOne} navigation, keep the DB-level FK constraint. Read {@link #tenantFk}'s
     * Javadoc for the mechanism; call {@code TenantDao.find(getTenantId())} on the rare
     * occasion a caller actually needs the related row.
     */
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    /**
     * <b>DDL-only shadow association — never navigate this.</b> Exists purely so Hibernate's
     * schema generation still emits {@code fk_pos_order_tenant}, the real database-enforced
     * constraint this project keeps even though the Java-level relationship is gone.
     * {@code insertable = false, updatable = false} means it never participates in a write —
     * {@link #tenantId} above is what {@code OrderDao.insert}/queries actually use — and it
     * has deliberately no getter or setter. Every entity in this codebase uses field access
     * (the {@code @Id} is placed on the field), so Hibernate never needs an accessor either;
     * with none exposed, nothing outside this file can reach it. If you find yourself wanting
     * to add one, that's a sign a `TenantDao` method is missing instead.
     */
    @SuppressWarnings("unused")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "tenant_id",
            insertable = false,
            updatable = false,
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_pos_order_tenant")
    )
    private TenantPojo tenantFk;

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
     *
     * <p>The user's id, not the entity — same peer-review Phase 2 decision as
     * {@link #tenantId}. See {@link #cashierFk}'s Javadoc for the mechanism.
     */
    @Column(name = "cashier_id", nullable = false)
    private Long cashierId;

    /** <b>DDL-only shadow association — never navigate this.</b> See {@link #tenantFk}. */
    @SuppressWarnings("unused")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "cashier_id",
            insertable = false,
            updatable = false,
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_pos_order_cashier")
    )
    private AppUserPojo cashierFk;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

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

    public Long getCashierId() {
        return cashierId;
    }

    public void setCashierId(Long cashierId) {
        this.cashierId = cashierId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}

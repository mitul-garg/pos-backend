package com.pos.pojo;

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
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A refund against a completed order. The table is {@code sales_return} because
 * {@code return} is a SQL reserved word.
 *
 * <p>Partial and repeated returns are allowed; the sum across returns for a line can
 * never exceed what was purchased, which C7 enforces by reading back the already-returned
 * quantities.
 *
 * <p><b>A return inherits its tenant from the original order</b> rather than re-reading
 * the session — that is the rule, and it is how the frontend expresses it too.
 *
 * <p><b>Its lines are not a navigable collection.</b> Peer-review Phase 2 dropped the old
 * {@code @OneToMany(mappedBy = "salesReturn", cascade = ALL, orphanRemoval = true) lines}
 * field along with every other {@code @ManyToOne} on this entity — see {@link #tenantFk}'s
 * Javadoc for the mechanism. {@code ReturnLineDao} is now how a caller reads or inserts
 * this return's lines. Simpler than {@code PosOrderPojo}'s equivalent change in one
 * respect: a return is insert-only once created (requirements.md §3/§7), so there is no
 * rebuild/delete path to replace, only the one-time cascade insert
 * {@code ReturnService.create} used to do through {@code salesReturn.addLine(line)}.
 */
@Entity
@Filter(name = TenantContext.FILTER_NAME, condition = TenantContext.CONDITION)
@Table(
        name = "sales_return",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_return_tenant_number",
                columnNames = { "tenant_id", "return_number" }
        ),
        indexes = {
                @Index(name = "idx_return_tenant_processor", columnList = "tenant_id, processed_by"),
                @Index(name = "idx_return_order", columnList = "original_order_id"),
                // Peer-review Phase 1: ReturnDao.list() sorts ORDER BY created_at DESC for
                // every GET /api/returns call, the identical query shape idx_order_tenant_created
                // exists on PosOrderPojo for. idx_return_tenant_processor's leftmost prefix covers a
                // processedBy-scoped read but not the ADMIN "all returns" case, which fell back to
                // a filesort on created_at.
                @Index(name = "idx_return_tenant_created", columnList = "tenant_id, created_at")
        }
)
public class SalesReturnPojo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * Always equals the original order's tenant. The tenant's id, not the entity —
     * peer-review Phase 2 decision: minimize {@code @ManyToOne} navigation, keep the
     * DB-level FK constraint. Read {@link #tenantFk}'s Javadoc for the mechanism.
     */
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    /**
     * <b>DDL-only shadow association — never navigate this.</b> Exists purely so Hibernate's
     * schema generation still emits {@code fk_sales_return_tenant}, the real
     * database-enforced constraint this project keeps even though the Java-level
     * relationship is gone. {@code insertable = false, updatable = false} means it never
     * participates in a write — {@link #tenantId} above is what {@code ReturnDao.insert}/
     * queries actually use — and it has deliberately no getter or setter. Every entity in
     * this codebase uses field access (the {@code @Id} is placed on the field), so
     * Hibernate never needs an accessor either; with none exposed, nothing outside this
     * file can reach it. If you find yourself wanting to add one, that's a sign a
     * {@code TenantDao} method is missing instead.
     */
    @SuppressWarnings("unused")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "tenant_id",
            insertable = false,
            updatable = false,
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_sales_return_tenant")
    )
    private TenantPojo tenantFk;

    /** {@code RET-YYYY-NNNN}, unique per tenant, minted from {@link TenantSequencePojo}. */
    @Column(name = "return_number", nullable = false, length = 32)
    private String returnNumber;

    /**
     * The original order's id, not the entity — same peer-review Phase 2 decision as
     * {@link #tenantId}. See {@link #originalOrderFk}'s Javadoc for the mechanism.
     */
    @Column(name = "original_order_id", nullable = false)
    private Long originalOrderId;

    /** <b>DDL-only shadow association — never navigate this.</b> See {@link #tenantFk}. */
    @SuppressWarnings("unused")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "original_order_id",
            insertable = false,
            updatable = false,
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_sales_return_pos_order")
    )
    private PosOrderPojo originalOrderFk;

    /** <b>Snapshot</b>, so a credit note prints standalone without loading the order. */
    @Column(name = "original_order_number", nullable = false, length = 32)
    private String originalOrderNumber;

    @Column(name = "refund_subtotal", nullable = false, precision = 12, scale = 2)
    private BigDecimal refundSubtotal;

    @Column(name = "refund_tax", nullable = false, precision = 12, scale = 2)
    private BigDecimal refundTax;

    @Column(name = "round_off", nullable = false, precision = 12, scale = 2)
    private BigDecimal roundOff = BigDecimal.ZERO;

    @Column(name = "refund_total", nullable = false, precision = 12, scale = 2)
    private BigDecimal refundTotal;

    /** Defaults to the original payment method. */
    // VARCHAR, not MySQL's native ENUM: adding a value to a MySQL ENUM is a table
    // alter, and hbm2ddl.auto=update will not perform it. Hibernate 6 defaults to the
    // native type on MySQL, so this has to be said explicitly on every enum field.
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "refund_method", nullable = false, length = 16)
    private PaymentMethod refundMethod;

    @Column(name = "reason", length = 500)
    private String reason;

    /**
     * Who processed it. <b>From the JWT subject, never the request body</b> — as with the
     * cashier on an order. The user's id, not the entity — same peer-review Phase 2
     * decision as {@link #tenantId}. See {@link #processedByFk}'s Javadoc for the mechanism.
     */
    @Column(name = "processed_by", nullable = false)
    private Long processedById;

    /** <b>DDL-only shadow association — never navigate this.</b> See {@link #tenantFk}. */
    @SuppressWarnings("unused")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "processed_by",
            insertable = false,
            updatable = false,
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_sales_return_processed_by")
    )
    private AppUserPojo processedByFk;

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

    public String getReturnNumber() {
        return returnNumber;
    }

    public void setReturnNumber(String returnNumber) {
        this.returnNumber = returnNumber;
    }

    public Long getOriginalOrderId() {
        return originalOrderId;
    }

    public void setOriginalOrderId(Long originalOrderId) {
        this.originalOrderId = originalOrderId;
    }

    public String getOriginalOrderNumber() {
        return originalOrderNumber;
    }

    public void setOriginalOrderNumber(String originalOrderNumber) {
        this.originalOrderNumber = originalOrderNumber;
    }

    public BigDecimal getRefundSubtotal() {
        return refundSubtotal;
    }

    public void setRefundSubtotal(BigDecimal refundSubtotal) {
        this.refundSubtotal = refundSubtotal;
    }

    public BigDecimal getRefundTax() {
        return refundTax;
    }

    public void setRefundTax(BigDecimal refundTax) {
        this.refundTax = refundTax;
    }

    public BigDecimal getRoundOff() {
        return roundOff;
    }

    public void setRoundOff(BigDecimal roundOff) {
        this.roundOff = roundOff;
    }

    public BigDecimal getRefundTotal() {
        return refundTotal;
    }

    public void setRefundTotal(BigDecimal refundTotal) {
        this.refundTotal = refundTotal;
    }

    public PaymentMethod getRefundMethod() {
        return refundMethod;
    }

    public void setRefundMethod(PaymentMethod refundMethod) {
        this.refundMethod = refundMethod;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Long getProcessedById() {
        return processedById;
    }

    public void setProcessedById(Long processedById) {
        this.processedById = processedById;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}

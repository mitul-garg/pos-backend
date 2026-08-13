package com.pos.pojo;

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

    /** Always equals the original order's tenant. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "tenant_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_sales_return_tenant")
    )
    private TenantPojo tenant;

    /** {@code RET-YYYY-NNNN}, unique per tenant, minted from {@link TenantSequencePojo}. */
    @Column(name = "return_number", nullable = false, length = 32)
    private String returnNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "original_order_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_sales_return_pos_order")
    )
    private PosOrderPojo originalOrder;

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

    /** <b>From the JWT subject, never the request body</b> — as with the cashier. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "processed_by",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_sales_return_processed_by")
    )
    private AppUserPojo processedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "salesReturn", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ReturnLinePojo> lines = new ArrayList<>();

    /** Keeps both sides of the association consistent, which cascading relies on. */
    public void addLine(ReturnLinePojo line) {
        lines.add(line);
        line.setSalesReturn(this);
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

    public String getReturnNumber() {
        return returnNumber;
    }

    public void setReturnNumber(String returnNumber) {
        this.returnNumber = returnNumber;
    }

    public PosOrderPojo getOriginalOrder() {
        return originalOrder;
    }

    public void setOriginalOrder(PosOrderPojo originalOrder) {
        this.originalOrder = originalOrder;
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

    public AppUserPojo getProcessedBy() {
        return processedBy;
    }

    public void setProcessedBy(AppUserPojo processedBy) {
        this.processedBy = processedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public List<ReturnLinePojo> getLines() {
        return lines;
    }

    public void setLines(List<ReturnLinePojo> lines) {
        this.lines = lines;
    }
}

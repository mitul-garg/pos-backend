package com.pos.pojo;

import com.pos.pojo.enums.SequenceKind;
import com.pos.util.TenantContext;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Filter;

/**
 * A per-tenant counter. <b>The one table here with no business meaning.</b>
 *
 * <p>It exists because going multi-tenant meant giving up {@code AUTO_INCREMENT} for
 * order numbers, return numbers and QR sequences — a concurrency-safe generator the
 * database gave us for free — and replacing it with something we have to make safe
 * ourselves.
 *
 * <p><b>How to use it correctly (C6/C7):</b> read the row with
 * {@code SELECT ... FOR UPDATE} <i>in the same transaction as the insert it numbers</i>.
 * {@code MAX(number)+1} races, and so does a plain read-then-write: MySQL's default
 * REPEATABLE READ does not help, because a plain {@code SELECT} is a non-locking
 * snapshot read, so two transactions both seeing the same value is legal behaviour
 * rather than misconfiguration. The {@code UNIQUE(tenant_id, order_number)} key is the
 * backstop if that logic is ever wrong.
 *
 * <p>A tenant with no row for a {@code kind} starts at 1, matching the frontend, where a
 * newly created tenant simply has no counter entry yet.
 *
 * <p>No surrogate {@code id}: the primary key <i>is</i> {@code (tenant_id, kind)}, which
 * is also what makes "one counter per tenant per kind" a schema guarantee rather than a
 * convention.
 */
@Entity
@Filter(name = TenantContext.FILTER_NAME, condition = TenantContext.CONDITION)
@Table(name = "tenant_sequence")
public class TenantSequencePojo {

    @EmbeddedId
    private TenantSequenceIdPojo id;

    /**
     * {@code @MapsId} ties this association to the {@code tenantId} half of the key, so
     * one {@code tenant_id} column serves as both primary-key component and foreign key
     * rather than being mapped twice.
     */
    @MapsId("tenantId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "tenant_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_tenant_sequence_tenant")
    )
    private TenantPojo tenant;

    /** The next value to hand out — so a fresh counter starts at 1, not 0. */
    @Column(name = "next_value", nullable = false)
    @ColumnDefault("1")
    private long nextValue = 1L;

    protected TenantSequencePojo() {
        // Required by JPA.
    }

    public TenantSequencePojo(TenantPojo tenant, SequenceKind kind) {
        this.tenant = tenant;
        this.id = new TenantSequenceIdPojo(tenant.getId(), kind);
    }

    public TenantSequenceIdPojo getId() {
        return id;
    }

    public void setId(TenantSequenceIdPojo id) {
        this.id = id;
    }

    public TenantPojo getTenant() {
        return tenant;
    }

    public void setTenant(TenantPojo tenant) {
        this.tenant = tenant;
    }

    public long getNextValue() {
        return nextValue;
    }

    public void setNextValue(long nextValue) {
        this.nextValue = nextValue;
    }
}

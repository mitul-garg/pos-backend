package com.pos.pojo;

import java.io.Serializable;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The composite primary key of {@link TenantSequence}: {@code (tenant_id, kind)}.
 *
 * <p>{@code equals} and {@code hashCode} are mandatory here, not optional politeness —
 * JPA uses them to identify the entity in the persistence context, and a composite key
 * without them silently produces duplicate managed instances.
 */
@Embeddable
public class TenantSequenceId implements Serializable {

    private static final long serialVersionUID = 1L;

    @Column(name = "tenant_id")
    private Long tenantId;

    // VARCHAR, not MySQL's native ENUM: adding a value to a MySQL ENUM is a table
    // alter, and hbm2ddl.auto=update will not perform it. Hibernate 6 defaults to the
    // native type on MySQL, so this has to be said explicitly on every enum field.
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "kind", length = 16)
    private SequenceKind kind;

    protected TenantSequenceId() {
        // Required by JPA.
    }

    public TenantSequenceId(Long tenantId, SequenceKind kind) {
        this.tenantId = tenantId;
        this.kind = kind;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public SequenceKind getKind() {
        return kind;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TenantSequenceId that)) {
            return false;
        }
        return Objects.equals(tenantId, that.tenantId) && kind == that.kind;
    }

    @Override
    public int hashCode() {
        return Objects.hash(tenantId, kind);
    }
}

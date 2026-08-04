package com.pos.pojo;

import java.io.Serializable;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

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

    @Enumerated(EnumType.STRING)
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

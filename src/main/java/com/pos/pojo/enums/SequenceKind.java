package com.pos.pojo.enums;

/**
 * Which per-tenant counter a {@link TenantSequencePojo} row holds.
 *
 * <p>These exist because going multi-tenant meant giving up {@code AUTO_INCREMENT} for
 * these three numbers — a concurrency-safe generator the database gave us free — and
 * replacing it with something we have to make safe ourselves (backend-plan.md section 4).
 */
public enum SequenceKind {

    /** {@code ORD-YYYY-NNNN} on {@link PosOrderPojo}. */
    ORDER,

    /** {@code RET-YYYY-NNNN} on {@link SalesReturnPojo}. */
    RETURN,

    /** The numeric tail of a {@link VariantPojo}'s {@code POS-QR-{tenantId}-{seq}} payload. */
    QR
}

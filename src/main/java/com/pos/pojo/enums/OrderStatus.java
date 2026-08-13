package com.pos.pojo.enums;

/**
 * Order lifecycle. One record per real-world sale: resuming a {@code HELD} or
 * {@code DRAFT} order continues the same record rather than spawning a duplicate, and
 * clearing a resumed cart {@code CANCELLED}s it (requirements.md section 5).
 *
 * <p>A {@code COMPLETED} order is immutable.
 */
public enum OrderStatus {
    DRAFT,
    HELD,
    COMPLETED,
    CANCELLED
}

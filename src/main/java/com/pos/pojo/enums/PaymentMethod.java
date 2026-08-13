package com.pos.pojo.enums;

/**
 * How an order was paid, and by default how a return is refunded. v1 is a single
 * payment covering the full amount — no split tender (requirements.md section 3), which
 * is why payment is embedded in {@link PosOrderPojo} rather than being a child table.
 */
public enum PaymentMethod {
    CASH,
    CARD,
    UPI
}

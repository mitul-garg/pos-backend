package com.pos.model;

import java.math.BigDecimal;

import com.pos.pojo.enums.PaymentMethod;

/**
 * The {@code payment} object nested in {@link OrderData} — {@code null} until the order is
 * paid, exactly like the mock's {@code order.payment} (C6).
 *
 * <p>{@code amount} always equals the order's own {@code grandTotal}: v1 is a single
 * payment covering the full amount, no split tender (requirements.md section 3), which is
 * also why this is embedded rather than a child table — see {@code PosOrderPojo}'s Javadoc.
 */
public class PaymentData {

    private final PaymentMethod method;
    private final BigDecimal amount;
    private final BigDecimal amountTendered;
    private final BigDecimal change;
    private final String reference;

    public PaymentData(PaymentMethod method, BigDecimal amount, BigDecimal amountTendered,
                       BigDecimal change, String reference) {
        this.method = method;
        this.amount = amount;
        this.amountTendered = amountTendered;
        this.change = change;
        this.reference = reference;
    }

    public PaymentMethod getMethod() {
        return method;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public BigDecimal getAmountTendered() {
        return amountTendered;
    }

    public BigDecimal getChange() {
        return change;
    }

    public String getReference() {
        return reference;
    }
}

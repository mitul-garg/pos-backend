package com.pos.service;

import java.math.BigDecimal;

import com.pos.dao.OrderDao;
import com.pos.dao.OrderLineDao;
import com.pos.dao.VariantDao;
import com.pos.exception.NotFoundException;
import com.pos.exception.ValidationException;
import com.pos.model.OrderData;
import com.pos.model.PaymentForm;
import com.pos.pojo.OrderLinePojo;
import com.pos.pojo.enums.OrderStatus;
import com.pos.pojo.enums.PaymentMethod;
import com.pos.pojo.PosOrderPojo;
import com.pos.util.MaxLength;
import com.pos.util.Pricing;
import com.pos.util.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code POST /api/orders/{id}/payments} (C6) — the port of
 * {@code frontend/src/services/paymentService.js}, plus the hard stock check the mock had
 * no inventory ledger to enforce.
 *
 * <p><b>This is where inventory actually moves.</b> Nothing before this point reserves
 * stock — holding a cart, or even creating a {@code DRAFT} order, commits nothing.
 * {@link #pay} is the one moment stock is authoritative and decremented, which is also
 * why the hard availability check the frontend explicitly deferred (backend-plan.md
 * section 1, obligation 1) lives here rather than in {@code OrderService.create}.
 */
@Service
public class PaymentService {

    /** Matches the mock's {@code 'Order not found'}. */
    static final String NOT_FOUND = OrderService.NOT_FOUND;

    static final String ALREADY_PAID = "Order is already paid";
    static final String METHOD_REQUIRED = "Choose a payment method";
    static final String AMOUNT_TENDERED_SHORT = "Amount tendered is less than the total due";

    private final OrderDao orderDao;
    private final OrderLineDao orderLineDao;
    private final VariantDao variantDao;
    private final OrderService orderService;

    @Autowired
    public PaymentService(OrderDao orderDao, OrderLineDao orderLineDao, VariantDao variantDao,
                          OrderService orderService) {
        this.orderDao = orderDao;
        this.orderLineDao = orderLineDao;
        this.variantDao = variantDao;
        this.orderService = orderService;
    }

    /**
     * Single payment, full amount, no split tender (requirements.md section 3). On
     * success the order flips {@code COMPLETED} and stock is decremented line by line —
     * atomically, and inside this same transaction, so a rejection on any line rolls
     * every earlier decrement in this call back with it.
     */
    @Transactional
    public OrderData pay(Long orderId, PaymentForm form) {
        TenantContext.requireTenant();

        PosOrderPojo order = orderDao.find(orderId);
        if (order == null) {
            throw new NotFoundException(NOT_FOUND);
        }
        if (order.getStatus() == OrderStatus.COMPLETED) {
            throw new ValidationException(ALREADY_PAID);
        }
        if (form.getMethod() == null) {
            throw ValidationException.field("method", METHOD_REQUIRED);
        }
        // Peer-review Phase 1: pos_order.payment_reference is VARCHAR(64); the mock had
        // no schema to bound it against.
        MaxLength.require("reference", "Reference", form.getReference(), 64);

        boolean isCash = form.getMethod() == PaymentMethod.CASH;
        BigDecimal grandTotal = order.getGrandTotal();
        BigDecimal amountTendered = form.getAmountTendered();
        if (isCash) {
            BigDecimal tendered = amountTendered == null ? BigDecimal.ZERO : amountTendered;
            if (tendered.compareTo(grandTotal) < 0) {
                throw ValidationException.field("amountTendered", AMOUNT_TENDERED_SHORT);
            }
        }

        decrementStock(order);

        order.setPaymentMethod(form.getMethod());
        order.setPaymentAmount(grandTotal);
        order.setAmountTendered(isCash ? amountTendered : grandTotal);
        order.setChangeDue(isCash ? Pricing.computeChange(amountTendered, grandTotal) : BigDecimal.ZERO.setScale(2));
        order.setPaymentReference(referenceFor(form, order, isCash));
        order.setStatus(OrderStatus.COMPLETED);

        return orderService.toData(order);
    }

    /**
     * The hard check (backend-plan.md section 4, item 2). One atomic conditional update
     * per line, and zero affected rows <b>is</b> the rejection — not a read, a check and
     * a write with a gap for a second terminal to land in.
     *
     * <p>Each {@code variantId} here was already resolved through the tenant filter when
     * the line was created ({@code OrderService.resolveVariant}), so decrementing by that
     * id needs no further tenant check — see {@code VariantDao.decrementStock}.
     *
     * <p>Throwing mid-loop is deliberate and sufficient: this method runs inside
     * {@link #pay}'s transaction, so an exception here rolls back every decrement already
     * applied in this call, not just the one that failed.
     */
    private void decrementStock(PosOrderPojo order) {
        for (OrderLinePojo line : orderLineDao.findByOrder(order.getId())) {
            boolean ok = variantDao.decrementStock(line.getVariantId(), line.getQuantity());
            if (!ok) {
                throw ValidationException.field("items",
                        "Insufficient stock for " + line.getName());
            }
        }
    }

    /**
     * Cash carries whatever reference the operator typed, or none. Card/UPI get a dummy
     * transaction id when the caller supplies none — {@code MOCK-<METHOD>-<orderNumber>},
     * verbatim from the mock, since neither is wired to a real payment gateway.
     */
    private String referenceFor(PaymentForm form, PosOrderPojo order, boolean isCash) {
        if (form.getReference() != null && !form.getReference().isBlank()) {
            return form.getReference();
        }
        return isCash ? null : "MOCK-" + form.getMethod() + "-" + order.getOrderNumber();
    }
}

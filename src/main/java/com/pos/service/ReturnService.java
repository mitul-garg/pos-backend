package com.pos.service;

import java.math.BigDecimal;
import java.time.Year;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.pos.dao.AppUserDao;
import com.pos.dao.OrderDao;
import com.pos.dao.ReturnDao;
import com.pos.dao.TenantSequenceDao;
import com.pos.dao.VariantDao;
import com.pos.exception.NotFoundException;
import com.pos.exception.ValidationException;
import com.pos.model.OrderLookupData;
import com.pos.model.OrderLookupLineData;
import com.pos.model.PageData;
import com.pos.model.PaymentData;
import com.pos.model.ReturnData;
import com.pos.model.ReturnForm;
import com.pos.model.ReturnLineData;
import com.pos.model.ReturnLineForm;
import com.pos.model.SessionUserData;
import com.pos.pojo.OrderLinePojo;
import com.pos.pojo.enums.OrderStatus;
import com.pos.pojo.PosOrderPojo;
import com.pos.pojo.ReturnLinePojo;
import com.pos.pojo.enums.Role;
import com.pos.pojo.SalesReturnPojo;
import com.pos.pojo.enums.SequenceKind;
import com.pos.pojo.TenantPojo;
import com.pos.util.MaxLength;
import com.pos.util.Pricing;
import com.pos.util.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code GET /api/orders/lookup}, order/return history's other half — the port of
 * {@code frontend/src/services/returnService.js} (C7).
 *
 * <p><b>Refund math reuses {@link Pricing#computeOrderTotals}</b> against the
 * <i>original order line's</i> snapshotted {@code unitPrice} and {@code taxRatePercent},
 * never the variant's current row — a return refunds what was actually charged, exactly
 * as {@code OrderService} recomputes a sale from the variant's current row rather than
 * the request. There is no order-level discount on a refund, so every call passes
 * {@link BigDecimal#ZERO}.
 *
 * <p>Every method is {@code @Transactional}, read-only ones included, for the standing
 * reason: the tenant filter lives on a Hibernate session, and a read outside a
 * transaction is an unscoped read.
 */
@Service
public class ReturnService {

    /** Matches the mock's {@code 'Return not found'}. */
    static final String NOT_FOUND = "Return not found";

    /** Reused rather than duplicated — the same message a missing order gets elsewhere. */
    static final String ORDER_NOT_FOUND = OrderService.NOT_FOUND;

    /**
     * <b>A deliberate split from the mock</b>, which throws one message for "no such
     * order" and "order not completed" alike. This application already tells the two
     * apart everywhere else a resource exists but is in the wrong state — an unpaid
     * order 404s, a wrong-state one 400s (see {@code PaymentService.ALREADY_PAID}) — so
     * a genuinely missing or cross-tenant order stays a 404 and this is the 400 for one
     * that exists but was never completed.
     */
    static final String ORDER_NOT_COMPLETED = "Only a completed order can be returned";

    static final String ITEMS_REQUIRED = "Select at least one item to return";
    static final String VARIANT_REQUIRED = "Variant is required";
    static final String ITEM_NOT_ON_ORDER = "Item is not part of the original order";
    static final String ORIGINAL_ORDER_REQUIRED = "Original order is required";

    private final OrderDao orderDao;
    private final ReturnDao returnDao;
    private final VariantDao variantDao;
    private final AppUserDao appUserDao;
    private final TenantSequenceDao tenantSequenceDao;
    private final AuthService authService;

    @Autowired
    public ReturnService(OrderDao orderDao, ReturnDao returnDao, VariantDao variantDao,
                         AppUserDao appUserDao, TenantSequenceDao tenantSequenceDao,
                         AuthService authService) {
        this.orderDao = orderDao;
        this.returnDao = returnDao;
        this.variantDao = variantDao;
        this.appUserDao = appUserDao;
        this.tenantSequenceDao = tenantSequenceDao;
        this.authService = authService;
    }

    /**
     * {@code GET /api/orders/lookup?orderNumber=}. Fetches a completed order with each
     * line's returnable quantity folded in — purchased minus already returned, read
     * fresh on every call rather than trusted from anywhere the client might have cached
     * it.
     */
    @Transactional(readOnly = true)
    public OrderLookupData lookupOrder(String orderNumber) {
        TenantContext.requireTenant();
        String trimmed = orderNumber == null ? "" : orderNumber.trim();
        PosOrderPojo order = trimmed.isEmpty() ? null : orderDao.findByOrderNumber(trimmed);
        if (order == null) {
            throw new NotFoundException(ORDER_NOT_FOUND);
        }
        if (order.getStatus() != OrderStatus.COMPLETED) {
            throw new ValidationException(ORDER_NOT_COMPLETED);
        }
        return toLookupData(order);
    }

    /**
     * {@code GET /api/returns/{id}}.
     */
    @Transactional(readOnly = true)
    public ReturnData get(Long id) {
        TenantContext.requireTenant();
        SalesReturnPojo salesReturn = returnDao.find(id);
        if (salesReturn == null) {
            throw new NotFoundException(NOT_FOUND);
        }
        return toData(salesReturn);
    }

    /**
     * {@code GET /api/returns?processedBy=&page=&pageSize=}.
     *
     * <p><b>A {@code CASHIER}'s {@code processedBy} is forced to their own id</b>, the
     * identical override {@code OrderService.list} applies to {@code cashierId} and for
     * the identical reason: requirements.md section 9 states the parameter is "a query,
     * not an identity claim" applies "identically to {@code GET /api/returns?processedBy=}
     * in C7" to the same RBAC promise section 5 makes for orders. An {@code ADMIN}'s
     * value passes through unchanged.
     */
    @Transactional(readOnly = true)
    public PageData<ReturnData> list(Long processedBy, int page, int pageSize) {
        TenantContext.requireTenant();
        SessionUserData session = authService.currentSession();
        Long effectiveProcessedBy = session.getRole() == Role.CASHIER ? session.getId() : processedBy;

        int safePage = Math.max(page, 1);
        int safePageSize = Math.min(Math.max(pageSize, 1), 200);

        long total = returnDao.count(effectiveProcessedBy);
        List<SalesReturnPojo> returns =
                returnDao.list(effectiveProcessedBy, (safePage - 1) * safePageSize, safePageSize);
        // Peer-review Phase 1: one batched query for the whole page's lines, instead of
        // each row's lazy `lines` firing its own SELECT (see ReturnDao.findLinesByReturnIds).
        Map<Long, List<ReturnLinePojo>> linesByReturn = returnDao.findLinesByReturnIds(
                returns.stream().map(SalesReturnPojo::getId).toList());
        List<ReturnData> items = returns.stream()
                .map(r -> toData(r, linesByReturn.getOrDefault(r.getId(), List.of())))
                .toList();
        return new PageData<>(items, total, safePage, safePageSize);
    }

    /**
     * {@code POST /api/returns}. Tenant and {@code processedBy} both come from the
     * session, never the body — {@link ReturnForm} has no field for either.
     *
     * <p><b>The original order is locked for the rest of this transaction</b>
     * ({@code OrderDao.findForUpdate}) before its already-returned quantities are read,
     * closing the same race a bare read-then-write would leave open: two returns against
     * the same order, submitted at the same moment, must not both see the same
     * unreturned baseline and together refund more than was purchased.
     *
     * <p>The return number is minted from this store's own sequence in the same
     * transaction as the insert — identical machinery to {@code OrderService.create},
     * just {@link SequenceKind#RETURN} instead of {@link SequenceKind#ORDER}.
     */
    @Transactional
    public ReturnData create(ReturnForm form) {
        TenantContext.requireTenant();
        SessionUserData session = authService.currentSession();

        if (form.getOriginalOrderId() == null) {
            throw ValidationException.field("originalOrderId", ORIGINAL_ORDER_REQUIRED);
        }
        // Peer-review Phase 1: sales_return.reason is VARCHAR(500); the mock had no
        // schema to bound it against.
        MaxLength.require("reason", "Reason", form.getReason(), 500);

        PosOrderPojo order = orderDao.findForUpdate(form.getOriginalOrderId());
        if (order == null) {
            throw new NotFoundException(ORDER_NOT_FOUND);
        }
        if (order.getStatus() != OrderStatus.COMPLETED) {
            throw new ValidationException(ORDER_NOT_COMPLETED);
        }

        List<ReturnLineForm> requested = form.getItems() == null ? List.of() : form.getItems();
        List<ReturnLineForm> selected = requested.stream()
                .filter(i -> i.getQuantity() != null && i.getQuantity() > 0)
                .toList();
        if (selected.isEmpty()) {
            throw ValidationException.field("items", ITEMS_REQUIRED);
        }

        Map<Long, Integer> alreadyReturned = returnDao.returnedQuantitiesByVariant(order.getId());
        // Consumed by an earlier line of THIS same request — without it, splitting one
        // returnable quantity across two request lines for the same variant would check
        // each against the identical baseline and let both through.
        Map<Long, Integer> consumedThisRequest = new HashMap<>();

        List<OrderLinePojo> matchedLines = new ArrayList<>(selected.size());
        List<Pricing.LineInput> inputs = new ArrayList<>(selected.size());
        for (ReturnLineForm itemForm : selected) {
            if (itemForm.getVariantId() == null) {
                throw ValidationException.field("variantId", VARIANT_REQUIRED);
            }
            OrderLinePojo line = order.getLines().stream()
                    .filter(l -> l.getVariant().getId().equals(itemForm.getVariantId()))
                    .findFirst()
                    .orElseThrow(() -> ValidationException.field("items", ITEM_NOT_ON_ORDER));

            int returned = alreadyReturned.getOrDefault(itemForm.getVariantId(), 0)
                    + consumedThisRequest.getOrDefault(itemForm.getVariantId(), 0);
            int returnable = line.getQuantity() - returned;
            int quantity = itemForm.getQuantity();
            if (quantity > returnable) {
                throw ValidationException.field("items",
                        "Cannot return more than " + returnable + " of \"" + line.getName() + "\"");
            }
            consumedThisRequest.merge(itemForm.getVariantId(), quantity, Integer::sum);

            matchedLines.add(line);
            inputs.add(new Pricing.LineInput(line.getVariant().getId(), line.getName(),
                    line.getQrCode(), quantity, line.getUnitPrice(), line.getTaxRatePercent(),
                    BigDecimal.ZERO));
        }

        Pricing.OrderTotals totals = Pricing.computeOrderTotals(inputs, BigDecimal.ZERO);

        SalesReturnPojo salesReturn = new SalesReturnPojo();
        salesReturn.setTenant(order.getTenant());
        salesReturn.setReturnNumber(nextReturnNumber(order.getTenant()));
        salesReturn.setOriginalOrder(order);
        salesReturn.setOriginalOrderNumber(order.getOrderNumber());
        salesReturn.setRefundSubtotal(totals.subtotal);
        salesReturn.setRefundTax(totals.totalTax);
        salesReturn.setRoundOff(totals.roundOff);
        salesReturn.setRefundTotal(totals.grandTotal);
        salesReturn.setRefundMethod(
                form.getRefundMethod() == null ? order.getPaymentMethod() : form.getRefundMethod());
        salesReturn.setReason(form.getReason());
        salesReturn.setProcessedBy(appUserDao.reference(session.getId()));

        for (int i = 0; i < totals.lines.size(); i++) {
            Pricing.LineTotals computed = totals.lines.get(i);
            OrderLinePojo sourceLine = matchedLines.get(i);

            ReturnLinePojo returnLine = new ReturnLinePojo();
            returnLine.setTenant(order.getTenant());
            returnLine.setVariant(sourceLine.getVariant());
            returnLine.setName(computed.input.name);
            returnLine.setQuantity(computed.input.quantity);
            returnLine.setUnitPrice(computed.input.unitPrice);
            returnLine.setTaxRatePercent(computed.input.taxRatePercent);
            returnLine.setLineRefund(computed.lineTotal);
            salesReturn.addLine(returnLine);

            // Each id here was already resolved through a filtered read when the ORIGINAL
            // order was created — see VariantDao.restoreStock's Javadoc for why that
            // makes this bulk statement safe without a tenant check of its own.
            variantDao.restoreStock(sourceLine.getVariant().getId(), computed.input.quantity);
        }

        returnDao.insert(salesReturn);
        return toData(salesReturn);
    }

    /** This store's next return number, identical shape to {@code OrderService}'s. */
    private String nextReturnNumber(TenantPojo tenant) {
        long sequence = tenantSequenceDao.next(SequenceKind.RETURN, tenant);
        return String.format("RET-%d-%04d", Year.now().getValue(), sequence);
    }

    private OrderLookupData toLookupData(PosOrderPojo order) {
        Map<Long, Integer> returned = returnDao.returnedQuantitiesByVariant(order.getId());
        List<OrderLookupLineData> items = order.getLines().stream()
                .map(line -> toLookupLine(line, returned.getOrDefault(line.getVariant().getId(), 0)))
                .toList();

        PaymentData payment = order.getPaymentMethod() == null ? null : new PaymentData(
                order.getPaymentMethod(), order.getPaymentAmount(), order.getAmountTendered(),
                order.getChangeDue(), order.getPaymentReference());

        return new OrderLookupData(
                order.getId(),
                order.getTenant().getId(),
                order.getOrderNumber(),
                items,
                order.getSubtotal(),
                order.getTotalTax(),
                order.getOrderDiscount(),
                order.getRoundOff(),
                order.getGrandTotal(),
                order.getStatus(),
                payment,
                order.getCashier().getId(),
                order.getCreatedAt());
    }

    private OrderLookupLineData toLookupLine(OrderLinePojo line, int returnedQuantity) {
        int returnable = Math.max(0, line.getQuantity() - returnedQuantity);
        return new OrderLookupLineData(
                line.getVariant().getId(),
                line.getName(),
                line.getQrCode(),
                line.getQuantity(),
                line.getUnitPrice(),
                line.getTaxRatePercent(),
                line.getLineDiscount(),
                line.getLineTotal(),
                returnedQuantity,
                returnable);
    }

    /**
     * Mapped inside the transaction — {@code getLines()} and {@code processedBy} are LAZY.
     * Used by {@link #get} and {@link #create}, where reading the lazy collection directly
     * is one extra query for one return, not a per-row problem.
     */
    private ReturnData toData(SalesReturnPojo salesReturn) {
        return toData(salesReturn, salesReturn.getLines());
    }

    /**
     * The core mapper, taking {@code lines} explicitly rather than reading
     * {@code salesReturn.getLines()} itself — {@link #list} batches every return's
     * lines on the page into one query ({@code ReturnDao.findLinesByReturnIds},
     * peer-review Phase 1) rather than letting each row's lazy association fire its own
     * {@code SELECT}, and passes the already-loaded lines in here instead.
     */
    private ReturnData toData(SalesReturnPojo salesReturn, List<ReturnLinePojo> lines) {
        List<ReturnLineData> items = lines.stream()
                .map(l -> new ReturnLineData(l.getVariant().getId(), l.getName(), l.getQuantity(),
                        l.getUnitPrice(), l.getTaxRatePercent(), l.getLineRefund()))
                .toList();

        return new ReturnData(
                salesReturn.getId(),
                salesReturn.getTenant().getId(),
                salesReturn.getReturnNumber(),
                salesReturn.getOriginalOrder().getId(),
                salesReturn.getOriginalOrderNumber(),
                items,
                salesReturn.getRefundSubtotal(),
                salesReturn.getRefundTax(),
                salesReturn.getRoundOff(),
                salesReturn.getRefundTotal(),
                salesReturn.getRefundMethod(),
                salesReturn.getReason(),
                salesReturn.getProcessedBy().getId(),
                salesReturn.getCreatedAt());
    }
}

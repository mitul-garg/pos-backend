package com.pos.service;

import java.math.BigDecimal;
import java.time.Year;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.pos.config.AppProperties;
import com.pos.dao.OrderDao;
import com.pos.dao.OrderLineDao;
import com.pos.dao.TenantSequenceDao;
import com.pos.dao.VariantDao;
import com.pos.exception.NotFoundException;
import com.pos.exception.ValidationException;
import com.pos.model.OrderData;
import com.pos.model.OrderForm;
import com.pos.model.OrderLineData;
import com.pos.model.OrderLineForm;
import com.pos.model.PageData;
import com.pos.model.PaymentData;
import com.pos.model.SessionUserData;
import com.pos.pojo.OrderLinePojo;
import com.pos.pojo.enums.OrderStatus;
import com.pos.pojo.PosOrderPojo;
import com.pos.pojo.ProductPojo;
import com.pos.pojo.enums.Role;
import com.pos.pojo.enums.SequenceKind;
import com.pos.pojo.VariantPojo;
import com.pos.util.Pricing;
import com.pos.util.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Order create/list/get/update — the hold/resume/cancel lifecycle up to (but not
 * including) payment, which is {@link PaymentService} (C6). The port of
 * {@code frontend/src/services/orderService.js}.
 *
 * <p><b>Every amount is recomputed here, never trusted from the request.</b>
 * {@link OrderLineForm} carries only {@code variantId}, {@code quantity} and
 * {@code lineDiscount} — no price, no tax rate, no name — and {@link #resolveVariant}
 * re-derives all three of those from the variant's <i>current</i> row, read through the
 * tenant filter. A client that forges a price in the body has nothing to forge it into.
 *
 * <p>Every method is {@code @Transactional}, including the read-only ones, for the
 * standing reason: the tenant filter lives on a Hibernate session, and a read outside a
 * transaction is an unscoped read.
 */
@Service
public class OrderService {

    /** Matches the mock's {@code 'Order not found'}. */
    static final String NOT_FOUND = "Order not found";

    /** Verbatim from {@code orderService.update}. */
    static final String ALREADY_COMPLETED =
            "A completed order can no longer be modified";

    static final String QUANTITY_INVALID = "Quantity must be greater than 0";
    static final String VARIANT_REQUIRED = "Variant is required";

    /**
     * Peer-review Phase 0 resource-creation guardrail: a durable ceiling on cart
     * size, not a time-windowed rate limit — see {@link #buildLines}.
     */
    static final String TOO_MANY_LINE_ITEMS = "Order has too many line items";

    /**
     * <b>Not in the mock</b> — the mock's {@code create} accepts whatever {@code status}
     * string is handed to it. A real client only ever sends {@code DRAFT} (the default,
     * on payment's first save) or {@code HELD} (Checkout's "Hold" button); a request
     * naming anything else is either a bug or an attempt to synthesize a
     * {@code COMPLETED} order without paying, or a {@code CANCELLED} one without going
     * through {@link #update}. Both of those stay reachable only their own way.
     */
    static final String CREATE_STATUS_INVALID = "New orders must be DRAFT or HELD";

    /**
     * <b>Also not in the mock</b>, for the identical reason. A {@code PATCH} transitions
     * a held sale, in either direction between held and abandoned — {@code DRAFT} is
     * where an order starts and never returns to, and {@code COMPLETED} is
     * {@link PaymentService}'s alone to set.
     */
    static final String PATCH_STATUS_INVALID = "Status can only be changed to HELD or CANCELLED";

    private final OrderDao orderDao;
    private final OrderLineDao orderLineDao;
    private final VariantDao variantDao;
    private final TenantSequenceDao tenantSequenceDao;
    private final AuthService authService;
    private final AppProperties appProperties;

    @Autowired
    public OrderService(OrderDao orderDao, OrderLineDao orderLineDao, VariantDao variantDao,
                        TenantSequenceDao tenantSequenceDao, AuthService authService,
                        AppProperties appProperties) {
        this.orderDao = orderDao;
        this.orderLineDao = orderLineDao;
        this.variantDao = variantDao;
        this.tenantSequenceDao = tenantSequenceDao;
        this.authService = authService;
        this.appProperties = appProperties;
    }

    /**
     * {@code GET /api/orders?status=&cashierId=&page=&pageSize=}.
     *
     * <p><b>A {@code CASHIER}'s {@code cashierId} is not a request parameter here — it is
     * forced to their own id, whatever the caller supplied.</b> Requirements.md section 5
     * promises "a CASHIER sees only their own orders", stated as an RBAC rule, not merely
     * a UI default the way the query parameter's own description
     * ("{@code cashierId} remains a legitimate filter argument, not an identity claim",
     * requirements.md section 9) reads in isolation. Read literally, that line would let
     * any cashier page through a colleague's sales by id. An {@code ADMIN}'s value passes
     * through unchanged — {@code null} for "all", or a specific operator's id to filter
     * the same list a cashier would see.
     */
    @Transactional(readOnly = true)
    public PageData<OrderData> list(OrderStatus status, Long cashierId, int page, int pageSize) {
        TenantContext.requireTenant();
        SessionUserData session = authService.currentSession();
        Long effectiveCashierId = session.getRole() == Role.CASHIER ? session.getId() : cashierId;

        int safePage = Math.max(page, 1);
        int safePageSize = Math.min(Math.max(pageSize, 1), 200);

        long total = orderDao.count(status, effectiveCashierId);
        List<PosOrderPojo> orders = orderDao.list(
                status, effectiveCashierId, (safePage - 1) * safePageSize, safePageSize);
        // Peer-review Phase 1: one batched query for the whole page's lines, instead of
        // each row's lazy `lines` firing its own SELECT (see OrderLineDao.findByOrders).
        Map<Long, List<OrderLinePojo>> linesByOrder =
                orderLineDao.findByOrders(orders.stream().map(PosOrderPojo::getId).toList());
        List<OrderData> items = orders.stream()
                .map(o -> toData(o, linesByOrder.getOrDefault(o.getId(), List.of())))
                .toList();
        return new PageData<>(items, total, safePage, safePageSize);
    }

    /**
     * {@code GET /api/orders/{id}}. Unlike {@link #list}, not cashier-scoped — a receipt
     * reprint or a resume-by-id has to work regardless of who rang the sale up, the same
     * way {@code ProductService.get} is open to both roles. Only {@link #list}'s "my
     * orders" view narrows by actor; a direct id is either in this tenant or it 404s.
     */
    @Transactional(readOnly = true)
    public OrderData get(Long id) {
        TenantContext.requireTenant();
        PosOrderPojo order = orderDao.find(id);
        if (order == null) {
            throw new NotFoundException(NOT_FOUND);
        }
        return toData(order);
    }

    /**
     * {@code POST /api/orders}. Both the tenant and the cashier are stamped from the
     * session, never the body — {@link OrderForm} has no field for either, matching
     * {@code ProductForm}'s omission of {@code tenantId}.
     *
     * <p>The order number is minted from this store's own sequence <b>in the same
     * transaction as the insert</b> — see {@code TenantSequenceDao.next}. Both locks it
     * takes are held until this method returns, which is what reserves the value.
     *
     * <p>Lines are priced <i>before</i> the order is inserted, but not persisted until
     * after — {@link #buildLines} needs no order id, only {@code order.getTenantId()},
     * and the order has none until {@link OrderDao#insert} assigns one ({@code IDENTITY}
     * generation). {@link #insertLines} is the second half, once that id exists.
     */
    @Transactional
    public OrderData create(OrderForm form) {
        TenantContext.requireTenant();
        SessionUserData session = authService.currentSession();

        OrderStatus status = form.getStatus() == null ? OrderStatus.DRAFT : form.getStatus();
        if (status != OrderStatus.DRAFT && status != OrderStatus.HELD) {
            throw ValidationException.field("status", CREATE_STATUS_INVALID);
        }

        Long tenantId = session.getTenantId();

        PosOrderPojo order = new PosOrderPojo();
        order.setTenantId(tenantId);
        order.setCashierId(session.getId());
        order.setStatus(status);
        order.setOrderNumber(nextOrderNumber(tenantId));

        List<OrderLineForm> items = form.getItems() == null ? List.of() : form.getItems();
        BigDecimal orderDiscount = form.getOrderDiscount() == null
                ? BigDecimal.ZERO
                : form.getOrderDiscount();
        List<OrderLinePojo> lines = buildLines(order, items, orderDiscount);

        orderDao.insert(order);
        insertLines(order, lines);
        return toData(order, lines);
    }

    /**
     * {@code PATCH /api/orders/{id}} — hold / resume-and-reprice / cancel, all through one
     * merge patch, matching {@code orderService.update}.
     *
     * <p>{@code items} present replaces the line set wholesale and re-resolves every
     * variant, exactly like {@link #create}. {@code orderDiscount} present with
     * {@code items} absent re-totals the <i>existing</i> lines against the new discount
     * without a second variant lookup — those lines were already priced, and an order
     * discount does not change what a line itself costs.
     */
    @Transactional
    public OrderData update(Long id, OrderForm form) {
        TenantContext.requireTenant();

        PosOrderPojo order = orderDao.find(id);
        if (order == null) {
            throw new NotFoundException(NOT_FOUND);
        }
        if (order.getStatus() == OrderStatus.COMPLETED) {
            throw new ValidationException(ALREADY_COMPLETED);
        }

        List<OrderLinePojo> lines;
        if (form.getItems() != null) {
            BigDecimal orderDiscount = form.getOrderDiscount() == null
                    ? order.getOrderDiscount()
                    : form.getOrderDiscount();
            lines = buildLines(order, form.getItems(), orderDiscount);
            // orphanRemoval used to make this a side effect of order.getLines().clear();
            // now it is the explicit delete-then-reinsert its old Javadoc always
            // described in JPA terms.
            orderLineDao.deleteByOrder(order.getId());
            insertLines(order, lines);
        } else if (form.getOrderDiscount() != null) {
            lines = orderLineDao.findByOrder(order.getId());
            retotal(order, lines, form.getOrderDiscount());
        } else {
            lines = orderLineDao.findByOrder(order.getId());
        }

        if (form.getStatus() != null) {
            if (form.getStatus() != OrderStatus.HELD && form.getStatus() != OrderStatus.CANCELLED) {
                throw ValidationException.field("status", PATCH_STATUS_INVALID);
            }
            order.setStatus(form.getStatus());
        }

        return toData(order, lines);
    }

    /**
     * Takes this store's next order-number value. A tiny wrapper, but the seam
     * {@link PaymentService} would use if a return ever needed the identical call inside
     * the identical transaction shape (it does not — returns take {@code SequenceKind.RETURN}
     * from the same {@code TenantSequenceDao}, in C7).
     */
    private String nextOrderNumber(Long tenantId) {
        long sequence = tenantSequenceDao.next(SequenceKind.ORDER, tenantId);
        return String.format("ORD-%d-%04d", Year.now().getValue(), sequence);
    }

    /**
     * Resolves every requested line against the <i>current</i> variant row, prices the
     * whole set with {@link Pricing}, applies the resulting totals to {@code order}, and
     * returns the freshly-built lines — tenant-stamped but <b>not yet persisted, and
     * without an {@code orderId}</b>. {@link #create} does not know its order's id until
     * after {@code OrderDao.insert}; {@link #update} always does. Either way,
     * {@link #insertLines} is the second step that actually writes them.
     */
    private List<OrderLinePojo> buildLines(PosOrderPojo order, List<OrderLineForm> itemForms,
                                           BigDecimal orderDiscount) {
        if (itemForms.size() > appProperties.getOrderMaxLineItems()) {
            throw ValidationException.field("items", TOO_MANY_LINE_ITEMS);
        }

        List<VariantPojo> variants = new ArrayList<>(itemForms.size());
        List<Pricing.LineInput> inputs = new ArrayList<>(itemForms.size());
        for (OrderLineForm itemForm : itemForms) {
            VariantDao.VariantWithProduct found = resolveVariant(itemForm);
            variants.add(found.variant());
            inputs.add(lineInputOf(found.variant(), found.product(), itemForm));
        }

        Pricing.OrderTotals totals = Pricing.computeOrderTotals(inputs, orderDiscount);
        applyTotals(order, totals);

        List<OrderLinePojo> lines = new ArrayList<>(totals.lines.size());
        for (int i = 0; i < totals.lines.size(); i++) {
            Pricing.LineTotals computed = totals.lines.get(i);
            OrderLinePojo line = new OrderLinePojo();
            line.setTenantId(order.getTenantId());
            line.setVariantId(variants.get(i).getId());
            line.setName(computed.input.name);
            line.setQrCode(computed.input.qrCode);
            line.setQuantity(computed.input.quantity);
            line.setUnitPrice(computed.input.unitPrice);
            line.setTaxRatePercent(computed.input.taxRatePercent);
            line.setLineDiscount(computed.input.lineDiscount);
            line.setLineTotal(computed.lineTotal);
            lines.add(line);
        }
        return lines;
    }

    /**
     * Stamps {@code order}'s (by-then-assigned) id onto every line and inserts them —
     * the second half of {@link #buildLines}, split off because {@link #create} cannot
     * do this until after {@code OrderDao.insert}, while {@link #update} can do it
     * immediately.
     */
    private void insertLines(PosOrderPojo order, List<OrderLinePojo> lines) {
        for (OrderLinePojo line : lines) {
            line.setOrderId(order.getId());
        }
        orderLineDao.insertAll(lines);
    }

    /**
     * Re-totals against the order's own already-snapshotted lines — no variant lookup,
     * because an order-level discount does not reprice a line.
     */
    private void retotal(PosOrderPojo order, List<OrderLinePojo> lines, BigDecimal orderDiscount) {
        List<Pricing.LineInput> inputs = lines.stream()
                .map(this::lineInputOfExisting)
                .toList();
        applyTotals(order, Pricing.computeOrderTotals(inputs, orderDiscount));
    }

    private void applyTotals(PosOrderPojo order, Pricing.OrderTotals totals) {
        order.setSubtotal(totals.subtotal);
        order.setTotalTax(totals.totalTax);
        order.setOrderDiscount(totals.orderDiscount);
        order.setRoundOff(totals.roundOff);
        order.setGrandTotal(totals.grandTotal);
    }

    /**
     * {@code variantId} resolved through the tenant filter — a foreign or unknown id
     * fails exactly like a missing one, the same fail-closed answer
     * {@code VariantService.create} gives a foreign {@code productId}.
     */
    private VariantDao.VariantWithProduct resolveVariant(OrderLineForm form) {
        if (form.getVariantId() == null) {
            throw ValidationException.field("variantId", VARIANT_REQUIRED);
        }
        if (form.getQuantity() == null || form.getQuantity() <= 0) {
            throw ValidationException.field("quantity", QUANTITY_INVALID);
        }
        VariantDao.VariantWithProduct found = variantDao.findWithProduct(form.getVariantId());
        if (found == null) {
            throw new NotFoundException(VariantService.NOT_FOUND);
        }
        return found;
    }

    private Pricing.LineInput lineInputOf(VariantPojo variant, ProductPojo product, OrderLineForm form) {
        String name = product.getName() + " — " + variant.getVariantLabel();
        BigDecimal lineDiscount = form.getLineDiscount() == null ? BigDecimal.ZERO : form.getLineDiscount();
        return new Pricing.LineInput(variant.getId(), name, variant.getQrCode(),
                form.getQuantity(), variant.getSellingPrice(), product.getTaxRatePercent(),
                lineDiscount);
    }

    /** Rebuilds a {@link Pricing.LineInput} from an already-persisted, snapshotted line. */
    private Pricing.LineInput lineInputOfExisting(OrderLinePojo line) {
        return new Pricing.LineInput(line.getVariantId(), line.getName(), line.getQrCode(),
                line.getQuantity(), line.getUnitPrice(), line.getTaxRatePercent(),
                line.getLineDiscount());
    }

    /**
     * Mapped inside the transaction, fetching this order's lines itself — used by
     * {@link #get} and {@code PaymentService}, where a second query for one order's
     * lines is one extra statement, not a per-row problem.
     */
    OrderData toData(PosOrderPojo order) {
        return toData(order, orderLineDao.findByOrder(order.getId()));
    }

    /**
     * The core mapper, taking {@code lines} explicitly rather than fetching them itself
     * — {@link #list} batches every order's lines on the page into one query
     * ({@code OrderLineDao.findByOrders}, peer-review Phase 1) rather than reading each
     * row's lines with its own {@code SELECT}, and passes the already-loaded lines in
     * here instead.
     */
    private OrderData toData(PosOrderPojo order, List<OrderLinePojo> lines) {
        List<OrderLineData> items = lines.stream()
                .map(l -> new OrderLineData(l.getVariantId(), l.getName(), l.getQrCode(),
                        l.getQuantity(), l.getUnitPrice(), l.getTaxRatePercent(),
                        l.getLineDiscount(), l.getLineTotal()))
                .toList();

        PaymentData payment = order.getPaymentMethod() == null ? null : new PaymentData(
                order.getPaymentMethod(), order.getPaymentAmount(), order.getAmountTendered(),
                order.getChangeDue(), order.getPaymentReference());

        return new OrderData(
                order.getId(),
                order.getTenantId(),
                order.getOrderNumber(),
                items,
                order.getSubtotal(),
                order.getTotalTax(),
                order.getOrderDiscount(),
                order.getRoundOff(),
                order.getGrandTotal(),
                order.getStatus(),
                payment,
                order.getCashierId(),
                order.getCreatedAt());
    }
}

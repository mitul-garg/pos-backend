package com.pos.service;

import java.math.BigDecimal;
import java.time.Year;
import java.util.ArrayList;
import java.util.List;

import com.pos.dao.AppUserDao;
import com.pos.dao.OrderDao;
import com.pos.dao.TenantDao;
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
import com.pos.pojo.OrderLine;
import com.pos.pojo.OrderStatus;
import com.pos.pojo.PosOrder;
import com.pos.pojo.Product;
import com.pos.pojo.Role;
import com.pos.pojo.SequenceKind;
import com.pos.pojo.Tenant;
import com.pos.pojo.Variant;
import com.pos.util.Pricing;
import com.pos.util.TenantContext;
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
 * {@code lineDiscount} — no price, no tax rate, no name — and {@link #resolveLine}
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
    private final VariantDao variantDao;
    private final TenantDao tenantDao;
    private final AppUserDao appUserDao;
    private final TenantSequenceDao tenantSequenceDao;
    private final AuthService authService;

    @Autowired
    public OrderService(OrderDao orderDao, VariantDao variantDao, TenantDao tenantDao,
                        AppUserDao appUserDao, TenantSequenceDao tenantSequenceDao,
                        AuthService authService) {
        this.orderDao = orderDao;
        this.variantDao = variantDao;
        this.tenantDao = tenantDao;
        this.appUserDao = appUserDao;
        this.tenantSequenceDao = tenantSequenceDao;
        this.authService = authService;
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
        List<OrderData> items = orderDao
                .list(status, effectiveCashierId, (safePage - 1) * safePageSize, safePageSize)
                .stream()
                .map(this::toData)
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
        PosOrder order = orderDao.find(id);
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
     */
    @Transactional
    public OrderData create(OrderForm form) {
        TenantContext.requireTenant();
        SessionUserData session = authService.currentSession();

        OrderStatus status = form.getStatus() == null ? OrderStatus.DRAFT : form.getStatus();
        if (status != OrderStatus.DRAFT && status != OrderStatus.HELD) {
            throw ValidationException.field("status", CREATE_STATUS_INVALID);
        }

        Tenant tenant = tenantDao.reference(session.getTenantId());

        PosOrder order = new PosOrder();
        order.setTenant(tenant);
        order.setCashier(appUserDao.reference(session.getId()));
        order.setStatus(status);
        order.setOrderNumber(nextOrderNumber(tenant));

        List<OrderLineForm> items = form.getItems() == null ? List.of() : form.getItems();
        BigDecimal orderDiscount = form.getOrderDiscount() == null
                ? BigDecimal.ZERO
                : form.getOrderDiscount();
        rebuildLines(order, items, orderDiscount);

        orderDao.insert(order);
        return toData(order);
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

        PosOrder order = orderDao.find(id);
        if (order == null) {
            throw new NotFoundException(NOT_FOUND);
        }
        if (order.getStatus() == OrderStatus.COMPLETED) {
            throw new ValidationException(ALREADY_COMPLETED);
        }

        if (form.getItems() != null) {
            BigDecimal orderDiscount = form.getOrderDiscount() == null
                    ? order.getOrderDiscount()
                    : form.getOrderDiscount();
            rebuildLines(order, form.getItems(), orderDiscount);
        } else if (form.getOrderDiscount() != null) {
            retotal(order, form.getOrderDiscount());
        }

        if (form.getStatus() != null) {
            if (form.getStatus() != OrderStatus.HELD && form.getStatus() != OrderStatus.CANCELLED) {
                throw ValidationException.field("status", PATCH_STATUS_INVALID);
            }
            order.setStatus(form.getStatus());
        }

        return toData(order);
    }

    /**
     * Takes this store's next order-number value. A tiny wrapper, but the seam
     * {@link PaymentService} would use if a return ever needed the identical call inside
     * the identical transaction shape (it does not — returns take {@code SequenceKind.RETURN}
     * from the same {@code TenantSequenceDao}, in C7).
     */
    private String nextOrderNumber(Tenant tenant) {
        long sequence = tenantSequenceDao.next(SequenceKind.ORDER, tenant);
        return String.format("ORD-%d-%04d", Year.now().getValue(), sequence);
    }

    /**
     * Resolves every requested line against the <i>current</i> variant row, prices the
     * whole set with {@link Pricing}, and replaces {@code order}'s line collection.
     * {@code orphanRemoval} on {@code PosOrder.lines} is what makes {@code clear()} a
     * deletion rather than a detachment — editing a held order's cart is exactly this.
     */
    private void rebuildLines(PosOrder order, List<OrderLineForm> itemForms, BigDecimal orderDiscount) {
        List<Variant> variants = new ArrayList<>(itemForms.size());
        List<Pricing.LineInput> inputs = new ArrayList<>(itemForms.size());
        for (OrderLineForm itemForm : itemForms) {
            Variant variant = resolveVariant(itemForm);
            variants.add(variant);
            inputs.add(lineInputOf(variant, itemForm));
        }

        Pricing.OrderTotals totals = Pricing.computeOrderTotals(inputs, orderDiscount);

        order.getLines().clear();
        for (int i = 0; i < totals.lines.size(); i++) {
            Pricing.LineTotals computed = totals.lines.get(i);
            OrderLine line = new OrderLine();
            line.setTenant(order.getTenant());
            line.setVariant(variants.get(i));
            line.setName(computed.input.name);
            line.setQrCode(computed.input.qrCode);
            line.setQuantity(computed.input.quantity);
            line.setUnitPrice(computed.input.unitPrice);
            line.setTaxRatePercent(computed.input.taxRatePercent);
            line.setLineDiscount(computed.input.lineDiscount);
            line.setLineTotal(computed.lineTotal);
            order.addLine(line);
        }
        applyTotals(order, totals);
    }

    /**
     * Re-totals against the order's own already-snapshotted lines — no variant lookup,
     * because an order-level discount does not reprice a line.
     */
    private void retotal(PosOrder order, BigDecimal orderDiscount) {
        List<Pricing.LineInput> inputs = order.getLines().stream()
                .map(this::lineInputOfExisting)
                .toList();
        applyTotals(order, Pricing.computeOrderTotals(inputs, orderDiscount));
    }

    private void applyTotals(PosOrder order, Pricing.OrderTotals totals) {
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
    private Variant resolveVariant(OrderLineForm form) {
        if (form.getVariantId() == null) {
            throw ValidationException.field("variantId", VARIANT_REQUIRED);
        }
        if (form.getQuantity() == null || form.getQuantity() <= 0) {
            throw ValidationException.field("quantity", QUANTITY_INVALID);
        }
        Variant variant = variantDao.findWithProduct(form.getVariantId());
        if (variant == null) {
            throw new NotFoundException(VariantService.NOT_FOUND);
        }
        return variant;
    }

    private Pricing.LineInput lineInputOf(Variant variant, OrderLineForm form) {
        Product product = variant.getProduct();
        String name = product.getName() + " — " + variant.getVariantLabel();
        BigDecimal lineDiscount = form.getLineDiscount() == null ? BigDecimal.ZERO : form.getLineDiscount();
        return new Pricing.LineInput(variant.getId(), name, variant.getQrCode(),
                form.getQuantity(), variant.getSellingPrice(), product.getTaxRatePercent(),
                lineDiscount);
    }

    /** Rebuilds a {@link Pricing.LineInput} from an already-persisted, snapshotted line. */
    private Pricing.LineInput lineInputOfExisting(OrderLine line) {
        return new Pricing.LineInput(line.getVariant().getId(), line.getName(), line.getQrCode(),
                line.getQuantity(), line.getUnitPrice(), line.getTaxRatePercent(),
                line.getLineDiscount());
    }

    /**
     * Mapped inside the transaction — {@code order.getLines()} and the {@code cashier}
     * association are both {@code LAZY}, and this is the one read of either.
     */
    OrderData toData(PosOrder order) {
        List<OrderLineData> items = order.getLines().stream()
                .map(l -> new OrderLineData(l.getVariant().getId(), l.getName(), l.getQrCode(),
                        l.getQuantity(), l.getUnitPrice(), l.getTaxRatePercent(),
                        l.getLineDiscount(), l.getLineTotal()))
                .toList();

        PaymentData payment = order.getPaymentMethod() == null ? null : new PaymentData(
                order.getPaymentMethod(), order.getPaymentAmount(), order.getAmountTendered(),
                order.getChangeDue(), order.getPaymentReference());

        return new OrderData(
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
}

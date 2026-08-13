package com.pos.dao;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.pos.pojo.OrderLinePojo;
import com.pos.pojo.enums.OrderStatus;
import com.pos.pojo.PosOrderPojo;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Repository;

/**
 * Persistence for {@code pos_order} (C6).
 *
 * <p>No method here takes a tenant, for the reason on {@code ProductDao}: the
 * {@code tenantFilter} appends {@code tenant_id = ?} to every statement below. The one
 * place a query does name an id explicitly is {@link #find}, and that is a primary key —
 * safe only because the {@code @FilterDef} sets {@code applyToLoadByKey}.
 *
 * <p>{@code lines} is left {@code LAZY} on {@link PosOrderPojo} and not {@code JOIN FETCH}ed
 * here, unlike {@code VariantDao}'s join onto its parent product. A to-many collection
 * cannot be fetch-joined under {@code setFirstResult}/{@code setMaxResults} without
 * Hibernate silently paginating in memory — the classic JPA trap — so {@link #list}
 * leaves it lazy. {@code OrderService.list} no longer reads it per row, though
 * ({@link #findLinesByOrderIds} below, peer-review Phase 1) — {@link #get} and
 * {@code PaymentService} still do, which is one extra statement for one order, not a
 * per-row problem.
 */
@Repository
public class OrderDao {

    @PersistenceContext
    private EntityManager em;

    /**
     * By primary key. See {@code ProductDao.find} for why this is a plain {@code find}
     * rather than JPQL: the JPQL form would stay scoped even with
     * {@code applyToLoadByKey} removed, hiding the regression from the test that exists
     * to catch it.
     */
    public PosOrderPojo find(Long id) {
        return em.find(PosOrderPojo.class, id);
    }

    /**
     * By primary key, locked for the rest of this transaction (C7). {@code
     * ReturnService.create} takes this before reading how much of an order has already
     * been returned, so two returns racing against the <b>same order</b> serialize
     * rather than both computing a returnable quantity against the same unreturned
     * baseline — the identical race {@code TenantSequenceDao.next} locks a tenant row
     * to prevent, scoped here to one order rather than the whole store, since only two
     * returns against the same order can conflict.
     *
     * <p>Plain {@code em.find} for the same reason {@link #find} is: the
     * {@code @FilterDef}'s {@code applyToLoadByKey} is what keeps this scoped, and
     * rewriting it as JPQL to "add" scoping would hide a regression there instead of
     * catching one.
     */
    public PosOrderPojo findForUpdate(Long id) {
        return em.find(PosOrderPojo.class, id, LockModeType.PESSIMISTIC_WRITE);
    }

    /**
     * By order number, within the caller's tenant (C7). Order numbers repeat across
     * tenants — each store runs its own {@code ORD-YYYY-####} sequence from 1 — so this
     * <b>must</b> resolve through the filter, or {@code "ORD-2026-0001"} could hand back
     * whichever tenant happens to have inserted it first. {@code ReturnService.lookupOrder}
     * is the one caller.
     */
    public PosOrderPojo findByOrderNumber(String orderNumber) {
        return em.createQuery("SELECT o FROM PosOrderPojo o WHERE o.orderNumber = :orderNumber",
                        PosOrderPojo.class)
                .setParameter("orderNumber", orderNumber)
                .getResultStream()
                .findFirst()
                .orElse(null);
    }

    /** One page, newest first — matching {@code orderService.list}'s sort. */
    public List<PosOrderPojo> list(OrderStatus status, Long cashierId, int offset, int limit) {
        TypedQuery<PosOrderPojo> query = em.createQuery(
                "SELECT o FROM PosOrderPojo o" + where(status, cashierId)
                        + " ORDER BY o.createdAt DESC, o.id DESC",
                PosOrderPojo.class);
        bind(query, status, cashierId);
        return query.setFirstResult(offset).setMaxResults(limit).getResultList();
    }

    /** How many rows {@link #list} would return unpaged — the envelope's {@code total}. */
    public long count(OrderStatus status, Long cashierId) {
        TypedQuery<Long> query = em.createQuery(
                "SELECT count(o) FROM PosOrderPojo o" + where(status, cashierId), Long.class);
        bind(query, status, cashierId);
        return query.getSingleResult();
    }

    /**
     * Inserts (cascading the lines) and <b>flushes on purpose</b> — same reasoning as
     * {@code VariantDao.insert}: without it, a constraint violation on
     * {@code uk_order_tenant_number} would surface at commit, outside the call that
     * caused it, wrapped in a {@code TransactionSystemException} that has lost the
     * field it should be blamed on.
     */
    public void insert(PosOrderPojo order) {
        em.persist(order);
        em.flush();
    }

    /**
     * Every line for a whole page of orders, batched into one query and grouped by
     * order id — the fix for {@code OrderService.list}'s N+1 (peer-review Phase 1):
     * one extra {@code SELECT} for the whole page instead of one per row.
     * {@code OrderLinePojo} carries its own {@code tenant_id} column (denormalised for
     * exactly this reason, per its own Javadoc), so this query is scoped on its own
     * terms rather than through a joined parent.
     *
     * <p>Ordered {@code order_id, id} so each order's lines come back in the same order
     * {@code order.getLines()} would produce — neither side declares an
     * {@code @OrderBy}, so that order is insertion order, which is also primary-key
     * order.
     */
    public Map<Long, List<OrderLinePojo>> findLinesByOrderIds(List<Long> orderIds) {
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        List<OrderLinePojo> lines = em.createQuery(
                        "SELECT l FROM OrderLinePojo l WHERE l.order.id IN :orderIds"
                                + " ORDER BY l.order.id, l.id",
                        OrderLinePojo.class)
                .setParameter("orderIds", orderIds)
                .getResultList();

        Map<Long, List<OrderLinePojo>> byOrder = new LinkedHashMap<>();
        for (OrderLinePojo line : lines) {
            byOrder.computeIfAbsent(line.getOrder().getId(), k -> new ArrayList<>()).add(line);
        }
        return byOrder;
    }

    private String where(OrderStatus status, Long cashierId) {
        List<String> conditions = new ArrayList<>();
        if (status != null) {
            conditions.add("o.status = :status");
        }
        if (cashierId != null) {
            conditions.add("o.cashier.id = :cashierId");
        }
        return conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
    }

    private void bind(TypedQuery<?> query, OrderStatus status, Long cashierId) {
        if (status != null) {
            query.setParameter("status", status);
        }
        if (cashierId != null) {
            query.setParameter("cashierId", cashierId);
        }
    }
}

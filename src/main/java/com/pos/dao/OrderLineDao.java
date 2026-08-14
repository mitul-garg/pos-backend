package com.pos.dao;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.pos.pojo.OrderLinePojo;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

/**
 * Persistence for {@code order_line} (C6), split out from {@link OrderDao} by peer-review
 * Phase 2's {@code @ManyToOne}-removal sweep: {@link com.pos.pojo.PosOrderPojo#getId()
 * PosOrderPojo}'s old {@code lines} field (a {@code @OneToMany(mappedBy = "order",
 * cascade = ALL, orphanRemoval = true)}) let {@code OrderService.rebuildLines} delete and
 * insert lines purely through JPA cascade; now that the association is gone, this DAO is
 * where those writes happen explicitly.
 *
 * <p>No method here takes a tenant, for the reason on {@code OrderDao}: the
 * {@code tenantFilter} on {@link OrderLinePojo} appends {@code tenant_id = ?} to every
 * ordinary query below. {@link #deleteByOrder} is bulk JPQL and therefore unfiltered —
 * safe because every caller reaches it with an {@code orderId} already resolved through a
 * filtered read earlier in the same transaction (the order itself, loaded via
 * {@code OrderDao.find}), the identical reasoning {@code VariantDao.decrementStock}
 * documents for its own bulk statements.
 */
@Repository
public class OrderLineDao {

    @PersistenceContext
    private EntityManager em;

    /**
     * One order's lines, in primary-key order — also insertion order, since neither this
     * entity nor its old parent collection ever declared an {@code @OrderBy}. Replaces
     * the old lazy {@code order.getLines()} read.
     */
    public List<OrderLinePojo> findByOrder(Long orderId) {
        return em.createQuery(
                        "SELECT l FROM OrderLinePojo l WHERE l.orderId = :orderId ORDER BY l.id",
                        OrderLinePojo.class)
                .setParameter("orderId", orderId)
                .getResultList();
    }

    /**
     * Every line for a whole page of orders, batched into one query and grouped by order
     * id — the fix for {@code OrderService.list}'s N+1 (peer-review Phase 1), moved here
     * from {@code OrderDao.findLinesByOrderIds} once {@code OrderLinePojo} got its own
     * DAO. Ordered {@code order_id, id} so each order's lines come back in the same order
     * {@link #findByOrder} would produce for it.
     */
    public Map<Long, List<OrderLinePojo>> findByOrders(List<Long> orderIds) {
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        List<OrderLinePojo> lines = em.createQuery(
                        "SELECT l FROM OrderLinePojo l WHERE l.orderId IN :orderIds"
                                + " ORDER BY l.orderId, l.id",
                        OrderLinePojo.class)
                .setParameter("orderIds", orderIds)
                .getResultList();

        Map<Long, List<OrderLinePojo>> byOrder = new LinkedHashMap<>();
        for (OrderLinePojo line : lines) {
            byOrder.computeIfAbsent(line.getOrderId(), k -> new ArrayList<>()).add(line);
        }
        return byOrder;
    }

    /**
     * Inserts a freshly-built line set, and <b>flushes on purpose</b> — same reasoning as
     * {@code VariantDao.insert}: without it, a {@code ck_order_line_quantity_positive}
     * violation would surface at commit, outside the call that caused it, wrapped in a
     * {@code TransactionSystemException} that has lost the field it should be blamed on.
     */
    public void insertAll(List<OrderLinePojo> lines) {
        for (OrderLinePojo line : lines) {
            em.persist(line);
        }
        em.flush();
    }

    /**
     * Bulk delete, scoped by an {@code orderId} already resolved through a filtered read
     * earlier in the same transaction — {@code OrderService.rebuildLines}'s replacement
     * for {@code order.getLines().clear()} now that {@code orphanRemoval} is gone. Not
     * scoped by the tenant filter itself: a bulk statement names no entity alias for
     * Hibernate to filter, the same shape {@code VariantDao.decrementStock} documents.
     * The database's own {@code ON DELETE CASCADE} on {@code fk_order_line_pos_order}
     * covers the one case this doesn't need to: deleting the order row itself.
     */
    public void deleteByOrder(Long orderId) {
        em.createQuery("DELETE FROM OrderLinePojo l WHERE l.orderId = :orderId")
                .setParameter("orderId", orderId)
                .executeUpdate();
    }
}

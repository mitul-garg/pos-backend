package com.pos.dao;

import java.util.ArrayList;
import java.util.List;

import com.pos.pojo.OrderStatus;
import com.pos.pojo.PosOrder;
import jakarta.persistence.EntityManager;
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
 * <p>{@code lines} is left {@code LAZY} on {@link PosOrder} and not {@code JOIN FETCH}ed
 * here, unlike {@code VariantDao}'s join onto its parent product. A to-many collection
 * cannot be fetch-joined under {@code setFirstResult}/{@code setMaxResults} without
 * Hibernate silently paginating in memory — the classic JPA trap — so {@link #list}
 * leaves it lazy and {@code OrderService} reads it per row inside the same transaction.
 * That is one extra statement per order on a page (bounded by {@code pageSize}, at most
 * 200), not per line, and is the same trade {@code ProductDao} already makes for every
 * association it does not eagerly join.
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
    public PosOrder find(Long id) {
        return em.find(PosOrder.class, id);
    }

    /** One page, newest first — matching {@code orderService.list}'s sort. */
    public List<PosOrder> list(OrderStatus status, Long cashierId, int offset, int limit) {
        TypedQuery<PosOrder> query = em.createQuery(
                "SELECT o FROM PosOrder o" + where(status, cashierId)
                        + " ORDER BY o.createdAt DESC, o.id DESC",
                PosOrder.class);
        bind(query, status, cashierId);
        return query.setFirstResult(offset).setMaxResults(limit).getResultList();
    }

    /** How many rows {@link #list} would return unpaged — the envelope's {@code total}. */
    public long count(OrderStatus status, Long cashierId) {
        TypedQuery<Long> query = em.createQuery(
                "SELECT count(o) FROM PosOrder o" + where(status, cashierId), Long.class);
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
    public void insert(PosOrder order) {
        em.persist(order);
        em.flush();
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

package com.pos.dao;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.pos.pojo.ReturnLinePojo;
import com.pos.pojo.SalesReturnPojo;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Repository;

/**
 * Persistence for {@code sales_return} (C7).
 *
 * <p>No method here takes a tenant, for the reason on {@code OrderDao}: the
 * {@code tenantFilter} appends {@code tenant_id = ?} to every statement below, including
 * {@link #returnedQuantitiesByVariant} — which is filtered on {@code ReturnLinePojo}'s own
 * {@code tenant_id} directly rather than through a joined parent. See that method's
 * Javadoc for why the distinction matters.
 */
@Repository
public class ReturnDao {

    @PersistenceContext
    private EntityManager em;

    /**
     * By primary key. See {@code OrderDao.find} for why this stays a plain
     * {@code em.find} rather than JPQL — {@code applyToLoadByKey} is what keeps it
     * scoped.
     */
    public SalesReturnPojo find(Long id) {
        return em.find(SalesReturnPojo.class, id);
    }

    /** One page, newest first — matching {@code returnService.list}'s sort. */
    public List<SalesReturnPojo> list(Long processedById, int offset, int limit) {
        TypedQuery<SalesReturnPojo> query = em.createQuery(
                "SELECT r FROM SalesReturnPojo r" + where(processedById)
                        + " ORDER BY r.createdAt DESC, r.id DESC",
                SalesReturnPojo.class);
        bind(query, processedById);
        return query.setFirstResult(offset).setMaxResults(limit).getResultList();
    }

    /** How many rows {@link #list} would return unpaged — the envelope's {@code total}. */
    public long count(Long processedById) {
        TypedQuery<Long> query = em.createQuery(
                "SELECT count(r) FROM SalesReturnPojo r" + where(processedById), Long.class);
        bind(query, processedById);
        return query.getSingleResult();
    }

    private String where(Long processedById) {
        return processedById == null ? "" : " WHERE r.processedBy.id = :processedById";
    }

    private void bind(TypedQuery<?> query, Long processedById) {
        if (processedById != null) {
            query.setParameter("processedById", processedById);
        }
    }

    /**
     * Inserts (cascading the lines) and <b>flushes on purpose</b> — same reasoning as
     * {@code OrderDao.insert}: without it, a constraint violation on
     * {@code uk_return_tenant_number} would surface at commit, outside the call that
     * caused it, wrapped in a {@code TransactionSystemException} that has lost the field
     * it should be blamed on.
     */
    public void insert(SalesReturnPojo salesReturn) {
        em.persist(salesReturn);
        em.flush();
    }

    /**
     * How much of each line of one order has already been returned, keyed by
     * {@code variantId} — the number both {@code ReturnService.lookupOrder} and
     * {@code ReturnService.create} subtract from the purchased quantity to get what
     * remains returnable.
     *
     * <p><b>Filtered directly, not through a join.</b> The {@code SELECT} reads only
     * {@code ReturnLinePojo}'s own columns, so its {@code @Filter} applies to the table this
     * query actually touches. That is the opposite shape from {@code VariantDao}'s
     * reads, which lean on a {@code JOIN FETCH}ed, separately-filtered parent — this
     * query has nothing else behind it if {@code ReturnLinePojo}'s own annotation were ever
     * dropped, which is exactly the case CONVENTIONS.md flags as the one a green
     * isolation case can't vouch for by itself. {@code TenantFilterCoverageTest} is what
     * actually holds this line, not this query's own test.
     */
    public Map<Long, Integer> returnedQuantitiesByVariant(Long orderId) {
        List<Object[]> rows = em.createQuery(
                        "SELECT rl.variant.id, SUM(rl.quantity) FROM ReturnLinePojo rl "
                                + "WHERE rl.salesReturn.originalOrder.id = :orderId "
                                + "GROUP BY rl.variant.id",
                        Object[].class)
                .setParameter("orderId", orderId)
                .getResultList();

        Map<Long, Integer> byVariant = new HashMap<>();
        for (Object[] row : rows) {
            byVariant.put((Long) row[0], ((Number) row[1]).intValue());
        }
        return byVariant;
    }

    /**
     * Every line for a whole page of returns, batched into one query and grouped by
     * return id — the return-history half of {@code OrderDao.findLinesByOrderIds}'s N+1
     * fix (peer-review Phase 1), same shape for the identical reason.
     *
     * <p>Ordered {@code return_id, id} so each return's lines come back in the same
     * order {@code salesReturn.getLines()} would produce.
     */
    public Map<Long, List<ReturnLinePojo>> findLinesByReturnIds(List<Long> returnIds) {
        if (returnIds.isEmpty()) {
            return Map.of();
        }
        List<ReturnLinePojo> lines = em.createQuery(
                        "SELECT l FROM ReturnLinePojo l WHERE l.salesReturn.id IN :returnIds"
                                + " ORDER BY l.salesReturn.id, l.id",
                        ReturnLinePojo.class)
                .setParameter("returnIds", returnIds)
                .getResultList();

        Map<Long, List<ReturnLinePojo>> byReturn = new LinkedHashMap<>();
        for (ReturnLinePojo line : lines) {
            byReturn.computeIfAbsent(line.getSalesReturn().getId(), k -> new ArrayList<>()).add(line);
        }
        return byReturn;
    }
}

package com.pos.dao;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.pos.pojo.ReturnLinePojo;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

/**
 * Persistence for {@code return_line} (C7), split out from {@link ReturnDao} the same way
 * peer-review Phase 2's {@code @ManyToOne}-removal sweep split {@code OrderLineDao} out of
 * {@code OrderDao}: {@code SalesReturnPojo}'s old {@code lines} field (a
 * {@code @OneToMany(mappedBy = "salesReturn", cascade = ALL, orphanRemoval = true)}) let
 * {@code ReturnService.create} insert every line purely through JPA cascade; now that the
 * association is gone, this DAO is where that insert happens explicitly. Simpler than
 * {@code OrderLineDao} in one respect: a return is insert-only once created
 * (requirements.md §3/§7), so there is no rebuild/delete path and therefore no
 * {@code deleteByReturn}.
 *
 * <p>No method here takes a tenant, for the reason on {@code ReturnDao}: the
 * {@code tenantFilter} on {@link ReturnLinePojo} appends {@code tenant_id = ?} to every
 * query below.
 */
@Repository
public class ReturnLineDao {

    @PersistenceContext
    private EntityManager em;

    /**
     * One return's lines, in primary-key order — also insertion order, since neither
     * this entity nor its old parent collection ever declared an {@code @OrderBy}.
     * Replaces the old lazy {@code salesReturn.getLines()} read.
     */
    public List<ReturnLinePojo> findByReturn(Long returnId) {
        return em.createQuery(
                        "SELECT l FROM ReturnLinePojo l WHERE l.returnId = :returnId ORDER BY l.id",
                        ReturnLinePojo.class)
                .setParameter("returnId", returnId)
                .getResultList();
    }

    /**
     * Every line for a whole page of returns, batched into one query and grouped by
     * return id — the return-history half of {@code OrderLineDao.findByOrders}'s N+1 fix
     * (peer-review Phase 1), moved here from {@code ReturnDao.findLinesByReturnIds} once
     * {@code ReturnLinePojo} got its own DAO.
     */
    public Map<Long, List<ReturnLinePojo>> findByReturns(List<Long> returnIds) {
        if (returnIds.isEmpty()) {
            return Map.of();
        }
        List<ReturnLinePojo> lines = em.createQuery(
                        "SELECT l FROM ReturnLinePojo l WHERE l.returnId IN :returnIds"
                                + " ORDER BY l.returnId, l.id",
                        ReturnLinePojo.class)
                .setParameter("returnIds", returnIds)
                .getResultList();

        Map<Long, List<ReturnLinePojo>> byReturn = new LinkedHashMap<>();
        for (ReturnLinePojo line : lines) {
            byReturn.computeIfAbsent(line.getReturnId(), k -> new ArrayList<>()).add(line);
        }
        return byReturn;
    }

    /**
     * How much of each line of one order has already been returned, keyed by
     * {@code variantId} — the number both {@code ReturnService.lookupOrder} and
     * {@code ReturnService.create} subtract from the purchased quantity to get what
     * remains returnable. Moved here from {@code ReturnDao} once {@code ReturnLinePojo}
     * got its own DAO — this is fundamentally a query over its own rows, the join onto
     * {@code SalesReturnPojo} exists only to reach {@code originalOrderId}.
     *
     * <p><b>An ad-hoc join, not a path expression.</b> {@code ReturnLinePojo.salesReturn}
     * is no longer a navigable association (peer-review Phase 2), so the old
     * {@code rl.salesReturn.originalOrder.id} became an explicit {@code JOIN ... ON}
     * against {@code r.originalOrderId} — same shape as {@code VariantDao}'s ad-hoc join
     * onto its product. Both {@code rl} and {@code r} stay independently scoped by their
     * own {@code @Filter}, so this is no less isolated than the single-entity read it
     * replaced — see {@code TenantFilterCoverageTest}.
     */
    public Map<Long, Integer> returnedQuantitiesByVariant(Long orderId) {
        List<Object[]> rows = em.createQuery(
                        "SELECT rl.variantId, SUM(rl.quantity) FROM ReturnLinePojo rl "
                                + "JOIN SalesReturnPojo r ON r.id = rl.returnId "
                                + "WHERE r.originalOrderId = :orderId "
                                + "GROUP BY rl.variantId",
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
     * Inserts a freshly-built line set, and <b>flushes on purpose</b> — same reasoning as
     * {@code OrderLineDao.insertAll}: a constraint violation should surface here, inside
     * the call that caused it, not wrapped in a {@code TransactionSystemException} at
     * commit.
     */
    public void insertAll(List<ReturnLinePojo> lines) {
        for (ReturnLinePojo line : lines) {
            em.persist(line);
        }
        em.flush();
    }
}

package com.pos.dao;

import java.util.List;

import com.pos.pojo.SalesReturnPojo;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Repository;

/**
 * Persistence for {@code sales_return} (C7).
 *
 * <p>No method here takes a tenant, for the reason on {@code OrderDao}: the
 * {@code tenantFilter} appends {@code tenant_id = ?} to every statement below.
 * {@code ReturnLinePojo}'s own persistence — including the batched N+1 fix and the
 * already-returned-quantities lookup — lives in {@code ReturnLineDao}, split out by
 * peer-review Phase 2 the same way {@code OrderLineDao} split out of {@code OrderDao}.
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
        return processedById == null ? "" : " WHERE r.processedById = :processedById";
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
}

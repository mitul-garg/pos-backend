package com.pos.dao;

import java.util.List;

import com.pos.pojo.Product;
import com.pos.pojo.Tenant;
import com.pos.util.PlatformOperation;
import com.pos.util.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Session;
import org.springframework.stereotype.Repository;

/**
 * Persistence for the tenant boundary itself (C3).
 *
 * <p><b>This DAO is deliberately outside tenant scoping</b>, and it is the only one that
 * ever will be. {@code tenant} carries no {@code tenant_id} column — it <i>is</i> the
 * discriminator — so the Hibernate filter arriving in C4 does not apply to it. Reading
 * a tenant row is therefore not a cross-tenant read; reading another tenant's
 * <i>contents</i> is, and that is what the filter prevents everywhere else.
 *
 * <p><b>{@link #productCount} and {@link #orderCount} are the one place that changes</b>
 * (C8): they read {@code Product}/{@code PosOrder}, both {@code @Filter}ed, on behalf of
 * a {@code SUPER_ADMIN} who has no tenant of their own — so the filter is disabled for
 * those two queries alone, behind {@link PlatformOperation}. Everything else here is
 * exactly what it was in C3.
 */
@Repository
public class TenantDao {

    @PersistenceContext
    private EntityManager em;

    /**
     * Resolves the login discriminator. Lower-cased on both sides rather than relying on
     * MySQL's case-insensitive default collation, so the rule is stated in the query and
     * survives a collation change.
     *
     * <p>Returns {@code null} rather than throwing: at login an unknown code is not an
     * error condition to be reported, it is one of several inputs that must all produce
     * the same generic 401 (see {@code AuthService}).
     */
    public Tenant findByCode(String code) {
        return em.createQuery(
                        "SELECT t FROM Tenant t WHERE lower(t.code) = :code", Tenant.class)
                .setParameter("code", code)
                .getResultStream()
                .findFirst()
                .orElse(null);
    }

    /**
     * The one reserved row that owns the {@code SUPER_ADMIN} users.
     *
     * <p>Matched on the {@code is_platform} flag rather than on {@code code = 'platform'}
     * so the platform namespace is identified by what it is, not by a string a future
     * tenant might claim.
     */
    public Tenant findPlatform() {
        return em.createQuery(
                        "SELECT t FROM Tenant t WHERE t.platform = true", Tenant.class)
                .getResultStream()
                .findFirst()
                .orElse(null);
    }

    public Tenant find(Long id) {
        return em.find(Tenant.class, id);
    }

    /**
     * Every tenant but the reserved platform row, newest first (C8) — the platform's own
     * list, matching the mock's {@code [...store.tenants].sort((a, b) => a.createdAt <
     * b.createdAt ? 1 : -1)}. {@code SUPER_ADMIN} is the only caller: every tenant-scoped
     * role has exactly one tenant and reaches it through the session, never this method.
     */
    public List<Tenant> list() {
        return em.createQuery(
                        "SELECT t FROM Tenant t WHERE t.platform = false ORDER BY t.createdAt DESC",
                        Tenant.class)
                .getResultList();
    }

    /**
     * How many products this tenant's catalogue holds, active or not — one of the row
     * counts {@code GET /api/tenants} shows so a suspension is an informed decision
     * rather than a guess (mirrors the frontend's {@code withCounts}).
     *
     * <p><b>The filter has to come off for this one.</b> {@link Product} carries
     * {@code @Filter}, and the caller is a {@code SUPER_ADMIN} with no tenant on the
     * thread — {@code TenantContext}'s resolver would supply {@link TenantContext#NO_TENANT}
     * and this would answer zero for every store, every time. Disabling it is exactly
     * the reach {@link PlatformOperation} exists to flag and {@code grep} for.
     *
     * <p>Re-enabled in a {@code finally}, and that matters more than it looks: this
     * {@code EntityManager} does not end when this method returns.
     * {@code TenantService.list} calls this once per tenant in a loop over the same
     * session, so leaving the filter off would un-scope every query for the rest of that
     * loop — including {@link #orderCount}'s own call, and anything after it in the same
     * transaction.
     */
    @PlatformOperation
    public long productCount(Long tenantId) {
        Session session = em.unwrap(Session.class);
        session.disableFilter(TenantContext.FILTER_NAME);
        try {
            return em.createQuery(
                            "SELECT count(p) FROM Product p WHERE p.tenant.id = :tenantId",
                            Long.class)
                    .setParameter("tenantId", tenantId)
                    .getSingleResult();
        } finally {
            session.enableFilter(TenantContext.FILTER_NAME)
                    .setParameter(TenantContext.PARAM_NAME, TenantContext.NO_TENANT);
        }
    }

    /** The order-count half of the same story — see {@link #productCount}. */
    @PlatformOperation
    public long orderCount(Long tenantId) {
        Session session = em.unwrap(Session.class);
        session.disableFilter(TenantContext.FILTER_NAME);
        try {
            return em.createQuery(
                            "SELECT count(o) FROM PosOrder o WHERE o.tenant.id = :tenantId",
                            Long.class)
                    .setParameter("tenantId", tenantId)
                    .getSingleResult();
        } finally {
            session.enableFilter(TenantContext.FILTER_NAME)
                    .setParameter(TenantContext.PARAM_NAME, TenantContext.NO_TENANT);
        }
    }

    /**
     * A proxy for the row with this id, for stamping a foreign key without reading it
     * (C5). Issues no SQL: the only thing an {@code INSERT} needs from the parent is its
     * id, and that is already in hand.
     *
     * <p><b>The {@code id} parameter is not a scoping argument</b>, which is the thing
     * CONVENTIONS.md forbids passing between layers. Nothing here is being filtered by it
     * — it names the row to point at, and its one caller takes it from
     * {@code AuthService.currentSession()}, i.e. from the token. A tenant id that arrived
     * in a request body must never reach this method.
     */
    public Tenant reference(Long id) {
        return em.getReference(Tenant.class, id);
    }

    /**
     * <b>Flushes on purpose</b> (C8), matching the fix C5 applied to
     * {@code VariantDao.insert} once uniqueness became something a request could race.
     * {@code TenantService.create} inserts this and an {@link com.pos.pojo.AppUser} in
     * the same transaction; without the flush, a duplicate {@code code} raised at commit
     * arrives wrapped in a {@code TransactionSystemException} the
     * {@code ApiExceptionHandler} never sees. Flushing here keeps the failure inside the
     * call that caused it, still carrying the constraint name that turns it into a
     * field-level 400. Harmless before C8: nothing called this on a path a caller could
     * race until tenant creation existed.
     */
    public void insert(Tenant tenant) {
        em.persist(tenant);
        em.flush();
    }
}

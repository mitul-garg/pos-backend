package com.pos.dao;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.pos.pojo.ProductPojo;
import com.pos.pojo.TenantPojo;
import com.pos.pojo.enums.TenantStatus;
import com.pos.util.tenancy.PlatformOperation;
import com.pos.util.tenancy.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
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
 * (C8): they read {@code ProductPojo}/{@code PosOrderPojo}, both {@code @Filter}ed, on behalf of
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
    public TenantPojo findByCode(String code) {
        return em.createQuery(
                        "SELECT t FROM TenantPojo t WHERE lower(t.code) = :code", TenantPojo.class)
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
    public TenantPojo findPlatform() {
        return em.createQuery(
                        "SELECT t FROM TenantPojo t WHERE t.platform = true", TenantPojo.class)
                .getResultStream()
                .findFirst()
                .orElse(null);
    }

    public TenantPojo find(Long id) {
        return em.find(TenantPojo.class, id);
    }

    /**
     * Resolves a self-registration's verification token (C9) — the lookup {@code
     * TenantRegistrationService.verify} runs against whatever the caller pasted in
     * from the emailed link. Returns {@code null} for an unknown token, matching
     * {@link #findByCode}'s reasoning: at this endpoint an unrecognized token isn't
     * an error to report distinctly, it's one of several inputs that must all fall
     * through to the same generic "invalid or expired" response.
     *
     * <p>No {@code lower()} normalization, unlike {@link #findByCode} — a token is an
     * opaque, case-sensitive random string ({@code VerificationTokens}, base64url),
     * not a human-typed identifier with a documented case-insensitivity rule.
     */
    public TenantPojo findByVerificationToken(String token) {
        return em.createQuery(
                        "SELECT t FROM TenantPojo t WHERE t.verificationToken = :token", TenantPojo.class)
                .setParameter("token", token)
                .getResultStream()
                .findFirst()
                .orElse(null);
    }

    /**
     * Every tenant but the reserved platform row, newest first (C8) — the platform's own
     * list, matching the mock's {@code [...store.tenants].sort((a, b) => a.createdAt <
     * b.createdAt ? 1 : -1)}. {@code SUPER_ADMIN} is the only caller: every tenant-scoped
     * role has exactly one tenant and reaches it through the session, never this method.
     */
    public List<TenantPojo> list() {
        return em.createQuery(
                        "SELECT t FROM TenantPojo t WHERE t.platform = false ORDER BY t.createdAt DESC",
                        TenantPojo.class)
                .getResultList();
    }

    /**
     * How many products this tenant's catalogue holds, active or not — one of the row
     * counts {@code GET /api/tenants} shows so a suspension is an informed decision
     * rather than a guess (mirrors the frontend's {@code withCounts}).
     *
     * <p><b>The filter has to come off for this one.</b> {@link ProductPojo} carries
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
                            "SELECT count(p) FROM ProductPojo p WHERE p.tenantId = :tenantId",
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
                            "SELECT count(o) FROM PosOrderPojo o WHERE o.tenantId = :tenantId",
                            Long.class)
                    .setParameter("tenantId", tenantId)
                    .getSingleResult();
        } finally {
            session.enableFilter(TenantContext.FILTER_NAME)
                    .setParameter(TenantContext.PARAM_NAME, TenantContext.NO_TENANT);
        }
    }

    /**
     * Same as {@link #productCount}, batched into one {@code GROUP BY} for the whole
     * tenant list — the fix for {@code TenantService.list}'s {@code 3N+1} (peer-review
     * Phase 1): one query for every tenant on the page instead of one call per tenant.
     * Still needs the identical disable/re-enable dance {@link #productCount} does —
     * this reads {@link ProductPojo} on behalf of a {@code SUPER_ADMIN} with no tenant of
     * its own, whether it's counting one tenant or every tenant on the page.
     *
     * <p>A tenant with zero products is simply absent from the result — {@code GROUP BY}
     * has nothing to group for it — so the caller reads this with
     * {@code getOrDefault(id, 0L)}.
     */
    @PlatformOperation
    public Map<Long, Long> productCountsByTenants(List<Long> tenantIds) {
        if (tenantIds.isEmpty()) {
            return Map.of();
        }
        Session session = em.unwrap(Session.class);
        session.disableFilter(TenantContext.FILTER_NAME);
        try {
            List<Object[]> rows = em.createQuery(
                            "SELECT p.tenantId, count(p) FROM ProductPojo p"
                                    + " WHERE p.tenantId IN :tenantIds GROUP BY p.tenantId",
                            Object[].class)
                    .setParameter("tenantIds", tenantIds)
                    .getResultList();
            return groupCounts(rows);
        } finally {
            session.enableFilter(TenantContext.FILTER_NAME)
                    .setParameter(TenantContext.PARAM_NAME, TenantContext.NO_TENANT);
        }
    }

    /** The order-count half of the same story — see {@link #productCountsByTenants}. */
    @PlatformOperation
    public Map<Long, Long> orderCountsByTenants(List<Long> tenantIds) {
        if (tenantIds.isEmpty()) {
            return Map.of();
        }
        Session session = em.unwrap(Session.class);
        session.disableFilter(TenantContext.FILTER_NAME);
        try {
            List<Object[]> rows = em.createQuery(
                            "SELECT o.tenantId, count(o) FROM PosOrderPojo o"
                                    + " WHERE o.tenantId IN :tenantIds GROUP BY o.tenantId",
                            Object[].class)
                    .setParameter("tenantIds", tenantIds)
                    .getResultList();
            return groupCounts(rows);
        } finally {
            session.enableFilter(TenantContext.FILTER_NAME)
                    .setParameter(TenantContext.PARAM_NAME, TenantContext.NO_TENANT);
        }
    }

    private Map<Long, Long> groupCounts(List<Object[]> rows) {
        Map<Long, Long> byTenant = new LinkedHashMap<>();
        for (Object[] row : rows) {
            byTenant.put((Long) row[0], (Long) row[1]);
        }
        return byTenant;
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
    public TenantPojo reference(Long id) {
        return em.getReference(TenantPojo.class, id);
    }

    /**
     * <b>Flushes on purpose</b> (C8), matching the fix C5 applied to
     * {@code VariantDao.insert} once uniqueness became something a request could race.
     * {@code TenantService.create} inserts this and an {@link com.pos.pojo.AppUserPojo} in
     * the same transaction; without the flush, a duplicate {@code code} raised at commit
     * arrives wrapped in a {@code TransactionSystemException} the
     * {@code ApiExceptionHandler} never sees. Flushing here keeps the failure inside the
     * call that caused it, still carrying the constraint name that turns it into a
     * field-level 400. Harmless before C8: nothing called this on a path a caller could
     * race until tenant creation existed.
     */
    public void insert(TenantPojo tenant) {
        em.persist(tenant);
        em.flush();
    }

    /**
     * Every tenant still {@code PENDING_VERIFICATION} whose token has already expired
     * as of {@code cutoff} — the candidate set {@code AbandonedTenantCleanupService}
     * deletes (peer-review Phase 1, net-new). Cross-tenant by nature, like {@link
     * #productCount}/{@link #orderCount}: the caller is a background job with no
     * tenant of its own, so this carries {@link PlatformOperation} for the same
     * reason {@code AppUserDao.countByEmail} does — see that method's Javadoc.
     *
     * <p><b>{@code FOR UPDATE}, not a plain read.</b> Locking each candidate row here,
     * before anything else in the same transaction touches it, is what closes the one
     * real race: a resend or a successful verify landing between this read and the
     * delete could otherwise resurrect a row this method already decided to remove.
     * A concurrent resend/verify's own write blocks on this lock until the cleanup
     * transaction commits or rolls back; if the delete commits first, that request
     * simply finds no such tenant afterward — the same generic "invalid or expired"
     * outcome it would already give a token that expired moments earlier.
     */
    @PlatformOperation
    public List<TenantPojo> findAbandonedPendingVerification(Instant cutoff) {
        return em.createQuery(
                        "SELECT t FROM TenantPojo t WHERE t.status = :status"
                                + " AND t.verificationExpiresAt < :cutoff",
                        TenantPojo.class)
                .setParameter("status", TenantStatus.PENDING_VERIFICATION)
                .setParameter("cutoff", cutoff)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getResultList();
    }

    /**
     * Removes a tenant row outright — <b>the only hard delete anywhere in this
     * codebase.</b> Contrast {@code prompts/database/constraints-and-indexes.md}'s
     * "Deleting a product/variant referenced by order or return history": that path
     * is permanently blocked, on purpose, because those rows carry real transaction
     * history. A {@code PENDING_VERIFICATION} tenant carries none — login is refused
     * before verification (`requirements.md` §9), so nothing can exist under it but
     * its own admin row — which is what makes this safe to have at all.
     *
     * <p>{@link com.pos.service.AbandonedTenantCleanupService} is this method's only
     * caller, and it must delete that admin row first via {@code
     * AppUserDao.deleteByTenant} — {@code fk_app_user_tenant} carries no {@code
     * ON DELETE CASCADE}, so this fails on the foreign key otherwise.
     */
    @PlatformOperation
    public void delete(TenantPojo tenant) {
        em.remove(tenant);
    }
}

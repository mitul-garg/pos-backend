package com.pos.dao;

import java.util.List;

import com.pos.pojo.AppUser;
import com.pos.pojo.Role;
import com.pos.util.PlatformOperation;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

/**
 * Persistence for {@code app_user} (C3).
 *
 * <p>Every lookup here takes the tenant explicitly, because <b>this DAO runs before the
 * tenant filter exists</b> in the request's life: authentication is what establishes
 * which tenant the caller is in, so it cannot itself be scoped by that answer. From C4
 * onwards it is the only DAO with that property — everywhere else the filter supplies
 * the tenant and no method signature mentions it (CONVENTIONS.md).
 *
 * <p><b>C8's user management pays the same price on purpose.</b> {@link AppUser} carries
 * no {@code @Filter} (see its class Javadoc), so {@code UserService} cannot lean on the
 * mechanism the way every other tenant-scoped service does — {@link #findByTenant} and
 * {@link #findInTenant} take the tenant explicitly and re-establish by hand exactly what
 * the filter would otherwise give for free.
 */
@Repository
public class AppUserDao {

    @PersistenceContext
    private EntityManager em;

    /**
     * The login lookup: {@code (tenantId, username)}, never username alone. That pair is
     * what {@code uk_user_tenant_username} makes unique, and it is why two stores can
     * each have an {@code admin}.
     *
     * <p>Returns {@code null} for an unknown username — a failed login, not an error.
     */
    public AppUser findByTenantAndUsername(Long tenantId, String username) {
        return em.createQuery(
                        "SELECT u FROM AppUser u JOIN FETCH u.tenant "
                                + "WHERE u.tenant.id = :tenantId AND lower(u.username) = :username",
                        AppUser.class)
                .setParameter("tenantId", tenantId)
                .setParameter("username", username)
                .getResultStream()
                .findFirst()
                .orElse(null);
    }

    /**
     * Loads a user and its tenant in one statement.
     *
     * <p>This is the hottest query in the application: it runs on <b>every authenticated
     * request</b>, because the session's status is re-checked per request rather than
     * only at login (backend-plan.md section 1, deferred obligation 5). The
     * {@code JOIN FETCH} is what keeps that one round trip instead of two —
     * {@code AppUser.tenant} is {@code LAZY}, and the caller always reads the tenant's
     * status.
     */
    public AppUser findWithTenant(Long id) {
        return em.createQuery(
                        "SELECT u FROM AppUser u JOIN FETCH u.tenant WHERE u.id = :id",
                        AppUser.class)
                .setParameter("id", id)
                .getResultStream()
                .findFirst()
                .orElse(null);
    }

    /**
     * A proxy for the caller's own user row, for stamping {@code cashier_id} on a new
     * order without a read (C6) — the same device as {@code TenantDao.reference}, and for
     * the identical reason: an {@code INSERT} only needs the parent's id, and the id here
     * is never anything but {@code AuthService.currentSession().getId()}, i.e. the JWT
     * subject. A caller-supplied {@code cashierId} must never reach this method.
     */
    public AppUser reference(Long id) {
        return em.getReference(AppUser.class, id);
    }

    /**
     * <b>Flushes on purpose</b>, matching the fix C5 applied to {@code VariantDao.insert}
     * once uniqueness became something a request could race. Without it, a duplicate
     * {@code (tenant_id, username)} raised at commit arrives wrapped in a
     * {@code TransactionSystemException} the {@code ApiExceptionHandler} never sees —
     * this keeps the failure inside the call that caused it, still carrying the
     * constraint name that turns it into a field-level 400. Harmless before C8: nothing
     * called this on a path a caller could race until user creation existed.
     */
    public void insert(AppUser user) {
        em.persist(user);
        em.flush();
    }

    /**
     * Every user in one tenant (C8) — the list {@code UserService} scopes by hand, since
     * there is no filter to lean on. Username order matches the mock's
     * {@code scopedRows(store.users)}, which preserves insertion order; sorted here
     * instead, since insertion order is not a property a database read otherwise
     * guarantees.
     */
    public List<AppUser> findByTenant(Long tenantId) {
        return em.createQuery(
                        "SELECT u FROM AppUser u WHERE u.tenant.id = :tenantId ORDER BY u.username",
                        AppUser.class)
                .setParameter("tenantId", tenantId)
                .getResultList();
    }

    /**
     * By id, <b>and</b> the tenant — the by-hand equivalent of what
     * {@code applyToLoadByKey} gives every other entity's {@code em.find()}. A plain
     * {@code em.find(AppUser.class, id)} would return another tenant's user quite
     * happily, since nothing here is filtered; this is the one query that stands in for
     * that protection, and every write in {@code UserService} goes through it.
     */
    public AppUser findInTenant(Long id, Long tenantId) {
        return em.createQuery(
                        "SELECT u FROM AppUser u WHERE u.id = :id AND u.tenant.id = :tenantId",
                        AppUser.class)
                .setParameter("id", id)
                .setParameter("tenantId", tenantId)
                .getResultStream()
                .findFirst()
                .orElse(null);
    }

    /**
     * How many <b>active</b> admins this tenant has right now — the count
     * {@code UserService.deactivate} checks before letting the last one go, so a store
     * can never lock itself out of its own account.
     */
    public long countActiveAdmins(Long tenantId) {
        return em.createQuery(
                        "SELECT count(u) FROM AppUser u WHERE u.tenant.id = :tenantId"
                                + " AND u.role = :role AND u.active = true",
                        Long.class)
                .setParameter("tenantId", tenantId)
                .setParameter("role", Role.ADMIN)
                .getSingleResult();
    }

    /**
     * How many users a tenant has, active or not — one of the row counts
     * {@code GET /api/tenants} shows a {@code SUPER_ADMIN} weighing a suspension (C8).
     *
     * <p>Needs no {@link PlatformOperation}-marked filter dance the way
     * {@code TenantDao.productCount}/{@code orderCount} do: {@link AppUser} was never
     * filtered in the first place, so there is nothing to disable. The reach is exactly
     * as cross-tenant in effect, though — this is only ever called once per store from
     * {@code TenantService}, never for the caller's own login — which is why it still
     * carries the marker; see {@link PlatformOperation}'s Javadoc.
     */
    @PlatformOperation
    public long countByTenant(Long tenantId) {
        return em.createQuery(
                        "SELECT count(u) FROM AppUser u WHERE u.tenant.id = :tenantId", Long.class)
                .setParameter("tenantId", tenantId)
                .getSingleResult();
    }
}

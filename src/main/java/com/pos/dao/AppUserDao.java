package com.pos.dao;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.pos.pojo.AppUserPojo;
import com.pos.pojo.enums.Role;
import com.pos.pojo.TenantPojo;
import com.pos.util.tenancy.PlatformOperation;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
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
 * <p><b>C8's user management pays the same price on purpose.</b> {@link AppUserPojo} carries
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
    public AppUserPojo findByTenantAndUsername(Long tenantId, String username) {
        return em.createQuery(
                        "SELECT u FROM AppUserPojo u JOIN FETCH u.tenant "
                                + "WHERE u.tenant.id = :tenantId AND lower(u.username) = :username",
                        AppUserPojo.class)
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
     * {@code AppUserPojo.tenant} is {@code LAZY}, and the caller always reads the tenant's
     * status.
     */
    public AppUserPojo findWithTenant(Long id) {
        return em.createQuery(
                        "SELECT u FROM AppUserPojo u JOIN FETCH u.tenant WHERE u.id = :id",
                        AppUserPojo.class)
                .setParameter("id", id)
                .getResultStream()
                .findFirst()
                .orElse(null);
    }

    /**
     * The resend-verification ownership check (C9) — confirms a
     * {@code { tenantCode, adminEmail }} pair actually names an admin in that tenant,
     * without ever revealing to the caller whether it did:
     * {@code TenantRegistrationService.resendVerification} sends the identical
     * acknowledgement either way. Case-insensitive, matching how tenant codes and
     * usernames are already compared elsewhere in this codebase — email addresses are
     * conventionally case-insensitive too.
     *
     * <p>Takes the tenant id explicitly, like every other {@code AppUserDao} lookup
     * (C4's exception — {@code AppUserPojo} carries no {@code @Filter}), and carries no
     * {@link com.pos.util.PlatformOperation} marker: unlike {@code countByTenant},
     * this reads one already-identified tenant's own users, not an aggregate across
     * every store, so it isn't cross-tenant reach in the sense the marker exists for.
     */
    public AppUserPojo findByTenantAndEmail(Long tenantId, String email) {
        return em.createQuery(
                        "SELECT u FROM AppUserPojo u WHERE u.tenant.id = :tenantId "
                                + "AND lower(u.email) = :email",
                        AppUserPojo.class)
                .setParameter("tenantId", tenantId)
                .setParameter("email", email)
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
    public AppUserPojo reference(Long id) {
        return em.getReference(AppUserPojo.class, id);
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
    public void insert(AppUserPojo user) {
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
    public List<AppUserPojo> findByTenant(Long tenantId) {
        return em.createQuery(
                        "SELECT u FROM AppUserPojo u WHERE u.tenant.id = :tenantId ORDER BY u.username",
                        AppUserPojo.class)
                .setParameter("tenantId", tenantId)
                .getResultList();
    }

    /**
     * By id, <b>and</b> the tenant — the by-hand equivalent of what
     * {@code applyToLoadByKey} gives every other entity's {@code em.find()}. A plain
     * {@code em.find(AppUserPojo.class, id)} would return another tenant's user quite
     * happily, since nothing here is filtered; this is the one query that stands in for
     * that protection, and every write in {@code UserService} goes through it.
     */
    public AppUserPojo findInTenant(Long id, Long tenantId) {
        return em.createQuery(
                        "SELECT u FROM AppUserPojo u WHERE u.id = :id AND u.tenant.id = :tenantId",
                        AppUserPojo.class)
                .setParameter("id", id)
                .setParameter("tenantId", tenantId)
                .getResultStream()
                .findFirst()
                .orElse(null);
    }

    /**
     * Locks the tenant row before {@link #countActiveAdmins} is trusted (C8) — the same
     * fix C7 needed for a check that is a {@code SUM}/aggregate rather than one row's
     * column, so an atomic conditional {@code UPDATE} has nothing to bind to
     * (CONVENTIONS.md, "Transactions"). Without it, two concurrent
     * {@code DELETE /api/users/{id}} calls against a tenant's last two active admins
     * could each read "2 active admins" before either writes, both pass the guard, and
     * the store is left with none — the identical read-then-write gap
     * {@code OrderDao.findForUpdate} closes for a return's returnable-quantity check.
     *
     * <p><b>The tenant row, not the two {@code AppUserPojo} rows being raced</b> — there is no
     * single row to lock the way a return locks one order, because the aggregate spans
     * every admin in the tenant and a concurrent {@code POST /api/users} could be adding
     * one mid-check. The tenant row is the one thing guaranteed to exist and common to
     * every write this guard could ever race against, mirroring
     * {@code TenantSequenceDao.next()}'s identical reasoning for taking the wider lock
     * over a narrower one that doesn't exist yet.
     *
     * <p><b>Must be the first read {@code UserService.deactivate} performs — before even
     * {@link #findInTenant}, not merely before {@link #countActiveAdmins}.</b> Found by
     * {@code LastAdminRaceIT}, not by reasoning: an earlier version locked only after
     * confirming the target was an {@code ADMIN}, which meant {@code findInTenant} ran
     * first. MySQL's REPEATABLE READ anchors every later plain {@code SELECT} in a
     * transaction to the snapshot taken at that transaction's <i>first</i> read; with
     * {@code findInTenant} as that first read, {@link #countActiveAdmins} kept answering
     * from a snapshot taken before this lock's wait, even after the wait ended — so two
     * racing deactivations both still read "2 active admins" and both succeeded, exactly
     * the bug this method exists to prevent. A locking read (this one) always reads the
     * latest committed row regardless of snapshot, which is what makes it safe to be
     * first; nothing else in the transaction has that property.
     *
     * <p>The consequence: {@code UserService.deactivate} pays for this lock on every
     * call, not only when the target turns out to be an {@code ADMIN} — there is no way
     * to know that without a read, and any read before this one reopens the bug. Cheap on
     * a low-throughput admin screen; the same trade {@code TenantSequenceDao.next()}
     * makes locking the whole tenant rather than a narrower row that doesn't exist yet.
     */
    public void lockTenant(Long tenantId) {
        em.find(TenantPojo.class, tenantId, LockModeType.PESSIMISTIC_WRITE);
    }

    /**
     * How many <b>active</b> admins this tenant has right now — the count
     * {@code UserService.deactivate} checks before letting the last one go, so a store
     * can never lock itself out of its own account. Call {@link #lockTenant} first.
     */
    public long countActiveAdmins(Long tenantId) {
        return em.createQuery(
                        "SELECT count(u) FROM AppUserPojo u WHERE u.tenant.id = :tenantId"
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
     * {@code TenantDao.productCount}/{@code orderCount} do: {@link AppUserPojo} was never
     * filtered in the first place, so there is nothing to disable. The reach is exactly
     * as cross-tenant in effect, though — this is only ever called once per store from
     * {@code TenantService}, never for the caller's own login — which is why it still
     * carries the marker; see {@link PlatformOperation}'s Javadoc.
     */
    @PlatformOperation
    public long countByTenant(Long tenantId) {
        return em.createQuery(
                        "SELECT count(u) FROM AppUserPojo u WHERE u.tenant.id = :tenantId", Long.class)
                .setParameter("tenantId", tenantId)
                .getSingleResult();
    }

    /**
     * Same as {@link #countByTenant}, batched into one {@code GROUP BY} for the whole
     * tenant list — the fix for {@code TenantService.list}'s {@code 3N+1} (peer-review
     * Phase 1): one query for every tenant on the page instead of one call per tenant.
     *
     * <p>A tenant with zero users is simply absent from the result — {@code GROUP BY}
     * has nothing to group for it — so the caller reads this with
     * {@code getOrDefault(id, 0L)}, the same shape {@code TenantDao.productCountsByTenants}
     * hands back.
     */
    @PlatformOperation
    public Map<Long, Long> countsByTenants(List<Long> tenantIds) {
        if (tenantIds.isEmpty()) {
            return Map.of();
        }
        List<Object[]> rows = em.createQuery(
                        "SELECT u.tenant.id, count(u) FROM AppUserPojo u"
                                + " WHERE u.tenant.id IN :tenantIds GROUP BY u.tenant.id",
                        Object[].class)
                .setParameter("tenantIds", tenantIds)
                .getResultList();

        Map<Long, Long> byTenant = new LinkedHashMap<>();
        for (Object[] row : rows) {
            byTenant.put((Long) row[0], (Long) row[1]);
        }
        return byTenant;
    }

    /**
     * How many users (any role, active or not) the <b>caller's own</b> tenant has,
     * <b>ever</b> — the second tier of {@code UserService.create}'s resource-creation
     * guardrail (peer-review Phase 0, made two-tier in Phase 1): a much higher, never-
     * reclaimed ceiling that exists purely to bound a create/deactivate abuse loop.
     * {@link #countActiveByOwnTenant} is the primary, reclaimable check.
     *
     * <p>The identical query to {@link #countByTenant}, deliberately not reused: that
     * method carries {@link PlatformOperation} because its only caller today is
     * {@code TenantService} weighing a suspension across every store, genuinely
     * cross-tenant reach. This one is called from {@code UserService.create} checking
     * the tenant the caller is already in — reusing {@code countByTenant} here would
     * make the marker lie for one of its two callers, and {@link PlatformOperation}'s
     * own Javadoc is explicit that the audit only works if it stays accurate. Small
     * deliberate duplication over a misleading marker.
     */
    public long countByOwnTenant(Long tenantId) {
        return em.createQuery(
                        "SELECT count(u) FROM AppUserPojo u WHERE u.tenant.id = :tenantId", Long.class)
                .setParameter("tenantId", tenantId)
                .getSingleResult();
    }

    /**
     * How many <b>active</b> users the caller's own tenant has right now — the primary
     * half of {@code UserService.create}'s resource-creation guardrail as of peer-review
     * Phase 1 (originally any-status; see {@link #countByOwnTenant}, now the second
     * tier). Deactivating a user who left frees a slot again, the same reclaimable shape
     * every other soft-delete in this codebase already has.
     *
     * <p>Not {@link #countActiveAdmins}: that counts one role for the last-admin guard,
     * this counts every role for the roster-size guardrail. Different questions that
     * happen to share a shape.
     */
    public long countActiveByOwnTenant(Long tenantId) {
        return em.createQuery(
                        "SELECT count(u) FROM AppUserPojo u WHERE u.tenant.id = :tenantId"
                                + " AND u.active = true",
                        Long.class)
                .setParameter("tenantId", tenantId)
                .getSingleResult();
    }

    /**
     * How many tenants (any status, ever) this email address has registered as an admin
     * for — {@code TenantRegistrationWriter.register}'s resource-creation guardrail
     * (peer-review Phase 0), checked before that method's inserts. <b>Cross-tenant by
     * nature</b> — there is no caller tenant at self-registration, and the whole point is
     * to count across every store this address has ever touched — so this carries
     * {@link PlatformOperation} like {@link #countByTenant}, for the same reason: it
     * reaches {@link AppUserPojo} rows outside any one tenant's own.
     *
     * <p>Case-insensitive, matching {@link #findByTenantAndEmail}: email addresses are
     * conventionally compared that way, and the caller passes an already-lowercased value
     * for the identical reason that method's Javadoc gives.
     */
    @PlatformOperation
    public long countByEmail(String email) {
        return em.createQuery(
                        "SELECT count(u) FROM AppUserPojo u WHERE lower(u.email) = :email", Long.class)
                .setParameter("email", email)
                .getSingleResult();
    }

    /**
     * Removes every {@code app_user} row for one tenant — {@code
     * AbandonedTenantCleanupService}'s one caller (peer-review Phase 1, net-new),
     * always run before {@code TenantDao.delete} on the same tenant, since {@code
     * fk_app_user_tenant} carries no cascade. A bulk statement rather than {@link
     * #findByTenant} plus a loop of individual removes — there is normally exactly
     * one row (the admin minted at registration), but nothing here assumes that.
     *
     * <p>Needs no filter dance for the reason {@link #countByTenant} already gives —
     * {@link AppUserPojo} was never filtered in the first place — but carries {@link
     * PlatformOperation} for the same reason that method does: the caller is a
     * background job with no tenant of its own, reaching every tenant's users, which
     * is exactly the cross-tenant reach the marker exists to flag.
     */
    @PlatformOperation
    public void deleteByTenant(Long tenantId) {
        em.createQuery("DELETE FROM AppUserPojo u WHERE u.tenant.id = :tenantId")
                .setParameter("tenantId", tenantId)
                .executeUpdate();
    }
}

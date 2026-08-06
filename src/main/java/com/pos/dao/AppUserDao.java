package com.pos.dao;

import com.pos.pojo.AppUser;
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

    public void insert(AppUser user) {
        em.persist(user);
    }
}

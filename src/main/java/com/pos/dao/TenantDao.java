package com.pos.dao;

import com.pos.pojo.Tenant;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

/**
 * Persistence for the tenant boundary itself (C3).
 *
 * <p><b>This DAO is deliberately outside tenant scoping</b>, and it is the only one that
 * ever will be. {@code tenant} carries no {@code tenant_id} column — it <i>is</i> the
 * discriminator — so the Hibernate filter arriving in C4 does not apply to it. Reading
 * a tenant row is therefore not a cross-tenant read; reading another tenant's
 * <i>contents</i> is, and that is what the filter prevents everywhere else.
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

    public void insert(Tenant tenant) {
        em.persist(tenant);
    }
}

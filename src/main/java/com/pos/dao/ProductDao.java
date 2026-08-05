package com.pos.dao;

import java.util.ArrayList;
import java.util.List;

import com.pos.pojo.Product;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Repository;

/**
 * Persistence for {@code product} (C4).
 *
 * <p><b>Read the signatures for what is missing.</b> Not one of them takes a
 * {@code tenantId}, and none of the JPQL below mentions {@code tenant_id} — the
 * {@code tenantFilter} on {@link Product} appends it to every statement here. That is the
 * upgrade over the frontend, where scoping was a helper you had to remember to call: a
 * query written in this class is scoped whether or not its author was thinking about
 * tenancy. It is also why this is the first DAO in the codebase with no tenant argument;
 * {@code TenantDao} and {@code AppUserDao} are the two permanent exceptions, and both have
 * a reason recorded on them.
 */
@Repository
public class ProductDao {

    @PersistenceContext
    private EntityManager em;

    /**
     * By primary key, and <b>safe only because {@code @FilterDef} sets
     * {@code applyToLoadByKey}</b>.
     *
     * <p>Hibernate filters do not apply to {@code find()} by default — that is the classic
     * way a filtered application still leaks, because every other query is scoped and this
     * one silently is not. Written as a plain {@code find} rather than a JPQL
     * {@code WHERE id = :id} on purpose: the JPQL form would be scoped by the ordinary
     * query path and would therefore keep passing if that flag were ever removed, hiding
     * the regression from the very test that exists to catch it.
     *
     * <p>Returns {@code null} for an id in another tenant, indistinguishable from one that
     * never existed. The caller turns both into the same 404.
     */
    public Product find(Long id) {
        return em.find(Product.class, id);
    }

    /**
     * One page of the catalogue, newest first.
     *
     * <p>{@code search} matches name or brand, {@code category} is exact, and both are
     * optional — a null means "no restriction" rather than "match null", which is why the
     * predicates are assembled rather than written as {@code (:x IS NULL OR ...)}. The
     * assembled form also keeps each generated statement to the columns it actually
     * filters on, so {@code idx_product_tenant_category} is usable.
     */
    public List<Product> list(String search, String category, boolean includeInactive,
                              int offset, int limit) {
        TypedQuery<Product> query = em.createQuery(
                "SELECT p FROM Product p" + where(search, category, includeInactive)
                        + " ORDER BY p.createdAt DESC, p.id DESC",
                Product.class);
        bind(query, search, category);
        return query.setFirstResult(offset).setMaxResults(limit).getResultList();
    }

    /** How many rows {@link #list} would return unpaged — the envelope's {@code total}. */
    public long count(String search, String category, boolean includeInactive) {
        TypedQuery<Long> query = em.createQuery(
                "SELECT count(p) FROM Product p" + where(search, category, includeInactive),
                Long.class);
        bind(query, search, category);
        return query.getSingleResult();
    }

    /**
     * The distinct categories present in this tenant's catalogue, for the filter dropdown.
     *
     * <p>Includes categories used only by deactivated products, matching
     * {@code productService.categories()} in the frontend, which maps over every scoped
     * row. Arguably a wart — the default list hides inactive products, so such a category
     * filters to nothing — but the mock is the contract until C5 revisits it.
     */
    public List<String> categories() {
        return em.createQuery(
                        "SELECT DISTINCT p.category FROM Product p "
                                + "WHERE p.category IS NOT NULL AND p.category <> '' "
                                + "ORDER BY p.category",
                        String.class)
                .getResultList();
    }

    private String where(String search, String category, boolean includeInactive) {
        List<String> conditions = new ArrayList<>();
        if (!includeInactive) {
            conditions.add("p.active = true");
        }
        if (category != null) {
            conditions.add("p.category = :category");
        }
        if (search != null) {
            conditions.add("(lower(p.name) LIKE :search OR lower(p.brand) LIKE :search)");
        }
        return conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
    }

    private void bind(TypedQuery<?> query, String search, String category) {
        if (category != null) {
            query.setParameter("category", category);
        }
        if (search != null) {
            query.setParameter("search", "%" + search + "%");
        }
    }
}

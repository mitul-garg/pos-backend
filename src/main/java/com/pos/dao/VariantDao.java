package com.pos.dao;

import java.util.List;

import com.pos.pojo.Variant;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

/**
 * Persistence for {@code variant} (C5).
 *
 * <p><b>No method here takes a tenant, and no query below mentions {@code tenant_id}</b> —
 * the {@code tenantFilter} on {@link Variant} appends it to every statement, exactly as it
 * does for {@code ProductDao}. That is what makes {@link #findByQrCode} safe: a label
 * printed in another store cannot resolve here, so it is indistinguishable from a code
 * that was never issued.
 *
 * <p><b>Every read {@code JOIN FETCH}es the product</b>, because every response is
 * enriched with the parent's name and GST slab ({@code VariantData}). The association is
 * {@code LAZY} — correct for the entity, since scoping happens on the column and eager
 * loading would make list queries N+1 — so the fetch is stated per query instead. Left
 * lazy, a 34-row catalogue list would be 35 statements.
 */
@Repository
public class VariantDao {

    /**
     * Both halves of the enrichment come from the parent, and the parent is filtered too
     * — so this join is scoped twice over. Only the root needs to be: a variant never
     * straddles a tenant boundary (its tenant is inherited from its product on insert),
     * which is an invariant the frontend's {@code seeds.test.js} asserts and this schema's
     * two {@code tenant_id} foreign keys make structural.
     */
    private static final String SELECT_WITH_PRODUCT =
            "SELECT v FROM Variant v JOIN FETCH v.product p";

    @PersistenceContext
    private EntityManager em;

    /**
     * By primary key, and safe only because the {@code @FilterDef} sets
     * {@code applyToLoadByKey} — see {@code ProductDao.find} for the full reasoning, and
     * do not rewrite it as JPQL to "fix" anything: the JPQL form would keep passing if
     * that flag were removed, hiding the regression from the test that catches it.
     */
    public Variant find(Long id) {
        return em.find(Variant.class, id);
    }

    /** The variants of one product, oldest first, inactive ones included. */
    public List<Variant> findByProduct(Long productId) {
        return em.createQuery(SELECT_WITH_PRODUCT + " WHERE p.id = :productId ORDER BY v.id",
                        Variant.class)
                .setParameter("productId", productId)
                .getResultList();
    }

    /**
     * <b>The POS hot path.</b> Resolves a scanned payload to exactly one variant, or to
     * nothing.
     *
     * <p>Matched exactly rather than case-insensitively: the value is machine-generated
     * and machine-read, so a case difference means the scan produced a different string,
     * not that an operator typed it differently.
     *
     * <p>Inactive variants still resolve, matching the mock. A deactivated SKU that is
     * still physically on a shelf has to scan to <i>something</i> — the client shows
     * "not sellable" rather than "unknown code", which is a different conversation with
     * the customer.
     */
    public Variant findByQrCode(String qrCode) {
        return em.createQuery(SELECT_WITH_PRODUCT + " WHERE v.qrCode = :qrCode", Variant.class)
                .setParameter("qrCode", qrCode)
                .getResultStream()
                .findFirst()
                .orElse(null);
    }

    /**
     * Manual-add search for the counter: active variants of active products, by product
     * name or brand, or the variant's own SKU or label.
     *
     * <p>Both {@code active} predicates are the mock's, and the second is the one worth
     * noticing — a variant of a deactivated product is not sellable even if the variant
     * row itself was never touched.
     */
    /**
     * Is this SKU already taken <b>in this tenant</b>? {@code excludeId} is the row being
     * edited, so a variant does not collide with itself.
     *
     * <p><b>Tenant-WIDE, not per product</b>, which is the mock's rule too after B4:
     * uniqueness is {@code (tenant_id, sku)}, so two different products in one store must
     * not share a SKU. Checking only the parent's siblings — the obvious reading of "is
     * this SKU free for this product?" — is too narrow and lets a duplicate through to the
     * database.
     *
     * <p>This is the <b>friendly</b> half of uniqueness, never the enforcing half. It is a
     * read and a write with a gap, and another transaction fits in the gap; the unique
     * index is what actually prevents the duplicate, and
     * {@code ApiExceptionHandler}'s constraint mapping turns the loser's violation into
     * the same 400 this produces for the uncontended case.
     */
    public boolean skuExists(String sku, Long excludeId) {
        return em.createQuery(
                        "SELECT count(v) FROM Variant v WHERE v.sku = :sku"
                                + " AND (:excludeId IS NULL OR v.id <> :excludeId)",
                        Long.class)
                .setParameter("sku", sku)
                .setParameter("excludeId", excludeId)
                .getSingleResult() > 0;
    }

    /**
     * Inserts, and <b>flushes on purpose</b>.
     *
     * <p>Without the flush the {@code INSERT} runs at commit, by which point the service
     * has returned and Spring has wrapped any constraint violation in a
     * {@code TransactionSystemException} thrown from the transaction interceptor. Flushing
     * here keeps the failure inside the call that caused it, where the exception still
     * names the constraint that rejected it and can become a field-level 400.
     *
     * <p>Nothing here sets {@code tenant_id}: it comes from the {@link Variant} handed in,
     * which the service stamped from the parent product. The filter appends to
     * {@code WHERE} clauses and an {@code INSERT} has none, so a write is scoped by
     * whoever builds the entity.
     */
    public void insert(Variant variant) {
        em.persist(variant);
        em.flush();
    }

    public List<Variant> search(String term, int limit) {
        return em.createQuery(SELECT_WITH_PRODUCT
                                + " WHERE v.active = true AND p.active = true"
                                + " AND (lower(p.name) LIKE :term OR lower(p.brand) LIKE :term"
                                + " OR lower(v.sku) LIKE :term OR lower(v.variantLabel) LIKE :term)"
                                + " ORDER BY p.name, v.variantLabel, v.id",
                        Variant.class)
                .setParameter("term", "%" + term + "%")
                .setMaxResults(limit)
                .getResultList();
    }
}

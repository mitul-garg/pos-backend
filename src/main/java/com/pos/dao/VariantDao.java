package com.pos.dao;

import java.util.List;

import com.pos.pojo.ProductPojo;
import com.pos.pojo.VariantPojo;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

/**
 * Persistence for {@code variant} (C5).
 *
 * <p><b>No method here takes a tenant, and no query below mentions {@code tenant_id}</b> —
 * the {@code tenantFilter} on {@link VariantPojo} appends it to every statement, exactly as it
 * does for {@code ProductDao}. That is what makes {@link #findByQrCode} safe: a label
 * printed in another store cannot resolve here, so it is indistinguishable from a code
 * that was never issued.
 *
 * <p><b>Every read joins the product</b>, because every response is enriched with the
 * parent's name and GST slab ({@code VariantData}). Peer-review Phase 2 replaced the old
 * {@code JOIN FETCH v.product} with an ad-hoc {@code JOIN ... ON} — {@code VariantPojo.product}
 * is no longer a navigable association — so reads now come back as a
 * {@link VariantWithProduct} tuple instead of an enriched entity. Same single SQL join,
 * same one round trip; left un-joined, a 34-row catalogue list would be 35 statements.
 */
@Repository
public class VariantDao {

    /**
     * A variant with its product, both loaded in the same round trip — the replacement for
     * the entity {@code JOIN FETCH} used to hand back directly, now that
     * {@code VariantPojo.product} is a plain id rather than a navigable association.
     */
    public record VariantWithProduct(VariantPojo variant, ProductPojo product) {
    }

    /**
     * Both halves of the enrichment come from the parent, and the parent is filtered too
     * — so this join is scoped twice over. Only the root needs to be: a variant never
     * straddles a tenant boundary (its tenant is inherited from its product on insert),
     * which is an invariant the frontend's {@code seeds.test.js} asserts and this schema's
     * two {@code tenant_id} foreign keys make structural.
     */
    private static final String SELECT_WITH_PRODUCT =
            "SELECT v, p FROM VariantPojo v JOIN ProductPojo p ON p.id = v.productId";

    @PersistenceContext
    private EntityManager em;

    /**
     * By primary key, and safe only because the {@code @FilterDef} sets
     * {@code applyToLoadByKey} — see {@code ProductDao.find} for the full reasoning, and
     * do not rewrite it as JPQL to "fix" anything: the JPQL form would keep passing if
     * that flag were removed, hiding the regression from the test that catches it.
     */
    public VariantPojo find(Long id) {
        return em.find(VariantPojo.class, id);
    }

    /**
     * A proxy for the row with this id, for stamping a foreign key without reading it —
     * same shape as {@code TenantDao.reference}/{@code AppUserDao.reference}. Issues no
     * SQL: the only thing an {@code INSERT} needs from the parent is its id, and that is
     * already in hand. {@code ReturnService.create}'s one caller passes an id already
     * resolved through a filtered read earlier in the same transaction — the matched
     * order line's own snapshotted {@code variantId}, never one taken straight from a
     * request.
     */
    public VariantPojo reference(Long id) {
        return em.getReference(VariantPojo.class, id);
    }

    /**
     * By primary key, product pre-fetched (C6). {@link #find} stays a plain {@code em.find}
     * for the reason on its own Javadoc; this is a second, JPQL, method for the one caller
     * that needs the product loaded up front — {@code OrderService}, building a line
     * snapshot from the product's name and GST slab. Ordinary-query scoping is exactly
     * what is wanted here, since this method has no by-id regression to hide.
     */
    public VariantWithProduct findWithProduct(Long id) {
        return em.createQuery(SELECT_WITH_PRODUCT + " WHERE v.id = :id", Object[].class)
                .setParameter("id", id)
                .getResultStream()
                .findFirst()
                .map(row -> new VariantWithProduct((VariantPojo) row[0], (ProductPojo) row[1]))
                .orElse(null);
    }

    /** The variants of one product, oldest first, inactive ones included. */
    public List<VariantWithProduct> findByProduct(Long productId) {
        return em.createQuery(SELECT_WITH_PRODUCT + " WHERE v.productId = :productId ORDER BY v.id",
                        Object[].class)
                .setParameter("productId", productId)
                .getResultStream()
                .map(row -> new VariantWithProduct((VariantPojo) row[0], (ProductPojo) row[1]))
                .toList();
    }

    /**
     * How many variants (active or not) one product has, <b>ever</b> — the second tier
     * of {@code VariantService.create}'s resource-creation guardrail (peer-review Phase
     * 0, made two-tier in Phase 1): a much higher, never-reclaimed ceiling that exists
     * purely to bound a create/deactivate abuse loop. {@link #countActiveByProduct} is
     * the primary, reclaimable check.
     *
     * <p>A count, not {@link #findByProduct}: that method fetches full rows with their
     * product joined, wasteful for a number this method exists specifically to avoid
     * pulling the rows at all. Still scoped by the tenant filter on {@code VariantPojo} like
     * every other query here, though a product id already resolved through
     * {@code ProductDao.find} can only ever belong to the caller's own tenant anyway.
     */
    public long countByProduct(Long productId) {
        return em.createQuery(
                        "SELECT count(v) FROM VariantPojo v WHERE v.productId = :productId", Long.class)
                .setParameter("productId", productId)
                .getSingleResult();
    }

    /**
     * How many <b>active</b> variants one product has right now — the primary half of
     * {@code VariantService.create}'s resource-creation guardrail as of peer-review
     * Phase 1 (originally any-status; see {@link #countByProduct}, now the second
     * tier). Deactivating a discontinued variant frees a slot again, the same
     * reclaimable shape every other soft-delete in this codebase already has.
     */
    public long countActiveByProduct(Long productId) {
        return em.createQuery(
                        "SELECT count(v) FROM VariantPojo v WHERE v.productId = :productId"
                                + " AND v.active = true",
                        Long.class)
                .setParameter("productId", productId)
                .getSingleResult();
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
    public VariantWithProduct findByQrCode(String qrCode) {
        return em.createQuery(SELECT_WITH_PRODUCT + " WHERE v.qrCode = :qrCode", Object[].class)
                .setParameter("qrCode", qrCode)
                .getResultStream()
                .findFirst()
                .map(row -> new VariantWithProduct((VariantPojo) row[0], (ProductPojo) row[1]))
                .orElse(null);
    }

    /**
     * Is this SKU already taken <b>in this tenant</b>? {@code excludeId} is the row being
     * edited, so a variant does not collide with itself.
     *
     * <p><b>Tenant-wide, not per product</b>, which is the mock's rule too after B4:
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
                        "SELECT count(v) FROM VariantPojo v WHERE v.sku = :sku"
                                + " AND (:excludeId IS NULL OR v.id <> :excludeId)",
                        Long.class)
                .setParameter("sku", sku)
                .setParameter("excludeId", excludeId)
                .getSingleResult() > 0;
    }

    /**
     * By SKU, product pre-fetched. {@code DevSeeder}'s only caller: seeded orders
     * (C6) reference variants by the same stable SKU literals the variant fixtures use,
     * rather than by an id that only exists after that variant has been inserted.
     */
    public VariantWithProduct findBySku(String sku) {
        return em.createQuery(SELECT_WITH_PRODUCT + " WHERE v.sku = :sku", Object[].class)
                .setParameter("sku", sku)
                .getResultStream()
                .findFirst()
                .map(row -> new VariantWithProduct((VariantPojo) row[0], (ProductPojo) row[1]))
                .orElse(null);
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
     * <p>Nothing here sets {@code tenant_id}: it comes from the {@link VariantPojo} handed in,
     * which the service stamped from the parent product. The filter appends to
     * {@code WHERE} clauses and an {@code INSERT} has none, so a write is scoped by
     * whoever builds the entity.
     */
    public void insert(VariantPojo variant) {
        em.persist(variant);
        em.flush();
    }

    /**
     * The hard stock check (C6) — one atomic statement rather than a read, a check and a
     * write with a gap between them. Two terminals racing the last unit both read
     * {@code stockQuantity = 1} under MySQL's default REPEATABLE READ (a plain
     * {@code SELECT} is a non-locking snapshot, so both seeing "1 left" is legal, not a
     * bug); this instead asks the database to decrement <b>and</b> check in one round
     * trip, and the row count <b>is</b> the availability answer — zero means "someone got
     * there first", not "nothing happened".
     *
     * <p><b>A bulk statement, and therefore not scoped by the tenant filter</b> — it
     * appends to {@code WHERE} clauses on ordinary queries, and this is neither: it names
     * no entity alias for Hibernate to filter. Safe anyway, because every caller reaches
     * this with an id already resolved through a filtered read ({@link #find} or
     * {@link #findWithProduct}) earlier in the same transaction — the id in hand is
     * already proven to belong to the caller's tenant, the same reasoning
     * {@code TenantSequenceDao.next} relies on for the {@code TenantPojo} it locks. This
     * method must never be called with an id taken from anywhere else.
     *
     * @return {@code true} if a row was decremented, {@code false} if stock was
     * insufficient (or the id no longer resolves) — the caller's rejection signal.
     */
    public boolean decrementStock(Long variantId, int quantity) {
        int updated = em.createQuery(
                        "UPDATE VariantPojo v SET v.stockQuantity = v.stockQuantity - :quantity"
                                + " WHERE v.id = :id AND v.stockQuantity >= :quantity")
                .setParameter("quantity", quantity)
                .setParameter("id", variantId)
                .executeUpdate();
        return updated > 0;
    }

    /**
     * Stock restore (C7) — the mirror of {@link #decrementStock}. Unconditional: unlike
     * a sale, a return has no "insufficient" case to reject, so there is nothing to
     * check, only the same atomic increment for the same reason — a read, an add and a
     * write would lose an update racing against a concurrent sale's decrement on the
     * same row.
     *
     * <p>Bulk JPQL again, and therefore not scoped by the tenant filter, safe for the
     * identical reason {@link #decrementStock} is: every caller reaches this with a
     * {@code variantId} already resolved through a filtered read earlier in the same
     * transaction — the original order line's own variant, in {@code ReturnService}'s
     * case.
     */
    public void restoreStock(Long variantId, int quantity) {
        em.createQuery("UPDATE VariantPojo v SET v.stockQuantity = v.stockQuantity + :quantity"
                        + " WHERE v.id = :id")
                .setParameter("quantity", quantity)
                .setParameter("id", variantId)
                .executeUpdate();
    }

    /**
     * Manual-add search for the counter: active variants of active products, by product
     * name or brand, or the variant's own SKU or label.
     *
     * <p>Both {@code active} predicates are the mock's, and the second is the one worth
     * noticing — a variant of a deactivated product is not sellable even if the variant
     * row itself was never touched.
     */
    public List<VariantWithProduct> search(String term, int limit) {
        return em.createQuery(SELECT_WITH_PRODUCT
                                + " WHERE v.active = true AND p.active = true"
                                + " AND (lower(p.name) LIKE :term OR lower(p.brand) LIKE :term"
                                + " OR lower(v.sku) LIKE :term OR lower(v.variantLabel) LIKE :term)"
                                + " ORDER BY p.name, v.variantLabel, v.id",
                        Object[].class)
                .setParameter("term", "%" + term + "%")
                .setMaxResults(limit)
                .getResultStream()
                .map(row -> new VariantWithProduct((VariantPojo) row[0], (ProductPojo) row[1]))
                .toList();
    }
}

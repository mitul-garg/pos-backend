package com.pos.service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.pos.config.AppProperties;
import com.pos.dao.ProductDao;
import com.pos.dao.TenantSequenceDao;
import com.pos.dao.VariantDao;
import com.pos.exception.NotFoundException;
import com.pos.exception.ValidationException;
import com.pos.model.ProductRefData;
import com.pos.model.VariantData;
import com.pos.model.VariantForm;
import com.pos.pojo.ProductPojo;
import com.pos.pojo.enums.SequenceKind;
import com.pos.pojo.enums.UnitOfMeasure;
import com.pos.pojo.VariantPojo;
import com.pos.util.MaxLength;
import com.pos.util.QrCodes;
import com.pos.util.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The catalogue's variant half (C5) — the port of
 * {@code frontend/src/services/variantService.js}. The read operations land here; writes
 * and QR generation follow in the same step.
 *
 * <p><b>{@link #lookupByQrCode} is the endpoint this whole design is for.</b> A variant is
 * what gets scanned, priced and stocked (requirements.md section 2), and a label printed
 * in one store must never resolve in another — a physical object can be carried between
 * tills in a way a URL cannot. It resolves inside the caller's tenant or not at all, and
 * "not at all" is the same answer as a code that was never issued.
 *
 * <p>Every method is {@code @Transactional}, including the read-only ones, for the reason
 * on {@code ProductService}: the filter lives on a Hibernate session, and a read outside a
 * transaction is an unscoped read.
 */
@Service
public class VariantService {

    /** Matches the frontend's {@code 'Variant not found'}. */
    static final String NOT_FOUND = "Variant not found";

    /** {@code variantService.search}'s default, and the frontend passes nothing else. */
    static final int DEFAULT_SEARCH_LIMIT = 8;

    /**
     * A ceiling on what one search can cost, not a page size. The counter's picker shows
     * a handful of rows; a caller asking for thousands is not using the feature.
     */
    static final int MAX_SEARCH_LIMIT = 50;

    /** All four verbatim from {@code domain/validators.js}'s {@code validateVariant}. */
    static final String MRP_REQUIRED = "MRP must be greater than 0";
    static final String PRICE_REQUIRED = "Selling price must be greater than 0";
    static final String PRICE_ABOVE_MRP = "Selling price cannot exceed MRP";
    static final String SKU_TAKEN = "SKU is already in use";

    /**
     * <b>Not in the frontend's validator</b>, and deliberately added here. The mock had no
     * {@code ck_variant_stock_not_negative} to answer to, so a negative quantity was
     * merely odd; against a real schema it is a constraint violation, and a 500 is the
     * wrong way to tell someone they typed a minus sign.
     */
    static final String STOCK_NEGATIVE = "Stock quantity cannot be negative";

    /**
     * Peer-review Phase 0 resource-creation guardrail: a durable ceiling on how many
     * variants one product can hold, not a time-windowed rate limit — see {@link #create}.
     */
    static final String TOO_MANY_VARIANTS = "This product has reached its variant limit";

    private final VariantDao variantDao;
    private final ProductDao productDao;
    private final TenantSequenceDao tenantSequenceDao;
    private final AppProperties appProperties;

    @Autowired
    public VariantService(VariantDao variantDao, ProductDao productDao,
                          TenantSequenceDao tenantSequenceDao, AppProperties appProperties) {
        this.variantDao = variantDao;
        this.productDao = productDao;
        this.tenantSequenceDao = tenantSequenceDao;
        this.appProperties = appProperties;
    }

    /**
     * {@code GET /api/products/{productId}/variants}, inactive rows included — this is the
     * admin management view, where deactivating and reactivating a variant has to be
     * possible.
     *
     * <p><b>An unknown or out-of-tenant {@code productId} answers an empty list, not a
     * 404.</b> That is the mock's behaviour and it is the right one here: the query is
     * scoped, so another store's product simply matches nothing, and answering 404 would
     * mean distinguishing "no such product" from "no variants yet" — which for a
     * cross-tenant id is exactly the distinction isolation exists to hide.
     */
    @Transactional(readOnly = true)
    public List<VariantData> listByProduct(Long productId) {
        TenantContext.requireTenant();
        return variantDao.findByProduct(productId).stream()
                .map(vp -> toData(vp.variant(), vp.product()))
                .toList();
    }

    /**
     * {@code GET /api/variants/lookup?qrCode=...} — the scan.
     *
     * <p><b>404 for an unknown code, and the frontend turns that back into {@code null}</b>
     * when the mock is swapped for HTTP in C9. The contract (requirements.md section 9)
     * says 404; the mock resolves to {@code null} and never throws, because a bad scan must
     * not break the checkout flow. Both are the same statement about the wire from opposite
     * sides of it.
     *
     * <p>A blank code takes the identical path. It is what an empty keyboard-wedge read
     * produces, and it is not a malformed request — it is a scan that found nothing.
     */
    @Transactional(readOnly = true)
    public VariantData lookupByQrCode(String qrCode) {
        TenantContext.requireTenant();

        String code = qrCode == null ? "" : qrCode.trim();
        VariantDao.VariantWithProduct found = code.isEmpty() ? null : variantDao.findByQrCode(code);
        if (found == null) {
            throw new NotFoundException(NOT_FOUND);
        }
        return toData(found.variant(), found.product());
    }

    /**
     * {@code GET /api/variants/search?q=...} — the operator-friendly form of "type the
     * code manually", since nobody memorises a QR payload.
     *
     * <p>Returns the same enriched shape as a scan, so a picked result reaches the cart
     * through the same path as a scanned one. A blank term returns nothing rather than
     * everything: the search box is empty far more often than it is full, and the
     * expensive answer is the wrong default.
     */
    @Transactional(readOnly = true)
    public List<VariantData> search(String term, int limit) {
        TenantContext.requireTenant();

        String needle = term == null ? "" : term.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return List.of();
        }
        int safeLimit = Math.min(Math.max(limit, 1), MAX_SEARCH_LIMIT);
        return variantDao.search(needle, safeLimit).stream()
                .map(vp -> toData(vp.variant(), vp.product()))
                .toList();
    }

    /**
     * {@code POST /api/products/{productId}/variants} — <b>the server mints the code</b>
     * (requirements.md section 6).
     *
     * <p>Three things happen in one transaction, and they have to: the QR sequence is
     * advanced under a row lock, the code is built from it, and the row is inserted. Split
     * them and the lock is released between reserving a number and using it, which is the
     * race {@code TenantSequenceDao} exists to prevent.
     *
     * <p><b>The variant inherits its parent's tenant rather than re-reading the
     * session</b>, which is the same rule a return inheriting its order's tenant will
     * follow in C7. The product was loaded through a filtered read, so the two are
     * identical by construction — but inheriting states the actual invariant: a variant
     * never belongs to a different store from its product, and nothing downstream has to
     * check that it doesn't.
     */
    @Transactional
    public VariantData create(Long productId, VariantForm form) {
        TenantContext.requireTenant();

        ProductPojo product = productDao.find(productId);
        if (product == null) {
            // The parent's message, not this service's: from the caller's side what was
            // not found is the product they addressed.
            throw new NotFoundException(ProductService.NOT_FOUND);
        }

        String sku = trimToNull(form.getSku());
        BigDecimal mrp = form.getMrp();
        BigDecimal sellingPrice = form.getSellingPrice();
        int stockQuantity = form.getStockQuantity() == null ? 0 : form.getStockQuantity();
        String variantLabel = trimToEmpty(form.getVariantLabel());
        validate(mrp, sellingPrice, stockQuantity, sku, null, variantLabel);

        Long tenantId = product.getTenantId();
        long sequence = tenantSequenceDao.next(SequenceKind.QR, tenantId);

        VariantPojo variant = new VariantPojo();
        variant.setTenantId(tenantId);
        variant.setProductId(product.getId());
        variant.setVariantLabel(variantLabel);
        variant.setAttributes(form.getAttributes());
        variant.setSku(sku == null ? QrCodes.fallbackSku(sequence) : sku);
        variant.setQrCode(QrCodes.payload(tenantId, sequence));
        variant.setMrp(mrp);
        variant.setSellingPrice(sellingPrice);
        variant.setStockQuantity(stockQuantity);
        variant.setUnitOfMeasure(form.getUnitOfMeasure() == null
                ? UnitOfMeasure.EACH
                : form.getUnitOfMeasure());
        variant.setActive(true);

        // Peer-review Phase 0 resource-creation guardrail -- a durable ceiling on how many
        // variants one product can hold, not tied to any one submitted field, so this is a
        // bare ValidationException like ProductService's/UserService's guardrails rather
        // than a field-level one.
        //
        // Two-tier as of Phase 1: the ACTIVE count is the real, reclaimable ceiling --
        // deactivating a discontinued variant frees a slot again. The lifetime count (any
        // status, ever) is a much higher pure abuse backstop that never resets -- the
        // entire reason a durable ceiling was chosen here over a time-windowed one, so a
        // create/deactivate loop must still hit a wall eventually.
        if (variantDao.countActiveByProduct(productId) >= appProperties.getProductMaxVariants()
                || variantDao.countByProduct(productId) >= appProperties.getProductMaxVariantsLifetime()) {
            throw new ValidationException(TOO_MANY_VARIANTS);
        }

        variantDao.insert(variant);
        return toData(variant, product);
    }

    /**
     * {@code PUT /api/variants/{id}} — a merge patch, validated on the merged row, exactly
     * like {@code ProductService.update}.
     *
     * <p>{@code qrCode} is not patchable: {@link VariantForm} has no such field, because
     * the payload belongs to the tenant's sequence rather than to the caller. Re-issuing
     * one is {@link #regenerateQrCode}.
     *
     * <p><b>Cascades up</b> (peer-review Phase 3): reactivating one variant of an inactive
     * product brings the product back too, the mirror of {@link #deactivate}'s own
     * cascade-up. Deactivating a variant here (rather than through
     * {@link #deactivate}) is checked the identical way. Both read the product's current
     * state before deciding whether there is anything to flip, so reactivating a variant
     * of an already-active product — the ordinary case — touches the product row not at
     * all.
     */
    @Transactional
    public VariantData update(Long id, VariantForm form) {
        TenantContext.requireTenant();

        VariantPojo variant = variantDao.find(id);
        if (variant == null) {
            throw new NotFoundException(NOT_FOUND);
        }
        ProductPojo product = productDao.find(variant.getProductId());

        BigDecimal mrp = form.getMrp() == null ? variant.getMrp() : form.getMrp();
        BigDecimal sellingPrice = form.getSellingPrice() == null
                ? variant.getSellingPrice()
                : form.getSellingPrice();
        int stockQuantity = form.getStockQuantity() == null
                ? variant.getStockQuantity()
                : form.getStockQuantity();
        String sku = form.getSku() == null ? variant.getSku() : trimToNull(form.getSku());
        String variantLabel = form.getVariantLabel() == null
                ? variant.getVariantLabel()
                : trimToEmpty(form.getVariantLabel());
        validate(mrp, sellingPrice, stockQuantity, sku, id, variantLabel);

        variant.setMrp(mrp);
        variant.setSellingPrice(sellingPrice);
        variant.setStockQuantity(stockQuantity);
        if (sku != null) {
            variant.setSku(sku);
        }
        variant.setVariantLabel(variantLabel);
        if (form.getAttributes() != null) {
            variant.setAttributes(form.getAttributes());
        }
        if (form.getUnitOfMeasure() != null) {
            variant.setUnitOfMeasure(form.getUnitOfMeasure());
        }
        if (form.getActive() != null) {
            if (form.getActive() && !product.isActive()) {
                // Cascade up: this variant was the reason (or one of the reasons) the
                // product read inactive, or the product was set inactive directly --
                // either way, reactivating any one variant brings it back.
                product.setActive(true);
            } else if (!form.getActive() && variant.isActive()
                    && variantDao.countActiveByProduct(variant.getProductId()) <= 1) {
                // Counted before the flip below, so this variant is still among the
                // active ones being compared -- the same "count before mutating" shape
                // UserService.deactivate's last-admin guard uses, not an off-by-one.
                product.setActive(false);
            }
            variant.setActive(form.getActive());
        }

        return toData(variant, product);
    }

    /**
     * {@code DELETE /api/variants/{id}} — soft, and idempotent.
     *
     * <p>The row survives because the label on the shelf does. A deactivated variant still
     * scans (see {@code VariantDao.findByQrCode}); it is the client that says "not
     * sellable" rather than "unknown code", which is a different conversation to have with
     * a customer. Order lines snapshot their prices, so history does not depend on this
     * row either way — but a returns lookup still resolves against it.
     *
     * <p><b>Cascades up</b> (peer-review Phase 3): deactivating the last active variant of
     * a product auto-deactivates the product too, rather than leaving a catalogue entry
     * reading "Active" with nothing under it actually sellable. A product with zero
     * variants never reaches this method at all, which is exactly the spec's zero-variant
     * exemption — it isn't a separate check here.
     */
    @Transactional
    public VariantData deactivate(Long id) {
        TenantContext.requireTenant();

        VariantPojo variant = variantDao.find(id);
        if (variant == null) {
            throw new NotFoundException(NOT_FOUND);
        }
        // Counted before the flip below, so this variant is still among the active
        // ones being compared -- the same "count before mutating" shape
        // UserService.deactivate's last-admin guard uses, not an off-by-one.
        boolean lastActiveVariant = variant.isActive()
                && variantDao.countActiveByProduct(variant.getProductId()) <= 1;
        variant.setActive(false);

        ProductPojo product = productDao.find(variant.getProductId());
        if (lastActiveVariant) {
            product.setActive(false);
        }
        return toData(variant, product);
    }

    /**
     * {@code POST /api/variants/{id}/qr-code} — issues a fresh payload for a variant whose
     * label was damaged or reprinted.
     *
     * <p><b>The old code stops resolving immediately</b>, which is the point and also the
     * hazard: any label already printed with it becomes unscannable. That is why this is
     * an explicit operation rather than a patchable field.
     *
     * <p>Takes the tenant's next sequence value rather than reusing the variant's own, so
     * the run never revisits a number a printed label might still carry.
     */
    @Transactional
    public VariantData regenerateQrCode(Long id) {
        TenantContext.requireTenant();

        VariantPojo variant = variantDao.find(id);
        if (variant == null) {
            throw new NotFoundException(NOT_FOUND);
        }
        Long tenantId = variant.getTenantId();
        long sequence = tenantSequenceDao.next(SequenceKind.QR, tenantId);
        variant.setQrCode(QrCodes.payload(tenantId, sequence));
        return toData(variant, productDao.find(variant.getProductId()));
    }

    /**
     * The port of {@code validateVariant()}, plus the stock rule the mock had no schema to
     * answer to, plus the length bounds it had no schema to answer to either (peer-review
     * Phase 1) — {@code variantLabel}/{@code sku} against {@code variant_label}/{@code sku}
     * (`prompts/database/schema.md`).
     *
     * <p>The price rules are ordered as the frontend orders them: a selling price of zero
     * is reported as "must be greater than 0" rather than as "cannot exceed MRP", because
     * one of those is the actual mistake.
     */
    private void validate(BigDecimal mrp, BigDecimal sellingPrice, int stockQuantity,
                          String sku, Long excludeId, String variantLabel) {
        Map<String, String> errors = new LinkedHashMap<>();

        boolean mrpPositive = isPositive(mrp);
        if (!mrpPositive) {
            errors.put("mrp", MRP_REQUIRED);
        }
        if (!isPositive(sellingPrice)) {
            errors.put("sellingPrice", PRICE_REQUIRED);
        } else if (mrpPositive && sellingPrice.compareTo(mrp) > 0) {
            errors.put("sellingPrice", PRICE_ABOVE_MRP);
        }
        if (stockQuantity < 0) {
            errors.put("stockQuantity", STOCK_NEGATIVE);
        }
        // Tenant-wide, not per product -- see VariantDao.skuExists. And friendly rather
        // than enforcing: the unique index is the referee, and ApiExceptionHandler maps
        // its verdict onto this same field and message.
        if (sku != null && variantDao.skuExists(sku, excludeId)) {
            errors.put("sku", SKU_TAKEN);
        }
        MaxLength.check(errors, "variantLabel", "Variant label", variantLabel, 120);
        MaxLength.check(errors, "sku", "SKU", sku, 64);

        if (!errors.isEmpty()) {
            throw new ValidationException(errors.values().iterator().next(), errors);
        }
    }

    private boolean isPositive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * The display name uses an em dash, matching {@code withProduct()} in the mock
     * character for character — it ends up on receipts and label prints, so "close enough"
     * would show up in print.
     */
    private VariantData toData(VariantPojo variant, ProductPojo product) {
        return new VariantData(
                variant.getId(),
                variant.getTenantId(),
                product.getId(),
                variant.getVariantLabel(),
                variant.getAttributes(),
                variant.getSku(),
                variant.getQrCode(),
                variant.getMrp(),
                variant.getSellingPrice(),
                variant.getStockQuantity(),
                variant.getUnitOfMeasure(),
                variant.isActive(),
                product.getName() + " — " + variant.getVariantLabel(),
                product.getTaxRatePercent(),
                product.getHsnCode(),
                new ProductRefData(product.getId(), product.getName(), product.getBrand()));
    }
}

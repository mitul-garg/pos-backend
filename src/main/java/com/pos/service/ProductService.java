package com.pos.service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.pos.config.AppProperties;
import com.pos.dao.ProductDao;
import com.pos.dao.TenantDao;
import com.pos.exception.NotFoundException;
import com.pos.exception.ValidationException;
import com.pos.model.PageData;
import com.pos.model.ProductData;
import com.pos.model.ProductForm;
import com.pos.pojo.Product;
import com.pos.util.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The catalogue's product half — read in C4, as the one scoped resource the spine was
 * proved against; written in C5. Variants and QR generation are {@code VariantService}.
 *
 * <p>The port of {@code frontend/src/services/productService.js}, minus the scoping: the
 * mock had to call {@code scopedRows()} / {@code findInTenant()} on every path, and here
 * there is no equivalent line anywhere, because {@code ProductDao}'s queries are filtered
 * beneath it. What remains of that helper is {@link TenantContext#requireTenant()}, which
 * is a guard and not a filter.
 *
 * <p><b>Every method is {@code @Transactional}, and that is not optional here.</b> The
 * tenant filter is enabled on a Hibernate session, and outside a transaction Spring hands
 * out a fresh {@code EntityManager} per call with no session to enable it on. A read that
 * escaped its transaction would be an unscoped read — see {@code prompts/c4-tenancy.md}.
 */
@Service
public class ProductService {

    /** Matches the frontend's {@code 'Product not found'} exactly, so C9 needs no change. */
    static final String NOT_FOUND = "Product not found";

    /**
     * The frontend asks for 200 and renders everything (backend-plan.md section 11). The
     * ceiling exists so a caller cannot ask for the whole table in one statement; it is
     * above the largest request the client actually makes, so nothing is truncated today.
     */
    static final int MAX_PAGE_SIZE = 200;

    /** Verbatim from {@code domain/validators.js}, so both halves fail identically. */
    static final String NAME_REQUIRED = "Name is required";

    /**
     * The GST slabs (requirements.md section 4), and the message the frontend shows for
     * anything else.
     *
     * <p>Compared with {@code compareTo} rather than {@code equals}: {@code BigDecimal}
     * equality is scale-sensitive, so {@code 5} and {@code 5.00} are unequal objects and
     * the same tax rate. A client sending either has sent a valid slab.
     */
    static final List<BigDecimal> TAX_RATES = List.of(
            BigDecimal.ZERO, BigDecimal.valueOf(5), BigDecimal.valueOf(12),
            BigDecimal.valueOf(18), BigDecimal.valueOf(28));

    static final String TAX_RATE_INVALID = "Tax rate must be one of 0, 5, 12, 18, 28%";

    /**
     * Peer-review Phase 0 resource-creation guardrail: a durable ceiling on catalogue
     * size, not a time-windowed rate limit — see {@link #create}.
     */
    static final String TOO_MANY_PRODUCTS = "This store has reached its product limit";

    private final ProductDao productDao;
    private final TenantDao tenantDao;
    private final AuthService authService;
    private final AppProperties appProperties;

    /**
     * Constructor injection, for the reason recorded on {@code AuthService}: a servlet-
     * context-only test has to be able to stub this without dragging in a database, and
     * field injection is applied even to a hand-built {@code @Bean}.
     */
    @Autowired
    public ProductService(ProductDao productDao, TenantDao tenantDao, AuthService authService,
                          AppProperties appProperties) {
        this.productDao = productDao;
        this.tenantDao = tenantDao;
        this.authService = authService;
        this.appProperties = appProperties;
    }

    @Transactional(readOnly = true)
    public PageData<ProductData> list(String search, String category, int page, int pageSize,
                                      boolean includeInactive) {
        TenantContext.requireTenant();

        String term = searchTerm(search);
        String exactCategory = trimToNull(category);
        int safePage = Math.max(page, 1);
        int safePageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);

        long total = productDao.count(term, exactCategory, includeInactive);
        List<ProductData> items = productDao
                .list(term, exactCategory, includeInactive,
                        (safePage - 1) * safePageSize, safePageSize)
                .stream()
                .map(this::toData)
                .toList();

        return new PageData<>(items, total, safePage, safePageSize);
    }

    /**
     * <b>The case C4 exists to get right.</b> An id belonging to another tenant does not
     * load, so it takes the same path as an id that was never issued and produces the same
     * 404 — never a 403, which would confirm the id is real somewhere else
     * (requirements.md section 13.3).
     */
    @Transactional(readOnly = true)
    public ProductData get(Long id) {
        TenantContext.requireTenant();

        Product product = productDao.find(id);
        if (product == null) {
            throw new NotFoundException(NOT_FOUND);
        }
        return toData(product);
    }

    @Transactional(readOnly = true)
    public List<String> categories() {
        TenantContext.requireTenant();
        return productDao.categories();
    }

    /**
     * {@code POST /api/products} (C5).
     *
     * <p><b>The tenant is stamped here, and it has to be.</b> The Hibernate filter appends
     * to {@code WHERE} clauses and an {@code INSERT} has none, so a write is scoped by
     * whoever builds the entity and by nothing else. The value comes from
     * {@code AuthService.currentSession()} — the token — and there is no code path by
     * which the request body could supply one: {@link ProductForm} has no such field.
     */
    @Transactional
    public ProductData create(ProductForm form) {
        TenantContext.requireTenant();

        String name = trimToNull(form.getName());
        BigDecimal taxRatePercent = form.getTaxRatePercent();
        validate(name, taxRatePercent);

        Product product = new Product();
        product.setTenant(tenantDao.reference(authService.currentSession().getTenantId()));
        product.setName(name);
        product.setBrand(trimToEmpty(form.getBrand()));
        product.setCategory(trimToEmpty(form.getCategory()));
        product.setDescription(trimToEmpty(form.getDescription()));
        product.setHsnCode(trimToEmpty(form.getHsnCode()));
        product.setTaxRatePercent(taxRatePercent);
        // Never from the form: a product is created active, and a caller asking for
        // isActive:false would be creating a row that no screen can reach.
        product.setActive(true);

        // Peer-review Phase 0 resource-creation guardrail -- a durable ceiling on tenant
        // catalogue size, not tied to any one submitted field, so this is a bare
        // ValidationException like UserService.LAST_ADMIN rather than a field-level one.
        // includeInactive=true, matching the guardrail's intent: soft-deleting a product
        // must not reopen headroom for the same tenant.
        if (productDao.count(null, null, true) >= appProperties.getTenantMaxProducts()) {
            throw new ValidationException(TOO_MANY_PRODUCTS);
        }

        productDao.insert(product);
        return toData(product);
    }

    /**
     * {@code PUT /api/products/{id}} — a <b>merge patch</b>, matching the mock's
     * {@code Object.assign(product, patch)}. An absent field is left alone, which is what
     * lets the frontend reactivate a product with {@code {"isActive": true}} and nothing
     * else.
     *
     * <p>Validation runs against the <i>merged</i> result rather than the request, for the
     * same reason. There is no {@code save} call at the end: the row was loaded inside
     * this transaction, so Hibernate's dirty checking writes it at commit.
     */
    @Transactional
    public ProductData update(Long id, ProductForm form) {
        TenantContext.requireTenant();

        Product product = productDao.find(id);
        if (product == null) {
            throw new NotFoundException(NOT_FOUND);
        }

        String name = form.getName() == null ? product.getName() : trimToNull(form.getName());
        BigDecimal taxRatePercent = form.getTaxRatePercent() == null
                ? product.getTaxRatePercent()
                : form.getTaxRatePercent();
        validate(name, taxRatePercent);

        product.setName(name);
        product.setTaxRatePercent(taxRatePercent);
        if (form.getBrand() != null) {
            product.setBrand(trimToEmpty(form.getBrand()));
        }
        if (form.getCategory() != null) {
            product.setCategory(trimToEmpty(form.getCategory()));
        }
        if (form.getDescription() != null) {
            product.setDescription(trimToEmpty(form.getDescription()));
        }
        if (form.getHsnCode() != null) {
            product.setHsnCode(trimToEmpty(form.getHsnCode()));
        }
        if (form.getActive() != null) {
            product.setActive(form.getActive());
        }

        return toData(product);
    }

    /**
     * {@code DELETE /api/products/{id}} — a <b>soft</b> delete, and the only kind there
     * is here. An order line snapshots what was sold, but the catalogue row is still what
     * a returns lookup and a receipt reprint resolve against, so removing it would rewrite
     * history. Deactivating hides it from the sell-side lists instead.
     *
     * <p>Idempotent: deactivating an already-inactive product is a no-op that answers 200,
     * not an error. Returns the row, as the mock does, so the client can update in place.
     */
    @Transactional
    public ProductData deactivate(Long id) {
        TenantContext.requireTenant();

        Product product = productDao.find(id);
        if (product == null) {
            throw new NotFoundException(NOT_FOUND);
        }
        product.setActive(false);
        return toData(product);
    }

    /**
     * The port of {@code validateProduct()} in {@code domain/validators.js}, field for
     * field and message for message.
     *
     * <p>Reports <b>every</b> broken field rather than the first, since the response
     * carries a field map and the form highlights each input. The top-level
     * {@code message} is the first of them, which is what the mock throws and what a
     * client with no field-level rendering shows.
     */
    private void validate(String name, BigDecimal taxRatePercent) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (name == null) {
            errors.put("name", NAME_REQUIRED);
        }
        if (taxRatePercent == null
                || TAX_RATES.stream().noneMatch(rate -> rate.compareTo(taxRatePercent) == 0)) {
            errors.put("taxRatePercent", TAX_RATE_INVALID);
        }
        if (!errors.isEmpty()) {
            throw new ValidationException(errors.values().iterator().next(), errors);
        }
    }

    /**
     * Mapped inside the transaction, which is what makes {@code product.getTenant()} safe
     * — the association is {@code LAZY}, and reading it after the transaction closed is a
     * {@code LazyInitializationException}. Only the id is read, which Hibernate answers
     * from the proxy without a second statement.
     */
    private ProductData toData(Product product) {
        return new ProductData(
                product.getId(),
                product.getTenant().getId(),
                product.getName(),
                product.getBrand(),
                product.getCategory(),
                product.getDescription(),
                product.getHsnCode(),
                product.getTaxRatePercent(),
                product.isActive(),
                product.getCreatedAt());
    }

    /**
     * Lower-cased, because the DAO matches it against {@code lower(name)} — a cashier
     * typing "AMUL" is searching for the same thing as one typing "amul".
     */
    private String searchTerm(String value) {
        String trimmed = trimToNull(value);
        return trimmed == null ? null : trimmed.toLowerCase(Locale.ROOT);
    }

    /**
     * An omitted filter and an empty one mean the same thing — no restriction. The
     * frontend sends {@code search: ''} and {@code category: ''} for "no filter" rather
     * than omitting the keys, so treating blank as a literal match would return nothing
     * on every fresh page load.
     *
     * <p>Case is left alone here: {@code category} is matched exactly, against values
     * stored as {@code Dairy} rather than {@code dairy}.
     */
    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    /**
     * The optional text fields, stored as {@code ""} rather than {@code NULL} when a form
     * leaves them empty — which is what the mock rows hold, so a React input bound to one
     * gets a string rather than a null and stays controlled. {@code ProductDao.categories}
     * excludes the empty string for the same reason.
     */
    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}

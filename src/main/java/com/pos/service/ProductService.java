package com.pos.service;

import java.util.List;
import java.util.Locale;

import com.pos.dao.ProductDao;
import com.pos.exception.NotFoundException;
import com.pos.model.PageData;
import com.pos.model.ProductData;
import com.pos.pojo.Product;
import com.pos.util.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reading the catalogue (C4) — the one scoped resource the spine is proved against.
 * Writes are C5, along with variants and QR generation.
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

    private final ProductDao productDao;

    /**
     * Constructor injection, for the reason recorded on {@code AuthService}: a servlet-
     * context-only test has to be able to stub this without dragging in a database, and
     * field injection is applied even to a hand-built {@code @Bean}.
     */
    @Autowired
    public ProductService(ProductDao productDao) {
        this.productDao = productDao;
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
}

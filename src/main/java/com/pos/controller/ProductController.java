package com.pos.controller;

import java.util.List;

import com.pos.model.PageData;
import com.pos.model.ProductData;
import com.pos.model.ProductForm;
import com.pos.service.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/products} — requirements.md section 9. Read in C4, written in C5.
 *
 * <p><b>The three writes are {@code ADMIN}-only, and the rule lives in
 * {@code SecurityConfig} rather than here.</b> Section 13.2 gives catalogue management to
 * an {@code ADMIN}; a {@code CASHIER} reads the same catalogue and changes none of it. A
 * URL rule keeps the whole role surface greppable in one file, which is the property that
 * makes "who can write to this?" answerable without reading every controller.
 *
 * <p><b>Note what none of these signatures take.</b> No {@code tenantId}, on any of them,
 * in any position — the paths are identical to the single-tenant contract precisely
 * because tenancy rides on the token rather than the URL. An endpoint here that accepted
 * one would be a bug, not a shortcut.
 */
@RestController
@RequestMapping("/api/products")
public class ProductController {

    @Autowired
    private ProductService productService;

    @Operation(summary = "List products",
               description = "One page of the caller's own catalogue, newest first. "
                       + "`search` matches product name or brand; `category` is exact. "
                       + "`includeInactive` surfaces soft-deleted rows for the admin "
                       + "management view and is off by default.")
    @GetMapping
    public PageData<ProductData> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        return productService.list(search, category, page, pageSize, includeInactive);
    }

    /**
     * Mapped before {@code /{id}} in this file for readability only — Spring prefers the
     * literal pattern over the templated one regardless of declaration order, so
     * {@code /api/products/categories} can never be read as an id.
     */
    @Operation(summary = "Catalogue categories",
               description = "Distinct categories present in the caller's own catalogue, "
                       + "sorted. A category used only by another store is not in it.")
    @GetMapping("/categories")
    public List<String> categories() {
        return productService.categories();
    }

    @Operation(summary = "Get one product",
               description = "404 if the id does not exist **or** belongs to another "
                       + "store — deliberately the same answer, since a 403 would confirm "
                       + "the id is real somewhere else.")
    @GetMapping("/{id}")
    public ProductData get(@PathVariable Long id) {
        return productService.get(id);
    }

    /**
     * No {@code @Valid}: {@link ProductForm} carries no constraints, because the same DTO
     * serves the merge-patch {@code PUT} where an absent field is legal. The service
     * validates the resulting row instead — see the form's Javadoc.
     */
    @Operation(summary = "Create a product",
               description = "ADMIN only. The tenant is stamped from the caller's token; a "
                       + "`tenantId` in the body is ignored. 400 with field → message for a "
                       + "blank name or a GST rate outside 0/5/12/18/28.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProductData create(@RequestBody ProductForm form) {
        return productService.create(form);
    }

    @Operation(summary = "Update a product",
               description = "ADMIN only, and a **merge patch**: fields absent from the body "
                       + "are left as they are, which is how `{\"isActive\": true}` "
                       + "reactivates a soft-deleted product. 404 for an id in another store.")
    @PutMapping("/{id}")
    public ProductData update(@PathVariable Long id, @RequestBody ProductForm form) {
        return productService.update(id, form);
    }

    @Operation(summary = "Deactivate a product",
               description = "ADMIN only. A soft delete — the row survives so receipts and "
                       + "returns still resolve it; it drops out of the default list. "
                       + "Idempotent, and answers with the updated product.")
    @DeleteMapping("/{id}")
    public ProductData deactivate(@PathVariable Long id) {
        return productService.deactivate(id);
    }
}

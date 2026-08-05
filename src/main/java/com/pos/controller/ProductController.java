package com.pos.controller;

import java.util.List;

import com.pos.model.PageData;
import com.pos.model.ProductData;
import com.pos.service.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/products} — the read half of requirements.md section 9 (C4). Create, update
 * and deactivate arrive with the rest of the catalogue in C5.
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
}

package com.pos.controller;

import java.util.List;

import com.pos.model.VariantData;
import com.pos.model.VariantForm;
import com.pos.service.VariantService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The variant endpoints from requirements.md section 9 (C5) — the read half.
 *
 * <p><b>No class-level {@code @RequestMapping}, because these paths are two shapes rather
 * than one prefix.</b> Listing a product's variants is a sub-resource of that product
 * ({@code /api/products/{productId}/variants}); a scan and a search address the variant
 * collection directly ({@code /api/variants/...}), since neither knows a product id — that
 * is the whole point of scanning. Splitting them across two controllers would separate
 * methods that share a service and a DTO for no gain.
 *
 * <p>{@code lookup} and {@code search} are literal patterns, so they can never be read as
 * {@code /{id}} when C5's writes add one — Spring prefers a literal over a template
 * regardless of declaration order, the same reason {@code /api/products/categories} is
 * safe.
 */
@RestController
public class VariantController {

    @Autowired
    private VariantService variantService;

    @Operation(summary = "Variants of one product",
               description = "Inactive variants included — this is the admin management "
                       + "view. A product id from another store matches nothing and answers "
                       + "an empty list rather than a 404, because 'no such product' and "
                       + "'no variants yet' must not be distinguishable across the boundary.")
    @GetMapping("/api/products/{productId}/variants")
    public List<VariantData> listByProduct(@PathVariable Long productId) {
        return variantService.listByProduct(productId);
    }

    /**
     * The hot path. Isolation here is physical rather than logical: a printed label can be
     * carried from one store to another in a way a URL cannot, and it must resolve in
     * neither.
     */
    @Operation(summary = "Resolve a scanned QR code",
               description = "Returns the one variant carrying this code, enriched with its "
                       + "product's name and GST slab so a cart line can be built without a "
                       + "second call. 404 for an unknown code **and** for a label printed "
                       + "in another store — deliberately the same answer.")
    @GetMapping("/api/variants/lookup")
    public VariantData lookupByQrCode(@RequestParam String qrCode) {
        return variantService.lookupByQrCode(qrCode);
    }

    @Operation(summary = "Search variants",
               description = "Active variants of active products, matched on product name "
                       + "or brand, or the variant's SKU or label. The operator-friendly "
                       + "form of typing a code by hand. A blank term returns nothing.")
    @GetMapping("/api/variants/search")
    public List<VariantData> search(
            @RequestParam String q,
            @RequestParam(defaultValue = "8") int limit) {
        return variantService.search(q, limit);
    }

    @Operation(summary = "Add a variant to a product",
               description = "ADMIN only. **The server generates and returns the QR code** "
                       + "from the store's own sequence — there is no way to supply one. "
                       + "`sku` is optional and is derived from the same sequence value "
                       + "when omitted. 404 for a product in another store.")
    @PostMapping("/api/products/{productId}/variants")
    @ResponseStatus(HttpStatus.CREATED)
    public VariantData create(@PathVariable Long productId, @RequestBody VariantForm form) {
        return variantService.create(productId, form);
    }

    @Operation(summary = "Update a variant",
               description = "ADMIN only, and a merge patch — `{\"isActive\": true}` "
                       + "reactivates one. `qrCode` is not among the fields: re-issuing a "
                       + "code is its own operation.")
    @PutMapping("/api/variants/{id}")
    public VariantData update(@PathVariable Long id, @RequestBody VariantForm form) {
        return variantService.update(id, form);
    }

    @Operation(summary = "Deactivate a variant",
               description = "ADMIN only. Soft, and idempotent. The variant keeps scanning "
                       + "— a label already on a shelf has to resolve to something — but "
                       + "drops out of search and is no longer sellable.")
    @DeleteMapping("/api/variants/{id}")
    public VariantData deactivate(@PathVariable Long id) {
        return variantService.deactivate(id);
    }

    /**
     * A {@code POST} rather than a {@code PUT}: it creates a new code rather than
     * replacing a known one with a supplied value, and it is not idempotent — calling it
     * twice consumes two sequence values and leaves two dead labels behind.
     */
    @Operation(summary = "Re-issue a variant's QR code",
               description = "ADMIN only. Takes the store's next sequence value and returns "
                       + "the updated variant. **The previous code stops resolving at "
                       + "once**, so any label already printed with it is dead — which is "
                       + "why this is an explicit operation rather than an editable field.")
    @PostMapping("/api/variants/{id}/qr-code")
    public VariantData regenerateQrCode(@PathVariable Long id) {
        return variantService.regenerateQrCode(id);
    }
}

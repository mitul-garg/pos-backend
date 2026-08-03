package com.pos.exception;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps to <b>400</b>, optionally carrying field → message so a form can highlight the
 * offending input (a duplicate SKU, {@code sellingPrice > mrp}).
 *
 * <p>Note this is the <i>friendly</i> half of uniqueness checking, not the enforcing
 * half. A service-level "does this SKU already exist?" is a read and a write with a
 * gap, and another transaction fits in the gap. The unique index is what actually
 * prevents the duplicate; this exception exists so the uncontended case gets a clean
 * field-level message instead of a raw constraint violation.
 */
public class ValidationException extends ApiException {

    private final Map<String, String> fields;

    public ValidationException(String message) {
        this(message, Map.of());
    }

    public ValidationException(String message, Map<String, String> fields) {
        super(message);
        this.fields = fields == null ? Map.of() : new LinkedHashMap<>(fields);
    }

    /** Single-field shorthand: {@code ValidationException.field("sku", "already in use")}. */
    public static ValidationException field(String field, String message) {
        return new ValidationException(message, Map.of(field, message));
    }

    public Map<String, String> getFields() {
        return fields;
    }
}

package com.pos.model;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The single error envelope. Every non-2xx response from this API has this shape, so
 * the frontend's HTTP layer needs exactly one error path.
 *
 * <pre>
 * { "message": "Selling price cannot exceed MRP",
 *   "fields":  { "sellingPrice": "must be at most the MRP" } }
 * </pre>
 *
 * <p>{@code fields} is omitted unless the failure is field-level.
 */
public class ApiError {

    private final String message;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private final Map<String, String> fields;

    public ApiError(String message) {
        this(message, Map.of());
    }

    public ApiError(String message, Map<String, String> fields) {
        this.message = message;
        this.fields = fields == null ? Map.of() : fields;
    }

    public String getMessage() {
        return message;
    }

    public Map<String, String> getFields() {
        return fields;
    }
}

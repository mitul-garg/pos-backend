package com.pos.util;

import java.util.Map;

import com.pos.exception.ValidationException;

/**
 * The one place a form field's length is checked against its backing column's
 * {@code VARCHAR} bound (peer-review Phase 1). Every {@code *Form} deliberately
 * carries no bean validation — {@code PUT} is a merge patch, and a field absent from
 * the body has to stay legal (CONVENTIONS.md) — so this is a service-layer check,
 * called explicitly from each service's own {@code validate()}, the same as every
 * other rule in this codebase.
 *
 * <p>Without it, an overlong field reaches MySQL's data-too-long error unmapped.
 * {@code ApiExceptionHandler} answers an unmapped constraint with a generic 500
 * deliberately — an unrecognized constraint is more likely a bug here than a bad
 * request — but a length overrun isn't that: it's an ordinary client mistake that
 * deserves the same clean 400 every other validation failure gets, the same
 * reasoning that put {@code ApiExceptionHandler.CONSTRAINT_FIELDS} there for unique
 * constraints.
 *
 * <p>Two shapes, matching the two ways a service already reports a bad field:
 * {@link #check} adds to an in-progress {@code errors} map for the "report every
 * broken field in one 400" style ({@code ProductService}, {@code VariantService},
 * {@code TenantService}, {@code TenantRegistrationWriter}); {@link #require} throws
 * immediately for the single-fault style ({@code ReturnService}, {@code
 * PaymentService}, {@code UserService}). Both are a no-op for {@code null} —
 * required-ness is a separate rule each caller already has its own message for.
 */
public final class MaxLength {

    private MaxLength() {
    }

    /**
     * Adds {@code label + " must be " + max + " characters or fewer"} to {@code
     * errors} under {@code field} if {@code value} overruns {@code max}.
     */
    public static void check(Map<String, String> errors, String field, String label, String value, int max) {
        if (value != null && value.length() > max) {
            errors.put(field, message(label, max));
        }
    }

    /** Same rule, thrown immediately rather than collected. */
    public static void require(String field, String label, String value, int max) {
        if (value != null && value.length() > max) {
            throw ValidationException.field(field, message(label, max));
        }
    }

    private static String message(String label, int max) {
        return label + " must be " + max + " characters or fewer";
    }
}

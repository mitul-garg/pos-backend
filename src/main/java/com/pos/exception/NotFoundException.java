package com.pos.exception;

/**
 * Maps to <b>404</b>.
 *
 * <p>Also the correct answer for a <i>cross-tenant</i> id, which is why isolation is
 * fail-closed: answering 403 there would confirm that the id exists in some other
 * tenant. From a caller's side, another tenant's data is indistinguishable from data
 * that was never there.
 */
public class NotFoundException extends ApiException {

    public NotFoundException(String message) {
        super(message);
    }

    /** e.g. {@code notFound("Product", 42)} — "Product 42 not found". */
    public static NotFoundException of(String entity, Object id) {
        return new NotFoundException(entity + " " + id + " not found");
    }
}

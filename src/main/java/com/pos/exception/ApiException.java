package com.pos.exception;

/**
 * Base class for the domain's own failures.
 *
 * <p>Services throw these; they never throw {@code ResponseStatusException} or anything
 * else servlet-shaped. Mapping to an HTTP status is
 * {@code ApiExceptionHandler}'s job — a service that knows about 404s cannot be
 * unit-tested without a servlet container, and cannot be reused off the web.
 */
public abstract class ApiException extends RuntimeException {

    protected ApiException(String message) {
        super(message);
    }
}

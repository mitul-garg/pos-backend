package com.pos.exception;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import com.pos.model.ApiError;
import jakarta.persistence.PersistenceException;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * The one place HTTP status codes are decided. Services throw domain exceptions and
 * stay ignorant of the web; this translates them, per {@code backend-plan.md} §6.
 *
 * <table>
 *   <caption>Status matrix</caption>
 *   <tr><td>Bad credentials, unknown or blank tenant code</td><td>401, one generic message</td></tr>
 *   <tr><td>Deactivated user, suspended tenant, wrong role</td><td>403</td></tr>
 *   <tr><td>Missing, or belonging to another tenant</td><td>404</td></tr>
 *   <tr><td>Validation failure</td><td>400, field → message</td></tr>
 * </table>
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /**
     * Unique keys a caller can actually violate, mapped to the field and the message the
     * pre-check in the service would have produced for the same mistake — so the
     * uncontended path and the raced one are indistinguishable on the wire.
     *
     * <p>Lower-cased keys: MySQL reports constraint names as declared, but the comparison
     * should not be what breaks if a name is ever declared in a different case.
     *
     * <p><b>Add a row here in the same change that adds a unique constraint a request can
     * trip.</b> Anything absent answers 500 rather than a misleading 400, which is the
     * right default — a constraint nobody mapped is a constraint nobody thought about.
     */
    private static final Map<String, String[]> CONSTRAINT_FIELDS = Map.of(
            "uk_variant_tenant_sku",
            new String[] { "sku", "SKU is already in use" },
            "uk_variant_tenant_qrcode",
            new String[] { "qrCode", "This QR code is already assigned to another variant" });

    /**
     * Never varied and never derived from the exception — see
     * {@link InvalidCredentialsException} for why the sameness is the point.
     */
    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ApiError> handleInvalidCredentials(InvalidCredentialsException ex) {
        log.debug("Authentication failed");
        return respond(HttpStatus.UNAUTHORIZED, InvalidCredentialsException.MESSAGE);
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ApiError> handleForbidden(ForbiddenException ex) {
        log.debug("Forbidden: {}", ex.getMessage());
        return respond(HttpStatus.FORBIDDEN, ex.getMessage());
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NotFoundException ex) {
        log.debug("Not found: {}", ex.getMessage());
        return respond(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ApiError> handleValidation(ValidationException ex) {
        log.debug("Validation failed: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError(ex.getMessage(), ex.getFields()));
    }

    /** {@code @Valid} on a Form DTO failed — unpack the binding result into fields. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleBindingFailure(MethodArgumentNotValidException ex) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            // putIfAbsent: a field with two failed constraints reports the first, rather
            // than whichever the validator happened to evaluate last.
            fields.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        ex.getBindingResult().getGlobalErrors()
                .forEach(error -> fields.putIfAbsent(error.getObjectName(), error.getDefaultMessage()));

        log.debug("Request body failed validation: {}", fields);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("Validation failed", fields));
    }

    /**
     * <b>The unique index rejecting a duplicate — the enforcing half of uniqueness</b>
     * (C5).
     *
     * <p>A service-level "is this SKU free?" is a read and a write with a gap, and another
     * transaction fits in the gap. The pre-check exists to give the uncontended case a
     * clean field-level message; <i>this</i> is what actually prevents the duplicate, and
     * mapping it to the same 400 with the same field and the same words is what makes the
     * race invisible to the caller rather than a different bug to report.
     *
     * <p>Both exception types are caught because which one arrives depends on where the
     * statement ran. A flush inside a service raises Hibernate's, wrapped in a
     * {@code PersistenceException}; Spring's translated form appears on other paths. The
     * constraint name is read from the Hibernate exception in the cause chain either way.
     *
     * <p><b>An unrecognised constraint is deliberately not turned into a 400.</b> Only the
     * ones listed in {@link #CONSTRAINT_FIELDS} have a field to blame and a sentence a user
     * can act on; anything else — a foreign key, a check constraint, something added later
     * and forgotten here — is a bug in this application rather than in the request, and
     * saying "400" about it would hide it. Those fall through to the 500 below, with the
     * stack trace logged.
     */
    @ExceptionHandler({ DataIntegrityViolationException.class, PersistenceException.class })
    public ResponseEntity<ApiError> handleConstraintViolation(RuntimeException ex) {
        String constraint = constraintNameOf(ex);
        String[] fieldAndMessage = constraint == null
                ? null
                : CONSTRAINT_FIELDS.get(constraint.toLowerCase(Locale.ROOT));

        if (fieldAndMessage == null) {
            log.error("Unmapped data integrity violation (constraint: {})", constraint, ex);
            return respond(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong");
        }

        log.debug("Constraint {} rejected a duplicate", constraint);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError(fieldAndMessage[1],
                        Map.of(fieldAndMessage[0], fieldAndMessage[1])));
    }

    /**
     * Walks to Hibernate's exception, which is the only one that knows which key was
     * violated. Spring's {@code DataIntegrityViolationException} wraps it, and a flush
     * raises it inside a {@code PersistenceException}.
     */
    private String constraintNameOf(Throwable ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return violation.getConstraintName();
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return null;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(HttpMessageNotReadableException ex) {
        log.debug("Unreadable request body: {}", ex.getMessage());
        return respond(HttpStatus.BAD_REQUEST, "Malformed request body");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParameter(MissingServletRequestParameterException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("Validation failed",
                        Map.of(ex.getParameterName(), "is required")));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("Validation failed",
                        Map.of(String.valueOf(ex.getName()), "is not a valid value")));
    }

    /**
     * No handler matched. Both variants occur: {@code NoHandlerFoundException} when the
     * DispatcherServlet finds no mapping (enabled in {@code WebAppInitializer}), and
     * {@code NoResourceFoundException} when a static-resource handler comes up empty.
     */
    @ExceptionHandler({ NoHandlerFoundException.class, NoResourceFoundException.class })
    public ResponseEntity<ApiError> handleNoHandler(Exception ex) {
        return respond(HttpStatus.NOT_FOUND, "No endpoint for this request");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return respond(HttpStatus.METHOD_NOT_ALLOWED,
                "Method " + ex.getMethod() + " is not supported for this endpoint");
    }

    /**
     * The backstop. Logs at ERROR with the stack trace and returns a message that says
     * nothing — an unexpected failure's detail is for the operator, not the caller,
     * since stack traces and SQL fragments leak schema and library versions.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong");
    }

    private ResponseEntity<ApiError> respond(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(new ApiError(message));
    }
}

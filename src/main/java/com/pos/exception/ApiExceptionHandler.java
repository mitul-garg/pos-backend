package com.pos.exception;

import java.util.LinkedHashMap;
import java.util.Map;

import com.pos.model.ApiError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

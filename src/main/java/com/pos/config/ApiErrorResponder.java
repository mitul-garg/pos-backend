package com.pos.config;

import java.io.IOException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pos.model.ApiError;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

/**
 * Writes an {@link ApiError} straight to the response, for failures that happen inside
 * the security filter chain.
 *
 * <p><b>Why this is not just {@code ApiExceptionHandler}.</b> A {@code @ControllerAdvice}
 * is a Spring MVC construct: it only sees exceptions the {@code DispatcherServlet}
 * dispatched. The security chain is a <i>servlet filter</i> and runs in front of the
 * servlet, so an unauthenticated request is rejected before MVC exists — the advice
 * never gets a chance. Left alone, Spring Security answers with its own empty-bodied 401
 * or an HTML error page, and the frontend's single error path finds nothing to read.
 *
 * <p>So the envelope is produced twice, from one class ({@code ApiError}) and by two
 * writers. The shape cannot drift because both serialize the same type;
 * {@code AuthControllerIT} asserts a filter-produced 401 and an advice-produced 401 have
 * the same body.
 *
 * <p>The {@code ObjectMapper} here is its own instance rather than {@code WebConfig}'s,
 * for the same structural reason: that bean lives in the servlet context, and this class
 * is wired into a <b>root</b>-context filter, which cannot see it. It is safe because
 * {@code ApiError} is two strings and a map — none of the mapper's configuration
 * (JavaTimeModule, null inclusion) applies to it.
 */
public class ApiErrorResponder {

    private final ObjectMapper mapper = new ObjectMapper();

    public void write(HttpServletResponse response, HttpStatus status, String message)
            throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getOutputStream(), new ApiError(message));
    }
}

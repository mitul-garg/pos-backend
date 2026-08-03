package com.pos.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import com.pos.exception.ForbiddenException;
import com.pos.exception.InvalidCredentialsException;
import com.pos.exception.NotFoundException;
import com.pos.exception.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pins the status matrix from {@code backend-plan.md} §6 before any endpoint depends
 * on it. Standalone setup with a throwaway controller, so nothing here is coupled to
 * the real API surface.
 */
class ApiExceptionHandlerTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void notFoundIs404() throws Exception {
        mvc.perform(get("/boom/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Product 42 not found"));
    }

    @Test
    void validationIs400WithFields() throws Exception {
        mvc.perform(get("/boom/invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Selling price cannot exceed MRP"))
                .andExpect(jsonPath("$.fields.sellingPrice").value("must be at most the MRP"));
    }

    @Test
    void forbiddenIs403AndMayBeSpecific() throws Exception {
        mvc.perform(get("/boom/forbidden"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("This store is suspended"));
    }

    /**
     * The security-relevant one. Whatever detail the caller supplied, the 401 body is
     * the fixed string — anything more would confirm which tenants or accounts exist.
     */
    @Test
    void invalidCredentialsIs401WithTheGenericMessage() throws Exception {
        mvc.perform(get("/boom/bad-credentials"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid tenant, username, or password"))
                .andExpect(jsonPath("$.fields").doesNotExist());
    }

    /** An unexpected failure must not leak its message, class name or stack trace. */
    @Test
    void unexpectedFailureIs500AndSaysNothing() throws Exception {
        mvc.perform(get("/boom/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Something went wrong"));
    }

    /** Plain errors omit the key entirely rather than sending an empty object. */
    @Test
    void omitsFieldsWhenThereAreNone() throws Exception {
        mvc.perform(get("/boom/not-found"))
                .andExpect(jsonPath("$.fields").doesNotExist());
    }

    @RestController
    @RequestMapping("/boom")
    static class ThrowingController {

        @GetMapping("/not-found")
        void notFound() {
            throw NotFoundException.of("Product", 42);
        }

        @GetMapping("/invalid")
        void invalid() {
            throw new ValidationException("Selling price cannot exceed MRP",
                    Map.of("sellingPrice", "must be at most the MRP"));
        }

        @GetMapping("/forbidden")
        void forbidden() {
            throw new ForbiddenException("This store is suspended");
        }

        @GetMapping("/bad-credentials")
        void badCredentials() {
            throw new InvalidCredentialsException();
        }

        @GetMapping("/unexpected")
        void unexpected() {
            throw new IllegalStateException("connection string: jdbc://secret@host/pos");
        }
    }
}

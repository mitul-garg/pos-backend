package com.pos.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.SQLException;
import java.util.Map;

import jakarta.persistence.PersistenceException;
import org.hibernate.exception.ConstraintViolationException;
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

    /**
     * <b>A duplicate that beat the service's pre-check answers exactly as one that did
     * not</b> (C5) — same status, same field, same words.
     *
     * <p>Tested here rather than only under a real race, because a race is a poor place to
     * learn whether the mapping works: whichever way the timing falls, this path is the one
     * that runs when the database is what catches the duplicate, and it is reachable in
     * production whether or not a test happens to provoke it.
     */
    @Test
    void aConstraintViolationBecomesTheSame400AsThePreCheck() throws Exception {
        mvc.perform(get("/boom/duplicate-sku"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("SKU is already in use"))
                .andExpect(jsonPath("$.fields.sku").value("SKU is already in use"));
    }

    /**
     * <b>The form MySQL 8 actually reports</b>, and the reason this case exists as well as
     * the one above: the first version of this test used the bare constraint name, passed,
     * and proved nothing. The real server says {@code variant.uk_variant_tenant_sku}, the
     * lookup missed every entry, and {@code VariantSequenceIT} caught it as a 500 where a
     * 400 was expected. A fixture that is not shaped like production is a fixture that
     * agrees with whatever the code does.
     */
    @Test
    void handlesTheTableQualifiedNameMySqlActuallyReports() throws Exception {
        mvc.perform(get("/boom/duplicate-sku-qualified"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.sku").value("SKU is already in use"));
    }

    /**
     * The deliberate non-mapping. A foreign key or a check constraint failing is a bug in
     * this application rather than in the request; answering 400 would blame the caller for
     * it and hide it from whoever could fix it.
     */
    @Test
    void anUnmappedConstraintStaysA500() throws Exception {
        mvc.perform(get("/boom/unmapped-constraint"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Something went wrong"))
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

        /**
         * Shaped like the real thing: Hibernate's exception, wrapped in a
         * {@code PersistenceException} the way a flush inside a service delivers it.
         */
        @GetMapping("/duplicate-sku")
        void duplicateSku() {
            throw new PersistenceException(new ConstraintViolationException(
                    "Duplicate entry", new SQLException("Duplicate entry"),
                    "uk_variant_tenant_sku"));
        }

        /** {@code Duplicate entry '1-RACE-0' for key 'variant.uk_variant_tenant_sku'}. */
        @GetMapping("/duplicate-sku-qualified")
        void duplicateSkuQualified() {
            throw new PersistenceException(new ConstraintViolationException(
                    "Duplicate entry", new SQLException("Duplicate entry"),
                    "variant.uk_variant_tenant_sku"));
        }

        @GetMapping("/unmapped-constraint")
        void unmappedConstraint() {
            throw new PersistenceException(new ConstraintViolationException(
                    "Cannot add or update a child row", new SQLException("FK failure"),
                    "fk_variant_product"));
        }
    }
}

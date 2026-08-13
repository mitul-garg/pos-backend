package com.pos.controller;

import java.math.BigDecimal;
import java.util.List;

import com.jayway.jsonpath.JsonPath;
import com.pos.config.MailConfig;
import com.pos.config.OpenApiConfig;
import com.pos.config.PersistenceConfig;
import com.pos.config.RecaptchaConfig;
import com.pos.config.RootConfig;
import com.pos.config.SecurityConfig;
import com.pos.config.WebConfig;
import com.pos.pojo.AppUserPojo;
import com.pos.pojo.ProductPojo;
import com.pos.pojo.enums.Role;
import com.pos.pojo.TenantPojo;
import com.pos.pojo.enums.TenantStatus;
import com.pos.pojo.enums.UnitOfMeasure;
import com.pos.pojo.VariantPojo;
import com.pos.util.tenancy.TenantContext;
import com.pos.util.TestIps;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesRegex;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The variant endpoints (C5), inside one store — the port of {@code variantService.js}.
 * Cross-tenant behaviour is {@code TenantIsolationIT}'s, including the scan, which is the
 * case that suite exists for.
 *
 * <p>What this suite is really about is the <b>identity of a variant</b>: the SKU and the
 * QR payload. Everything else is CRUD that looks like {@code ProductWriteIT}'s. Those two
 * fields are generated, unique per store and printed onto physical labels, so the cases
 * worth having are the ones that pin who is allowed to choose them (the server), what
 * happens when two rows want the same one (a field-level 400), and what re-issuing one
 * does to the old value (kills it).
 */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = {
        RootConfig.class, PersistenceConfig.class, SecurityConfig.class, MailConfig.class,
        RecaptchaConfig.class,
        WebConfig.class, OpenApiConfig.class })
@TestPropertySource("classpath:application-test.properties")
@Transactional
@DisplayName("/api/variants and /api/products/{id}/variants")
class VariantIT {

    private static final BCryptPasswordEncoder HASHER = new BCryptPasswordEncoder();
    private static final String ADMIN_HASH = HASHER.encode("admin123");
    private static final String CASHIER_HASH = HASHER.encode("cashier123");

    private static final long UNISSUED_ID = 9_999_999L;

    private static final String MILK_500 = """
            {"variantLabel":"500 ml","attributes":{"size":"500ml"},"sku":"AMUL-MILK-500",
             "mrp":30,"sellingPrice":29,"stockQuantity":40}
            """;

    @Autowired
    private WebApplicationContext context;

    @PersistenceContext
    private EntityManager em;

    private MockMvc mvc;

    private TenantPojo mgRoad;
    private Long milk;
    private Long retiredProduct;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        mgRoad = tenant("MG Road Store", "mg-road");
        user(mgRoad, "admin", ADMIN_HASH, Role.ADMIN);
        user(mgRoad, "cashier", CASHIER_HASH, Role.CASHIER);

        milk = product(mgRoad, "Amul Taaza Toned Milk", "Amul", "5.00", true);
        retiredProduct = product(mgRoad, "Discontinued Cola", "Generic", "28.00", false);

        em.flush();
        em.clear();
    }

    @AfterEach
    void clearThreadState() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Nested
    @DisplayName("POST /api/products/{productId}/variants")
    class Create {

        @Test
        @DisplayName("answers 201, enriched with the parent's name and GST slab")
        void createsAnEnrichedVariant() throws Exception {
            create(asAdmin(), milk, MILK_500)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.tenantId").value(id(mgRoad)))
                    .andExpect(jsonPath("$.productId").value(String.valueOf(milk)))
                    .andExpect(jsonPath("$.sku").value("AMUL-MILK-500"))
                    .andExpect(jsonPath("$.mrp").value(30))
                    .andExpect(jsonPath("$.sellingPrice").value(29))
                    .andExpect(jsonPath("$.stockQuantity").value(40))
                    .andExpect(jsonPath("$.attributes.size").value("500ml"))
                    .andExpect(jsonPath("$.isActive").value(true))
                    // The enrichment: everything a cart line needs, so a scan is one call.
                    .andExpect(jsonPath("$.name").value("Amul Taaza Toned Milk — 500 ml"))
                    .andExpect(jsonPath("$.taxRatePercent").value(5))
                    .andExpect(jsonPath("$.product.name").value("Amul Taaza Toned Milk"))
                    .andExpect(jsonPath("$.product.brand").value("Amul"));
        }

        @Test
        @DisplayName("mints a QR code carrying this store's id and its own sequence")
        void mintsAQrCode() throws Exception {
            create(asAdmin(), milk, MILK_500)
                    .andExpect(jsonPath("$.qrCode").value("POS-QR-" + id(mgRoad) + "-000001"));
            create(asAdmin(), milk, """
                    {"variantLabel":"1 L","sku":"AMUL-MILK-1000","mrp":58,"sellingPrice":56}
                    """)
                    .andExpect(jsonPath("$.qrCode").value("POS-QR-" + id(mgRoad) + "-000002"));
        }

        /**
         * The variant equivalent of {@code ProductWriteIT}'s body-tenantId case, and the
         * stronger of the two: a client that could name its own code could mint into
         * another store's run, since the tenant is a segment of the payload.
         */
        @Test
        @DisplayName("ignores a qrCode in the body — the server owns the value")
        void ignoresAQrCodeInTheBody() throws Exception {
            create(asAdmin(), milk, """
                    {"variantLabel":"500 ml","mrp":30,"sellingPrice":29,
                     "qrCode":"POS-QR-999-000001","tenantId":"999","id":"4242"}
                    """)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.qrCode").value("POS-QR-" + id(mgRoad) + "-000001"))
                    .andExpect(jsonPath("$.tenantId").value(id(mgRoad)))
                    .andExpect(jsonPath("$.id", not("4242")));
        }

        @Test
        @DisplayName("derives a SKU from the same sequence value when none is given")
        void derivesTheSkuFromTheSameSequenceValue() throws Exception {
            // The frontend's add form shows "auto-generated" and sends an empty string.
            // Sharing the sequence value keeps the two identifiers of one row in step.
            create(asAdmin(), milk, """
                    {"variantLabel":"500 ml","sku":"","mrp":30,"sellingPrice":29}
                    """)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.sku").value("SKU-000001"))
                    .andExpect(jsonPath("$.qrCode").value("POS-QR-" + id(mgRoad) + "-000001"));
        }

        @Test
        @DisplayName("defaults stock to 0, the unit to EACH, and the row to active")
        void appliesTheDefaults() throws Exception {
            create(asAdmin(), milk, """
                    {"variantLabel":"500 ml","mrp":30,"sellingPrice":29}
                    """)
                    .andExpect(jsonPath("$.stockQuantity").value(0))
                    .andExpect(jsonPath("$.unitOfMeasure").value("EACH"))
                    .andExpect(jsonPath("$.isActive").value(true));
        }

        @Test
        @DisplayName("rejects a selling price above MRP — MRP is the legal ceiling")
        void rejectsAPriceAboveMrp() throws Exception {
            create(asAdmin(), milk, """
                    {"variantLabel":"x","mrp":10,"sellingPrice":20}
                    """)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Selling price cannot exceed MRP"))
                    .andExpect(jsonPath("$.fields.sellingPrice").value("Selling price cannot exceed MRP"));
        }

        @Test
        @DisplayName("a zero price is 'must be greater than 0', not 'cannot exceed MRP'")
        void ordersThePriceRulesLikeTheFrontend() throws Exception {
            // Both rules are broken by {mrp: 0, sellingPrice: 0}; reporting the second
            // would send someone to fix the wrong field.
            create(asAdmin(), milk, """
                    {"variantLabel":"x","mrp":0,"sellingPrice":0,"stockQuantity":-3}
                    """)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fields.mrp").value("MRP must be greater than 0"))
                    .andExpect(jsonPath("$.fields.sellingPrice")
                            .value("Selling price must be greater than 0"))
                    .andExpect(jsonPath("$.fields.stockQuantity")
                            .value("Stock quantity cannot be negative"));
        }

        /**
         * Peer-review Phase 1: length bounds the mock had no schema to answer to. One
         * request covering both bounded fields on this form.
         */
        @Test
        @DisplayName("rejects overlong fields with a clean 400, not an unmapped 500")
        void rejectsOverlongFields() throws Exception {
            create(asAdmin(), milk, """
                    {"variantLabel":"%s","sku":"%s","mrp":30,"sellingPrice":29}
                    """.formatted("L".repeat(121), "S".repeat(65)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fields.variantLabel")
                            .value("Variant label must be 120 characters or fewer"))
                    .andExpect(jsonPath("$.fields.sku").value("SKU must be 64 characters or fewer"));
        }

        @Test
        @DisplayName("a SKU already used in this store is rejected on the sku field")
        void rejectsADuplicateSku() throws Exception {
            create(asAdmin(), milk, MILK_500).andExpect(status().isCreated());

            // Tenant-wide, not per product: a DIFFERENT product, same store, same SKU.
            create(asAdmin(), retiredProduct, MILK_500)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fields.sku").value("SKU is already in use"));
        }

        @Test
        @DisplayName("an unknown product id is 'Product not found'")
        void unknownProductIs404() throws Exception {
            // The parent's message: from the caller's side, what was missing is the
            // product they addressed.
            create(asAdmin(), UNISSUED_ID, MILK_500)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Product not found"));
        }

        /**
         * Peer-review Phase 0 resource-creation guardrail — {@code pos.product.maxVariants}
         * (50 in {@code application.properties}, not overridden for tests). Seeded by
         * direct persistence rather than 50 round trips; the real endpoint proves the
         * ceiling itself.
         */
        @Test
        @DisplayName("rejects a create once the product is at its variant-count ceiling")
        void rejectsACreateAtTheVariantCeiling() throws Exception {
            for (int i = 0; i < 50; i++) {
                variant(mgRoad, milk, "FILLER-" + i);
            }
            em.flush();
            em.clear();

            create(asAdmin(), milk, MILK_500)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("This product has reached its variant limit"))
                    .andExpect(jsonPath("$.fields").doesNotExist());
        }

        /**
         * Peer-review Phase 1: the two-tier guardrail's whole point, and the bug this
         * change fixes — before this, a product at the ceiling stayed locked out forever,
         * even after deactivating a variant, because the count included inactive rows.
         * Manually verified end-to-end against a real running app first (curl, products
         * case, same code shape) before writing this.
         */
        @Test
        @DisplayName("reclaims a slot at the same ceiling once a variant is deactivated")
        void reclaimsASlotOnceAVariantIsDeactivated() throws Exception {
            Long lastId = null;
            for (int i = 0; i < 50; i++) {
                lastId = variant(mgRoad, milk, "FILLER-" + i, true);
            }
            em.flush();
            em.clear();

            create(asAdmin(), milk, MILK_500).andExpect(status().isBadRequest());

            deleteVariant(asAdmin(), String.valueOf(lastId)).andExpect(status().isOk());

            create(asAdmin(), milk, MILK_500).andExpect(status().isCreated());
        }

        /**
         * Peer-review Phase 1: the second tier ({@code pos.product.maxVariantsLifetime},
         * 250) — the pure abuse backstop that exists precisely so a create/deactivate
         * loop can't spam unlimited rows now that the primary ceiling is reclaimable.
         * Active count stays comfortably under the 50 ceiling throughout; only the
         * lifetime count (active + inactive) reaches its own, separate limit.
         */
        @Test
        @DisplayName("still rejects at the lifetime ceiling, even with headroom on the active count")
        void rejectsAtTheLifetimeCeilingEvenBelowTheActiveCeiling() throws Exception {
            for (int i = 0; i < 10; i++) {
                variant(mgRoad, milk, "ACTIVE-FILLER-" + i, true);
            }
            for (int i = 0; i < 240; i++) {
                variant(mgRoad, milk, "RETIRED-FILLER-" + i, false);
            }
            em.flush();
            em.clear();

            create(asAdmin(), milk, MILK_500)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("This product has reached its variant limit"));
        }
    }

    @Nested
    @DisplayName("GET /api/variants/lookup — the scan")
    class Lookup {

        @Test
        @DisplayName("resolves a code to one enriched variant")
        void resolvesACode() throws Exception {
            String qrCode = qrCodeOf(createMilk500());

            lookup(asAdmin(), qrCode)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.sku").value("AMUL-MILK-500"))
                    .andExpect(jsonPath("$.name").value("Amul Taaza Toned Milk — 500 ml"))
                    .andExpect(jsonPath("$.taxRatePercent").value(5));
        }

        @Test
        @DisplayName("an unknown code, and a blank one, are both a plain 404")
        void unknownAndBlankAreBoth404() throws Exception {
            // Blank is what an empty keyboard-wedge read produces. It is a scan that found
            // nothing, not a malformed request, so it must not be a 400.
            lookup(asAdmin(), "POS-QR-" + id(mgRoad) + "-999999")
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Variant not found"));
            lookup(asAdmin(), "").andExpect(status().isNotFound());
            lookup(asAdmin(), "   ").andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("omitting the parameter entirely IS a malformed request")
        void aMissingParameterIs400() throws Exception {
            mvc.perform(get("/api/variants/lookup").header(AUTH, bearer(asAdmin())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fields.qrCode").value("is required"));
        }

        @Test
        @DisplayName("a deactivated variant still scans — the label is still on the shelf")
        void aDeactivatedVariantStillScans() throws Exception {
            String variantId = createMilk500();
            String qrCode = qrCodeOf(variantId);
            deleteVariant(asAdmin(), variantId).andExpect(status().isOk());

            // The client says "not sellable" rather than "unknown code", which is a
            // different conversation to have with a customer.
            lookup(asAdmin(), qrCode)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.isActive").value(false));
        }
    }

    @Nested
    @DisplayName("GET /api/variants/search")
    class Search {

        @Test
        @DisplayName("matches the product's name and brand, and the variant's SKU and label")
        void matchesAllFourFields() throws Exception {
            createMilk500();

            search(asAdmin(), "taaza").andExpect(jsonPath("$", hasSize(1)));
            search(asAdmin(), "AMUL").andExpect(jsonPath("$", hasSize(1)));
            search(asAdmin(), "milk-500").andExpect(jsonPath("$", hasSize(1)));
            search(asAdmin(), "500 ml").andExpect(jsonPath("$", hasSize(1)));
            search(asAdmin(), "nothing-like-this").andExpect(jsonPath("$", hasSize(0)));
        }

        @Test
        @DisplayName("a blank term returns nothing rather than everything")
        void aBlankTermReturnsNothing() throws Exception {
            createMilk500();
            // The search box is empty far more often than it is full, and the expensive
            // answer is the wrong default.
            search(asAdmin(), "").andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
        }

        @Test
        @DisplayName("skips a deactivated variant, and one whose PRODUCT is deactivated")
        void skipsWhatIsNotSellable() throws Exception {
            String variantId = createMilk500();
            search(asAdmin(), "taaza").andExpect(jsonPath("$", hasSize(1)));

            deleteVariant(asAdmin(), variantId);
            search(asAdmin(), "taaza").andExpect(jsonPath("$", hasSize(0)));

            // The second predicate is the one worth a case: a variant of a deactivated
            // product is not sellable even though its own row was never touched.
            update(asAdmin(), variantId, """
                    {"isActive":true}
                    """).andExpect(status().isOk());
            mvc.perform(delete("/api/products/" + milk).header(AUTH, bearer(asAdmin())))
                    .andExpect(status().isOk());
            search(asAdmin(), "taaza").andExpect(jsonPath("$", hasSize(0)));
        }

        @Test
        @DisplayName("honours limit")
        void honoursLimit() throws Exception {
            createMilk500();
            create(asAdmin(), milk, """
                    {"variantLabel":"1 L","sku":"AMUL-MILK-1000","mrp":58,"sellingPrice":56}
                    """).andExpect(status().isCreated());

            search(asAdmin(), "taaza").andExpect(jsonPath("$", hasSize(2)));
            mvc.perform(get("/api/variants/search")
                            .header(AUTH, bearer(asAdmin()))
                            .param("q", "taaza")
                            .param("limit", "1"))
                    .andExpect(jsonPath("$", hasSize(1)));
        }
    }

    @Nested
    @DisplayName("GET /api/products/{productId}/variants")
    class ListByProduct {

        @Test
        @DisplayName("includes inactive rows — this is the management view")
        void includesInactiveRows() throws Exception {
            String variantId = createMilk500();
            deleteVariant(asAdmin(), variantId);

            listByProduct(asAdmin(), milk)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].isActive").value(false));
        }

        @Test
        @DisplayName("an unknown product is an empty list, not a 404")
        void unknownProductIsEmpty() throws Exception {
            // Answering 404 would mean distinguishing "no such product" from "no variants
            // yet" -- which for a cross-tenant id is the distinction isolation hides.
            listByProduct(asAdmin(), UNISSUED_ID)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));
        }
    }

    @Nested
    @DisplayName("PUT, DELETE and re-issuing a code")
    class UpdateAndDelete {

        @Test
        @DisplayName("patches only what it is given")
        void patchesOnlyWhatItIsGiven() throws Exception {
            String variantId = createMilk500();

            update(asAdmin(), variantId, """
                    {"stockQuantity":99}
                    """)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.stockQuantity").value(99))
                    .andExpect(jsonPath("$.sku").value("AMUL-MILK-500"))
                    .andExpect(jsonPath("$.mrp").value(30))
                    .andExpect(jsonPath("$.sellingPrice").value(29))
                    .andExpect(jsonPath("$.variantLabel").value("500 ml"));
        }

        @Test
        @DisplayName("validates the merged row: a price patch above the STORED mrp fails")
        void validatesAgainstTheStoredMrp() throws Exception {
            // The request carries no mrp at all, so a per-request check would pass it.
            String variantId = createMilk500();

            update(asAdmin(), variantId, """
                    {"sellingPrice":45}
                    """)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fields.sellingPrice").value("Selling price cannot exceed MRP"));
        }

        @Test
        @DisplayName("a variant does not collide with its own SKU when edited")
        void doesNotCollideWithItself() throws Exception {
            String variantId = createMilk500();

            update(asAdmin(), variantId, """
                    {"sku":"AMUL-MILK-500","stockQuantity":12}
                    """)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.stockQuantity").value(12));
        }

        @Test
        @DisplayName("deactivate is soft and idempotent; isActive alone reactivates")
        void deactivateAndReactivate() throws Exception {
            String variantId = createMilk500();

            deleteVariant(asAdmin(), variantId).andExpect(jsonPath("$.isActive").value(false));
            deleteVariant(asAdmin(), variantId).andExpect(jsonPath("$.isActive").value(false));
            update(asAdmin(), variantId, """
                    {"isActive":true}
                    """).andExpect(jsonPath("$.isActive").value(true));
        }

        @Test
        @DisplayName("re-issuing a code advances the run and kills the old value")
        void regeneratingAdvancesTheRunAndKillsTheOldCode() throws Exception {
            String variantId = createMilk500();
            String original = qrCodeOf(variantId);

            String reissued = JsonPath.read(
                    mvc.perform(post("/api/variants/" + variantId + "/qr-code")
                                    .header(AUTH, bearer(asAdmin())))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.qrCode").value(matchesRegex(
                                    "POS-QR-" + id(mgRoad) + "-\\d{6}")))
                            .andReturn().getResponse().getContentAsString(),
                    "$.qrCode");

            assertNotEquals(original, reissued, "re-issuing must not return the same code");
            // The hazard, asserted rather than described: a label already printed with the
            // old value is now unscannable. That is why this is its own endpoint and not a
            // patchable field.
            lookup(asAdmin(), original).andExpect(status().isNotFound());
            lookup(asAdmin(), reissued).andExpect(status().isOk());
        }

        @Test
        @DisplayName("all three answer 404 for an id that never existed")
        void unknownIdIs404() throws Exception {
            String unknown = String.valueOf(UNISSUED_ID);

            update(asAdmin(), unknown, """
                    {"stockQuantity":1}
                    """)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Variant not found"));
            deleteVariant(asAdmin(), unknown).andExpect(status().isNotFound());
            mvc.perform(post("/api/variants/" + unknown + "/qr-code").header(AUTH, bearer(asAdmin())))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("the role rule")
    class RoleRules {

        @Test
        @DisplayName("a CASHIER cannot create, update, deactivate or re-issue a code")
        void aCashierCannotWrite() throws Exception {
            String variantId = createMilk500();
            String cashier = asCashier();

            create(cashier, milk, MILK_500).andExpect(status().isForbidden());
            update(cashier, variantId, """
                    {"stockQuantity":0}
                    """).andExpect(status().isForbidden());
            deleteVariant(cashier, variantId).andExpect(status().isForbidden());
            mvc.perform(post("/api/variants/" + variantId + "/qr-code").header(AUTH, bearer(cashier)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("but scans and searches — a till is useless without both")
        void aCashierCanScanAndSearch() throws Exception {
            String qrCode = qrCodeOf(createMilk500());

            lookup(asCashier(), qrCode).andExpect(status().isOk());
            search(asCashier(), "taaza").andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));
            listByProduct(asCashier(), milk).andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("the OpenAPI document")
    class Documentation {

        @Test
        @DisplayName("names the QR code as server-generated, since no form field says so")
        void documentsThatTheServerMintsTheCode() throws Exception {
            // The absence of a qrCode field in VariantForm is the contract, and an absence
            // documents nothing on its own -- a reader of the spec has to be told.
            mvc.perform(get("/api/openapi.json").header(AUTH, bearer(asAdmin())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.paths['/api/products/{productId}/variants'].post.description",
                            containsString("server generates")))
                    .andExpect(jsonPath("$.paths['/api/products/{productId}/variants'].post.responses.201")
                            .exists());
        }
    }

    // --- helpers -----------------------------------------------------------------

    private static final String AUTH = "Authorization";

    private ResultActions create(String token, Long productId, String body) throws Exception {
        return mvc.perform(post("/api/products/" + productId + "/variants")
                .header(AUTH, bearer(token))
                .contentType(APPLICATION_JSON)
                .content(body));
    }

    private ResultActions update(String token, String variantId, String body) throws Exception {
        return mvc.perform(put("/api/variants/" + variantId)
                .header(AUTH, bearer(token))
                .contentType(APPLICATION_JSON)
                .content(body));
    }

    private ResultActions deleteVariant(String token, String variantId) throws Exception {
        return mvc.perform(delete("/api/variants/" + variantId).header(AUTH, bearer(token)));
    }

    private ResultActions lookup(String token, String qrCode) throws Exception {
        return mvc.perform(get("/api/variants/lookup")
                .header(AUTH, bearer(token))
                .param("qrCode", qrCode));
    }

    private ResultActions search(String token, String term) throws Exception {
        return mvc.perform(get("/api/variants/search")
                .header(AUTH, bearer(token))
                .param("q", term));
    }

    private ResultActions listByProduct(String token, Long productId) throws Exception {
        return mvc.perform(get("/api/products/" + productId + "/variants")
                .header(AUTH, bearer(token)));
    }

    private String createMilk500() throws Exception {
        String response = create(asAdmin(), milk, MILK_500)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    /** A filter expression always yields an array, even when it matches one row. */
    private String qrCodeOf(String variantId) throws Exception {
        String response = listByProduct(asAdmin(), milk).andReturn().getResponse().getContentAsString();
        List<String> codes = JsonPath.read(response, "$[?(@.id == '" + variantId + "')].qrCode");
        return codes.get(0);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private String id(TenantPojo tenant) {
        return String.valueOf(tenant.getId());
    }

    private String asAdmin() throws Exception {
        return tokenFor("admin", "admin123");
    }

    private String asCashier() throws Exception {
        return tokenFor("cashier", "cashier123");
    }

    private String tokenFor(String username, String password) throws Exception {
        String body = """
                {"tenantCode":"mg-road","username":"%s","password":"%s"}
                """.formatted(username, password);
        String response = mvc.perform(post("/api/auth/login").with(TestIps.remoteAddr(TestIps.fresh())).contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        SecurityContextHolder.clearContext();
        TenantContext.clear();
        return JsonPath.read(response, "$.token");
    }

    private TenantPojo tenant(String name, String code) {
        TenantPojo tenant = new TenantPojo();
        tenant.setName(name);
        tenant.setCode(code);
        tenant.setStatus(TenantStatus.ACTIVE);
        tenant.setPlatform(false);
        em.persist(tenant);
        return tenant;
    }

    private void user(TenantPojo tenant, String username, String passwordHash, Role role) {
        AppUserPojo user = new AppUserPojo();
        user.setTenantId(tenant.getId());
        user.setUsername(username);
        user.setPasswordHash(passwordHash);
        user.setDisplayName(username);
        user.setRole(role);
        user.setActive(true);
        em.persist(user);
    }

    private Long product(TenantPojo tenant, String name, String brand, String taxRate, boolean active) {
        ProductPojo product = new ProductPojo();
        product.setTenantId(tenant.getId());
        product.setName(name);
        product.setBrand(brand);
        product.setCategory("Dairy");
        product.setTaxRatePercent(new BigDecimal(taxRate));
        product.setActive(active);
        em.persist(product);
        return product.getId();
    }

    /** Fast-path fixture for the variant-ceiling test — 50 of these beats 50 round trips. */
    private void variant(TenantPojo tenant, Long productId, String sku) {
        variant(tenant, productId, sku, true);
    }

    /**
     * Same, with the active flag exposed and the id returned — the two-tier guardrail
     * tests (peer-review Phase 1) need inactive rows that count toward the lifetime
     * ceiling without counting toward the active one, and a specific id to deactivate
     * through the real endpoint afterward.
     */
    private Long variant(TenantPojo tenant, Long productId, String sku, boolean active) {
        VariantPojo variant = new VariantPojo();
        variant.setTenantId(tenant.getId());
        variant.setProductId(productId);
        variant.setVariantLabel(sku);
        variant.setSku(sku);
        variant.setQrCode("QR-" + sku);
        variant.setMrp(new BigDecimal("10.00"));
        variant.setSellingPrice(new BigDecimal("9.00"));
        variant.setStockQuantity(0);
        variant.setUnitOfMeasure(UnitOfMeasure.EACH);
        variant.setActive(active);
        em.persist(variant);
        return variant.getId();
    }
}

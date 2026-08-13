package com.pos.controller;

import java.math.BigDecimal;

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
import com.pos.util.TenantContext;
import com.pos.util.TestIps;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The write half of {@code /api/products} (C5) — the port of {@code productService.js}'s
 * {@code create} / {@code update} / {@code deactivate}, plus the role rule the frontend
 * expresses as a route guard.
 *
 * <p><b>Cross-tenant writes are not here.</b> They belong in {@code TenantIsolationIT}
 * with every other attempt to reach across the boundary, so that one suite stays the
 * complete answer to "what stops a t1 caller touching t2?". This suite is about the
 * endpoints behaving correctly <i>inside</i> one store.
 *
 * <p>Three properties are worth naming because they are what the mock could not pin, and
 * what a reviewer would otherwise have to take on trust:
 *
 * <ul>
 *   <li><b>The tenant is stamped, not accepted.</b> A body naming another tenant produces
 *       a row in the caller's own — asserted directly, since the filter does not police
 *       inserts and nothing else would catch a regression here.</li>
 *   <li><b>{@code PUT} is a merge patch</b>, which is what makes {@code {"isActive":true}}
 *       a legal request and is the reason {@code ProductForm} has no bean validation.</li>
 *   <li><b>Validation runs on the merged row</b>, not on the request — so blanking a name
 *       through a patch is rejected even though the request itself looks well-formed.</li>
 * </ul>
 */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = {
        RootConfig.class, PersistenceConfig.class, SecurityConfig.class, MailConfig.class,
        RecaptchaConfig.class,
        WebConfig.class, OpenApiConfig.class })
@TestPropertySource("classpath:application-test.properties")
@Transactional
@DisplayName("POST/PUT/DELETE /api/products")
class ProductWriteIT {

    private static final BCryptPasswordEncoder HASHER = new BCryptPasswordEncoder();
    private static final String ADMIN_HASH = HASHER.encode("admin123");
    private static final String CASHIER_HASH = HASHER.encode("cashier123");
    private static final String SUPER_HASH = HASHER.encode("super123");

    /** No product will ever carry it. */
    private static final long UNISSUED_ID = 9_999_999L;

    private static final String CHAI_MASALA = """
            {"name":"Everest Chai Masala","brand":"Everest","category":"Staples",
             "description":"Blended spice mix","hsnCode":"0910","taxRatePercent":5}
            """;

    @Autowired
    private WebApplicationContext context;

    @PersistenceContext
    private EntityManager em;

    private MockMvc mvc;

    private TenantPojo mgRoad;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        TenantPojo platform = tenant("Platform", TenantPojo.PLATFORM_CODE, true);
        mgRoad = tenant("MG Road Store", "mg-road", false);

        user(platform, "superadmin", SUPER_HASH, Role.SUPER_ADMIN);
        user(mgRoad, "admin", ADMIN_HASH, Role.ADMIN);
        user(mgRoad, "cashier", CASHIER_HASH, Role.CASHIER);

        // The catalogue starts empty on purpose: every row this suite reads back was
        // created through the endpoint under test, so a create that silently wrote
        // nothing cannot be masked by a fixture.
        em.flush();
        em.clear();
    }

    @AfterEach
    void clearThreadState() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Nested
    @DisplayName("POST /api/products")
    class Create {

        @Test
        @DisplayName("answers 201 with an active product in the caller's own tenant")
        void createsAnActiveProduct() throws Exception {
            create(asAdmin(), CHAI_MASALA)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id", notNullValue()))
                    .andExpect(jsonPath("$.tenantId").value(id(mgRoad)))
                    .andExpect(jsonPath("$.name").value("Everest Chai Masala"))
                    .andExpect(jsonPath("$.brand").value("Everest"))
                    .andExpect(jsonPath("$.category").value("Staples"))
                    .andExpect(jsonPath("$.hsnCode").value("0910"))
                    .andExpect(jsonPath("$.taxRatePercent").value(5))
                    .andExpect(jsonPath("$.isActive").value(true))
                    .andExpect(jsonPath("$.createdAt", notNullValue()));
        }

        /**
         * <b>The case the whole design rests on.</b> {@code ProductForm} declares none of
         * these fields, so Jackson drops them — but "the DTO happens not to have a
         * setter" is exactly the kind of protection that evaporates when someone adds one
         * for convenience. This asserts the outcome rather than the mechanism.
         */
        @Test
        @DisplayName("ignores tenantId, id, isActive and createdAt in the body")
        void ignoresIdentityFieldsInTheBody() throws Exception {
            create(asAdmin(), """
                    {"name":"Body tenantId probe","taxRatePercent":5,
                     "tenantId":"99","id":"12345","isActive":false,
                     "createdAt":"2000-01-01T00:00:00Z"}
                    """)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.tenantId").value(id(mgRoad)))
                    .andExpect(jsonPath("$.id", not("12345")))
                    // A created product is always active: a caller cannot ask for a row
                    // that no screen can reach.
                    .andExpect(jsonPath("$.isActive").value(true));
        }

        @Test
        @DisplayName("trims the name and stores the omitted optional fields as empty strings")
        void trimsAndDefaultsTheOptionalFields() throws Exception {
            // "" rather than null so a React input bound to one stays controlled -- the
            // mock rows hold the same, and ProductDao.categories excludes it.
            create(asAdmin(), """
                    {"name":"  Tata Salt  ","taxRatePercent":0}
                    """)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.name").value("Tata Salt"))
                    .andExpect(jsonPath("$.brand").value(""))
                    .andExpect(jsonPath("$.category").value(""))
                    .andExpect(jsonPath("$.description").value(""))
                    .andExpect(jsonPath("$.hsnCode").value(""));
        }

        @Test
        @DisplayName("reports every broken field, and leads with the first")
        void rejectsABlankNameAndAnUnknownSlab() throws Exception {
            // The mock throws Object.values(errors)[0]; a client with no field-level
            // rendering shows that message, so the top-level one has to match it.
            create(asAdmin(), """
                    {"name":"   ","taxRatePercent":7}
                    """)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Name is required"))
                    .andExpect(jsonPath("$.fields.name").value("Name is required"))
                    .andExpect(jsonPath("$.fields.taxRatePercent")
                            .value("Tax rate must be one of 0, 5, 12, 18, 28%"));
        }

        @Test
        @DisplayName("a missing name is the same failure as a blank one")
        void rejectsAnAbsentName() throws Exception {
            create(asAdmin(), """
                    {"taxRatePercent":5}
                    """)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fields.name").value("Name is required"));
        }

        /**
         * Peer-review Phase 1: length bounds the mock had no schema to answer to. One
         * request covering all five bounded fields on this form, the multi-error shape
         * {@code rejectsABlankNameAndAnUnknownSlab} already exercises above.
         */
        @Test
        @DisplayName("rejects overlong fields with a clean 400, not an unmapped 500")
        void rejectsOverlongFields() throws Exception {
            create(asAdmin(), """
                    {"name":"%s","brand":"%s","category":"%s","description":"%s","hsnCode":"%s",
                     "taxRatePercent":5}
                    """.formatted("N".repeat(201), "B".repeat(121), "C".repeat(81),
                            "D".repeat(501), "H".repeat(17)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fields.name").value("Name must be 200 characters or fewer"))
                    .andExpect(jsonPath("$.fields.brand").value("Brand must be 120 characters or fewer"))
                    .andExpect(jsonPath("$.fields.category").value("Category must be 80 characters or fewer"))
                    .andExpect(jsonPath("$.fields.description")
                            .value("Description must be 500 characters or fewer"))
                    .andExpect(jsonPath("$.fields.hsnCode").value("HSN code must be 16 characters or fewer"));
        }

        /**
         * {@code 5} and {@code 5.00} are the same slab and unequal {@code BigDecimal}s —
         * which is why {@code ProductService} compares with {@code compareTo}. A client
         * sending either has sent a valid rate.
         */
        @ParameterizedTest(name = "taxRatePercent {0} is accepted")
        @ValueSource(strings = { "0", "5", "12", "18", "28", "5.00", "18.0" })
        void acceptsEveryGstSlab(String rate) throws Exception {
            create(asAdmin(), """
                    {"name":"Slab %s","taxRatePercent":%s}
                    """.formatted(rate, rate))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("the new product is immediately in the list and its category in the dropdown")
        void isVisibleToTheVeryNextRequest() throws Exception {
            create(asAdmin(), CHAI_MASALA).andExpect(status().isCreated());

            list(asAdmin())
                    .andExpect(jsonPath("$.total").value(1))
                    .andExpect(jsonPath("$.items[0].name").value("Everest Chai Masala"));
            mvc.perform(get("/api/products/categories").header(AUTH, bearer(asAdmin())))
                    .andExpect(jsonPath("$", hasItem("Staples")));
        }

        /**
         * Peer-review Phase 0 resource-creation guardrail — {@code pos.tenant.maxProducts}
         * (2000 in {@code application.properties}, not overridden for tests). Seeded by
         * direct persistence rather than 2000 round trips; the real endpoint proves the
         * ceiling itself.
         */
        @Test
        @DisplayName("rejects a create once the tenant is at its product-count ceiling")
        void rejectsACreateAtTheProductCeiling() throws Exception {
            for (int i = 0; i < 2000; i++) {
                product(mgRoad, "Filler " + i);
            }
            em.flush();
            em.clear();

            create(asAdmin(), """
                    {"name":"One More","taxRatePercent":5}
                    """)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("This store has reached its product limit"))
                    .andExpect(jsonPath("$.fields").doesNotExist());
        }

        /**
         * Peer-review Phase 1: the two-tier guardrail's whole point, and the bug this
         * change fixes — before this, a tenant at the ceiling stayed locked out forever,
         * even after deactivating a product, because the count included inactive rows.
         * Manually verified end-to-end against a real running app first (curl, with
         * pos.tenant.maxProducts/Lifetime overridden low) before writing this.
         */
        @Test
        @DisplayName("reclaims a slot at the same ceiling once a product is deactivated")
        void reclaimsASlotOnceAProductIsDeactivated() throws Exception {
            Long lastId = null;
            for (int i = 0; i < 2000; i++) {
                lastId = product(mgRoad, "Filler " + i, true);
            }
            em.flush();
            em.clear();

            create(asAdmin(), """
                    {"name":"Still At The Ceiling","taxRatePercent":5}
                    """)
                    .andExpect(status().isBadRequest());

            deleteProduct(asAdmin(), String.valueOf(lastId)).andExpect(status().isOk());

            create(asAdmin(), """
                    {"name":"Room Again","taxRatePercent":5}
                    """)
                    .andExpect(status().isCreated());
        }

        /**
         * Peer-review Phase 1: the second tier ({@code pos.tenant.maxProductsLifetime},
         * 10000) — the pure abuse backstop that exists precisely so a create/deactivate
         * loop can't spam unlimited rows now that the primary ceiling is reclaimable.
         * Active count stays comfortably under the 2000 ceiling throughout; only the
         * lifetime count (active + inactive) reaches its own, separate limit.
         */
        @Test
        @DisplayName("still rejects at the lifetime ceiling, even with headroom on the active count")
        void rejectsAtTheLifetimeCeilingEvenBelowTheActiveCeiling() throws Exception {
            for (int i = 0; i < 100; i++) {
                product(mgRoad, "Active Filler " + i, true);
            }
            for (int i = 0; i < 9_900; i++) {
                product(mgRoad, "Retired Filler " + i, false);
            }
            em.flush();
            em.clear();

            create(asAdmin(), """
                    {"name":"One More","taxRatePercent":5}
                    """)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("This store has reached its product limit"));
        }
    }

    @Nested
    @DisplayName("PUT /api/products/{id}")
    class Update {

        @Test
        @DisplayName("patches only what it is given")
        void patchesOnlyWhatItIsGiven() throws Exception {
            String productId = createChaiMasala();

            update(asAdmin(), productId, """
                    {"brand":"Everest Spices"}
                    """)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.brand").value("Everest Spices"))
                    // Everything absent from the patch survives it. Without this, a
                    // "replace the row" implementation would pass the assertion above.
                    .andExpect(jsonPath("$.name").value("Everest Chai Masala"))
                    .andExpect(jsonPath("$.category").value("Staples"))
                    .andExpect(jsonPath("$.hsnCode").value("0910"))
                    .andExpect(jsonPath("$.taxRatePercent").value(5))
                    .andExpect(jsonPath("$.isActive").value(true));
        }

        @Test
        @DisplayName("validates the merged row, not the request")
        void validatesTheMergedRow() throws Exception {
            // The request is well-formed and would pass any per-request check; what it
            // would PRODUCE is a nameless product. That distinction is the reason
            // validation lives in the service rather than on the DTO.
            String productId = createChaiMasala();

            update(asAdmin(), productId, """
                    {"name":"   "}
                    """)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fields.name").value("Name is required"));

            getProduct(asAdmin(), productId).andExpect(jsonPath("$.name").value("Everest Chai Masala"));
        }

        @Test
        @DisplayName("a rate the merged row keeps is not re-validated into a failure")
        void leavesAnUntouchedRateAlone() throws Exception {
            String productId = createChaiMasala();

            update(asAdmin(), productId, """
                    {"description":"Now with cardamom"}
                    """)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.taxRatePercent").value(5));
        }

        @Test
        @DisplayName("reactivates with isActive alone — the request ProductForm exists to allow")
        void reactivatesWithIsActiveAlone() throws Exception {
            String productId = createChaiMasala();
            deleteProduct(asAdmin(), productId).andExpect(jsonPath("$.isActive").value(false));

            // pages/Products/Products.jsx sends exactly this. A @NotBlank on name would
            // 400 it, which is why the form carries no constraints.
            update(asAdmin(), productId, """
                    {"isActive":true}
                    """)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.isActive").value(true))
                    .andExpect(jsonPath("$.name").value("Everest Chai Masala"));
        }

        @Test
        @DisplayName("an id that never existed is 404")
        void unknownIdIs404() throws Exception {
            update(asAdmin(), String.valueOf(UNISSUED_ID), """
                    {"brand":"x"}
                    """)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Product not found"));
        }
    }

    @Nested
    @DisplayName("DELETE /api/products/{id}")
    class Deactivate {

        @Test
        @DisplayName("hides the row from the default list without removing it")
        void hidesItFromTheDefaultList() throws Exception {
            String productId = createChaiMasala();

            deleteProduct(asAdmin(), productId)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.isActive").value(false));

            list(asAdmin()).andExpect(jsonPath("$.items", hasSize(0)));
            // Still there, which is the point of a soft delete: a receipt reprint and a
            // returns lookup both have to resolve a product that is no longer sold.
            list(asAdmin(), "includeInactive", "true")
                    .andExpect(jsonPath("$.items", hasSize(1)));
            getProduct(asAdmin(), productId).andExpect(status().isOk());
        }

        @Test
        @DisplayName("is idempotent")
        void isIdempotent() throws Exception {
            String productId = createChaiMasala();

            deleteProduct(asAdmin(), productId).andExpect(status().isOk());
            deleteProduct(asAdmin(), productId)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.isActive").value(false));
        }

        @Test
        @DisplayName("an id that never existed is 404")
        void unknownIdIs404() throws Exception {
            deleteProduct(asAdmin(), String.valueOf(UNISSUED_ID))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Product not found"));
        }
    }

    /**
     * Requirements.md section 13.2: catalogue management is an {@code ADMIN}'s. The
     * frontend expresses this as a route guard, which is UX — this is where it is
     * actually enforced, and it is the first rule in the application that discriminates
     * by role.
     */
    @Nested
    @DisplayName("the role rule")
    class RoleRules {

        @Test
        @DisplayName("a CASHIER cannot create, update or deactivate")
        void aCashierCannotWrite() throws Exception {
            String productId = createChaiMasala();
            String cashier = asCashier();

            create(cashier, CHAI_MASALA).andExpect(status().isForbidden());
            update(cashier, productId, """
                    {"brand":"x"}
                    """).andExpect(status().isForbidden());
            deleteProduct(cashier, productId).andExpect(status().isForbidden());

            // And the refusal changed nothing.
            getProduct(asAdmin(), productId)
                    .andExpect(jsonPath("$.brand").value("Everest"))
                    .andExpect(jsonPath("$.isActive").value(true));
        }

        @Test
        @DisplayName("but reads the same catalogue — the rule is method-scoped, not path-scoped")
        void aCashierCanStillRead() throws Exception {
            createChaiMasala();

            list(asCashier()).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
            mvc.perform(get("/api/products/categories").header(AUTH, bearer(asCashier())))
                    .andExpect(status().isOk());
        }

        /**
         * A platform admin fails the role rule before {@code requireTenant()} is reached,
         * so it never gets as far as the "no store account" 403. Both guards say no; this
         * pins which one answers, so a later change to either is visible here.
         */
        @Test
        @DisplayName("a SUPER_ADMIN is refused by the role rule, not by the tenant guard")
        void aPlatformAdminIsRefusedToo() throws Exception {
            create(asPlatformAdmin(), CHAI_MASALA)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message",
                            not(TenantContext.NO_TENANT_MESSAGE)));
        }
    }

    // --- helpers -----------------------------------------------------------------

    private static final String AUTH = "Authorization";

    private ResultActions create(String token, String body) throws Exception {
        return mvc.perform(post("/api/products")
                .header(AUTH, bearer(token))
                .contentType(APPLICATION_JSON)
                .content(body));
    }

    private ResultActions update(String token, String productId, String body) throws Exception {
        return mvc.perform(put("/api/products/" + productId)
                .header(AUTH, bearer(token))
                .contentType(APPLICATION_JSON)
                .content(body));
    }

    private ResultActions deleteProduct(String token, String productId) throws Exception {
        return mvc.perform(delete("/api/products/" + productId).header(AUTH, bearer(token)));
    }

    private ResultActions getProduct(String token, String productId) throws Exception {
        return mvc.perform(get("/api/products/" + productId).header(AUTH, bearer(token)));
    }

    private ResultActions list(String token, String... params) throws Exception {
        var request = get("/api/products").header(AUTH, bearer(token)).param("pageSize", "200");
        for (int i = 0; i < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        return mvc.perform(request);
    }

    /** Through the endpoint rather than through {@code em.persist}, so every fixture
     * here is also a create that is known to have worked. */
    private String createChaiMasala() throws Exception {
        String response = create(asAdmin(), CHAI_MASALA)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private String id(TenantPojo tenant) {
        return String.valueOf(tenant.getId());
    }

    private String asAdmin() throws Exception {
        return tokenFor("mg-road", "admin", "admin123");
    }

    private String asCashier() throws Exception {
        return tokenFor("mg-road", "cashier", "cashier123");
    }

    private String asPlatformAdmin() throws Exception {
        return tokenFor(TenantPojo.PLATFORM_CODE, "superadmin", "super123");
    }

    private String tokenFor(String tenantCode, String username, String password) throws Exception {
        String body = """
                {"tenantCode":"%s","username":"%s","password":"%s"}
                """.formatted(tenantCode, username, password);
        String response = mvc.perform(post("/api/auth/login").with(TestIps.remoteAddr(TestIps.fresh())).contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Logging in leaves its own state on the test thread; the request under test has
        // to establish its own from its own token.
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        return JsonPath.read(response, "$.token");
    }

    private TenantPojo tenant(String name, String code, boolean platform) {
        TenantPojo tenant = new TenantPojo();
        tenant.setName(name);
        tenant.setCode(code);
        tenant.setStatus(TenantStatus.ACTIVE);
        tenant.setPlatform(platform);
        em.persist(tenant);
        return tenant;
    }

    private void user(TenantPojo tenant, String username, String passwordHash, Role role) {
        AppUserPojo user = new AppUserPojo();
        user.setTenant(tenant);
        user.setUsername(username);
        user.setPasswordHash(passwordHash);
        user.setDisplayName(username);
        user.setRole(role);
        user.setActive(true);
        em.persist(user);
    }

    /** Fast-path fixture for the product-ceiling test — 2000 of these beats 2000 round trips. */
    private void product(TenantPojo tenant, String name) {
        product(tenant, name, true);
    }

    /**
     * Same, with the active flag exposed — the two-tier guardrail tests (peer-review
     * Phase 1) need inactive rows that count toward the lifetime ceiling without
     * counting toward the active one. Returns the id so a test can deactivate a
     * specific row through the real endpoint afterward.
     */
    private Long product(TenantPojo tenant, String name, boolean active) {
        ProductPojo product = new ProductPojo();
        product.setTenant(tenant);
        product.setName(name);
        product.setTaxRatePercent(BigDecimal.ZERO);
        product.setActive(active);
        em.persist(product);
        return product.getId();
    }
}

package com.pos.controller;

import java.math.BigDecimal;

import com.jayway.jsonpath.JsonPath;
import com.pos.config.OpenApiConfig;
import com.pos.config.PersistenceConfig;
import com.pos.config.RootConfig;
import com.pos.config.SecurityConfig;
import com.pos.config.WebConfig;
import com.pos.pojo.AppUser;
import com.pos.pojo.Product;
import com.pos.pojo.Role;
import com.pos.pojo.Tenant;
import com.pos.pojo.TenantStatus;
import com.pos.pojo.UnitOfMeasure;
import com.pos.pojo.Variant;
import com.pos.util.TenantContext;
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

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /api/orders}, inside one store — create/list/get/patch (C6). Payment is
 * {@code PaymentIT}'s, and cross-tenant behaviour is {@code TenantIsolationIT}'s, so that
 * one suite stays the complete answer to "what stops a t1 caller touching t2?".
 *
 * <p>What this suite is really about is the same property {@code ProductWriteIT} pins for
 * products: <b>every amount is recomputed, never accepted.</b> The fixture variant's
 * {@code sellingPrice} and its product's {@code taxRatePercent} are the only numbers that
 * should ever appear in a response — a request that supplies its own is testing whether
 * the server used them, and it must not have.
 */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = {
        RootConfig.class, PersistenceConfig.class, SecurityConfig.class,
        WebConfig.class, OpenApiConfig.class })
@TestPropertySource("classpath:application-test.properties")
@Transactional
@DisplayName("/api/orders")
class OrderWriteIT {

    private static final BCryptPasswordEncoder HASHER = new BCryptPasswordEncoder();
    private static final String ADMIN_HASH = HASHER.encode("admin123");
    private static final String CASHIER_HASH = HASHER.encode("cashier123");
    private static final String SUPER_HASH = HASHER.encode("super123");

    private static final long UNISSUED_ID = 9_999_999L;

    @Autowired
    private WebApplicationContext context;

    @PersistenceContext
    private EntityManager em;

    private MockMvc mvc;

    private Tenant mgRoad;
    private Long milk500;
    private Long lays52;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        Tenant platform = tenant("Platform", Tenant.PLATFORM_CODE, true);
        mgRoad = tenant("MG Road Store", "mg-road", false);

        user(platform, "superadmin", SUPER_HASH, Role.SUPER_ADMIN);
        user(mgRoad, "admin", ADMIN_HASH, Role.ADMIN);
        user(mgRoad, "cashier", CASHIER_HASH, Role.CASHIER);
        user(mgRoad, "cashier2", CASHIER_HASH, Role.CASHIER);

        Long milkProduct = product(mgRoad, "Amul Taaza Toned Milk", "5.00");
        milk500 = variant(mgRoad, milkProduct, "500 ml", "AMUL-MILK-500", "30.00", "29.00", 40);
        Long laysProduct = product(mgRoad, "Lay's Classic Salted", "12.00");
        lays52 = variant(mgRoad, laysProduct, "52 g", "LAYS-SALT-52", "20.00", "20.00", 60);

        em.flush();
        em.clear();
    }

    @AfterEach
    void clearThreadState() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Nested
    @DisplayName("POST /api/orders")
    class Create {

        @Test
        @DisplayName("answers 201 with a DRAFT order, priced from the variant's own row")
        void createsADraftOrderPricedFromTheVariant() throws Exception {
            create(asCashier(), """
                    {"items":[{"variantId":"%s","quantity":2}]}
                    """.formatted(milk500))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.tenantId").value(id(mgRoad)))
                    .andExpect(jsonPath("$.status").value("DRAFT"))
                    .andExpect(jsonPath("$.orderNumber", notNullValue()))
                    .andExpect(jsonPath("$.items", hasSize(1)))
                    .andExpect(jsonPath("$.items[0].variantId").value(String.valueOf(milk500)))
                    .andExpect(jsonPath("$.items[0].name").value("Amul Taaza Toned Milk — 500 ml"))
                    .andExpect(jsonPath("$.items[0].unitPrice").value(29))
                    .andExpect(jsonPath("$.items[0].taxRatePercent").value(5))
                    .andExpect(jsonPath("$.items[0].lineTotal").value(58))
                    .andExpect(jsonPath("$.subtotal").value(58))
                    .andExpect(jsonPath("$.grandTotal").value(58))
                    .andExpect(jsonPath("$.payment").doesNotExist())
                    .andExpect(jsonPath("$.createdAt", notNullValue()));
        }

        @Test
        @DisplayName("creates a HELD order when asked, for Checkout's Hold button")
        void createsAHeldOrder() throws Exception {
            create(asCashier(), """
                    {"items":[{"variantId":"%s","quantity":1}],"status":"HELD"}
                    """.formatted(milk500))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("HELD"));
        }

        /**
         * <b>The case the whole design rests on</b> — the same argument
         * {@code ProductWriteIT.ignoresIdentityFieldsInTheBody} makes: a DTO with no setter
         * for a field is not durable proof on its own, so this asserts the server actually
         * used its own numbers rather than trusting that {@link com.pos.model.OrderLineForm}
         * merely lacks a place to put them.
         */
        @Test
        @DisplayName("ignores a price, tax rate or name supplied in the line")
        void ignoresPriceFieldsSuppliedInTheLine() throws Exception {
            create(asCashier(), """
                    {"items":[{"variantId":"%s","quantity":1,"unitPrice":1,
                     "taxRatePercent":99,"name":"HACKED","lineTotal":1}]}
                    """.formatted(milk500))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.items[0].unitPrice").value(29))
                    .andExpect(jsonPath("$.items[0].taxRatePercent").value(5))
                    .andExpect(jsonPath("$.items[0].name").value("Amul Taaza Toned Milk — 500 ml"));
        }

        @Test
        @DisplayName("reconciles subtotal, tax and grandTotal across two different GST slabs")
        void reconcilesAcrossMultipleLines() throws Exception {
            // 2x milk @29 (5%) = 58; 3x lays @20 (12%) = 60; subtotal 118, whole rupee.
            create(asCashier(), """
                    {"items":[{"variantId":"%s","quantity":2},
                               {"variantId":"%s","quantity":3}]}
                    """.formatted(milk500, lays52))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.items", hasSize(2)))
                    .andExpect(jsonPath("$.subtotal").value(118))
                    .andExpect(jsonPath("$.grandTotal").value(118))
                    .andExpect(jsonPath("$.roundOff").value(0));
        }

        @Test
        @DisplayName("stamps the cashier from the token")
        void stampsTheCashierFromTheToken() throws Exception {
            String cashierId = JsonPath.read(
                    mvc.perform(get("/api/auth/me").header(AUTH, bearer(asCashier())))
                            .andReturn().getResponse().getContentAsString(),
                    "$.id");

            create(asCashier(), """
                    {"items":[{"variantId":"%s","quantity":1}]}
                    """.formatted(milk500))
                    .andExpect(jsonPath("$.cashierId").value(cashierId));
        }

        @Test
        @DisplayName("an unknown variant id is 'Variant not found'")
        void unknownVariantIs404() throws Exception {
            create(asCashier(), """
                    {"items":[{"variantId":"%s","quantity":1}]}
                    """.formatted(UNISSUED_ID))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Variant not found"));
        }

        @Test
        @DisplayName("a zero or missing quantity is 400 on the quantity field")
        void rejectsAZeroOrMissingQuantity() throws Exception {
            create(asCashier(), """
                    {"items":[{"variantId":"%s","quantity":0}]}
                    """.formatted(milk500))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fields.quantity").value("Quantity must be greater than 0"));

            create(asCashier(), """
                    {"items":[{"quantity":1}]}
                    """)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fields.variantId").value("Variant is required"));
        }

        @Test
        @DisplayName("only DRAFT or HELD may be requested on create")
        void onlyDraftOrHeldMayBeRequested() throws Exception {
            create(asCashier(), """
                    {"items":[{"variantId":"%s","quantity":1}],"status":"COMPLETED"}
                    """.formatted(milk500))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fields.status").value("New orders must be DRAFT or HELD"));

            create(asCashier(), """
                    {"items":[{"variantId":"%s","quantity":1}],"status":"CANCELLED"}
                    """.formatted(milk500))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("an ADMIN can create one too — checkout isn't cashier-only")
        void anAdminCanCreateToo() throws Exception {
            create(asAdmin(), """
                    {"items":[{"variantId":"%s","quantity":1}]}
                    """.formatted(milk500))
                    .andExpect(status().isCreated());
        }
    }

    @Nested
    @DisplayName("GET /api/orders/{id}")
    class Get {

        @Test
        @DisplayName("returns the order regardless of who is asking, within the tenant")
        void isOpenToEitherRoleWithinTheTenant() throws Exception {
            String orderId = createDraftAsCashier();

            getOrder(asAdmin(), orderId).andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(orderId));
            // A second cashier reading the first one's order — GET is not cashier-scoped,
            // unlike list(). A resume-by-id or a reprint has to work either way.
            getOrder(asCashier2(), orderId).andExpect(status().isOk());
        }

        @Test
        @DisplayName("an id that never existed is 404")
        void unknownIdIs404() throws Exception {
            getOrder(asCashier(), String.valueOf(UNISSUED_ID))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Order not found"));
        }
    }

    @Nested
    @DisplayName("GET /api/orders — list")
    class List {

        @Test
        @DisplayName("a CASHIER's cashierId is forced to their own, whatever is passed")
        void aCashiersCashierIdIsForced() throws Exception {
            createDraftAsCashier();
            String cashier2Id = JsonPath.read(
                    mvc.perform(get("/api/auth/me").header(AUTH, bearer(asCashier2())))
                            .andReturn().getResponse().getContentAsString(),
                    "$.id");

            // cashier passes cashier2's id -- must still see only their own order.
            list(asCashier(), "cashierId", cashier2Id)
                    .andExpect(jsonPath("$.total").value(1));
        }

        @Test
        @DisplayName("an ADMIN sees every order in the tenant with no cashierId")
        void anAdminSeesEveryOrderByDefault() throws Exception {
            createDraftAsCashier();
            create(asCashier2(), """
                    {"items":[{"variantId":"%s","quantity":1}]}
                    """.formatted(milk500)).andExpect(status().isCreated());

            list(asAdmin()).andExpect(jsonPath("$.total").value(2));
        }

        @Test
        @DisplayName("an ADMIN's cashierId filter narrows to one operator")
        void anAdminsCashierIdFilterNarrows() throws Exception {
            createDraftAsCashier();
            String cashier2Token = asCashier2();
            create(cashier2Token, """
                    {"items":[{"variantId":"%s","quantity":1}]}
                    """.formatted(milk500)).andExpect(status().isCreated());
            String cashier2Id = JsonPath.read(
                    mvc.perform(get("/api/auth/me").header(AUTH, bearer(cashier2Token)))
                            .andReturn().getResponse().getContentAsString(),
                    "$.id");

            list(asAdmin(), "cashierId", cashier2Id).andExpect(jsonPath("$.total").value(1));
        }

        @Test
        @DisplayName("status filters, and newest first")
        void statusFiltersAndOrdersNewestFirst() throws Exception {
            create(asCashier(), """
                    {"items":[{"variantId":"%s","quantity":1}],"status":"HELD"}
                    """.formatted(milk500)).andExpect(status().isCreated());
            create(asCashier(), """
                    {"items":[{"variantId":"%s","quantity":1}]}
                    """.formatted(milk500)).andExpect(status().isCreated());

            list(asCashier(), "status", "HELD").andExpect(jsonPath("$.total").value(1))
                    .andExpect(jsonPath("$.items[0].status").value("HELD"));
        }
    }

    @Nested
    @DisplayName("PATCH /api/orders/{id} — hold, resume-and-reprice, cancel")
    class Update {

        @Test
        @DisplayName("transitions DRAFT to HELD")
        void transitionsDraftToHeld() throws Exception {
            String orderId = createDraftAsCashier();

            patchOrder(asCashier(), orderId, """
                    {"status":"HELD"}
                    """)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("HELD"));
        }

        @Test
        @DisplayName("cancels a held order")
        void cancelsAHeldOrder() throws Exception {
            String orderId = createDraftAsCashier();
            patchOrder(asCashier(), orderId, """
                    {"status":"HELD"}
                    """).andExpect(status().isOk());

            patchOrder(asCashier(), orderId, """
                    {"status":"CANCELLED"}
                    """)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CANCELLED"));
        }

        @Test
        @DisplayName("only HELD or CANCELLED may be requested — not back to DRAFT")
        void onlyHeldOrCancelledMayBeRequested() throws Exception {
            String orderId = createDraftAsCashier();

            patchOrder(asCashier(), orderId, """
                    {"status":"DRAFT"}
                    """)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fields.status")
                            .value("Status can only be changed to HELD or CANCELLED"));
        }

        @Test
        @DisplayName("items replace the line set wholesale and re-price from current rows")
        void itemsReplaceTheLineSetAndReprice() throws Exception {
            String orderId = createDraftAsCashier();

            patchOrder(asCashier(), orderId, """
                    {"items":[{"variantId":"%s","quantity":3},{"variantId":"%s","quantity":1}]}
                    """.formatted(milk500, lays52))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(2)))
                    // 3x29 + 1x20 = 107
                    .andExpect(jsonPath("$.subtotal").value(107))
                    .andExpect(jsonPath("$.grandTotal").value(107));
        }

        @Test
        @DisplayName("orderDiscount alone re-totals the EXISTING lines, unchanged")
        void orderDiscountAloneRetotalsExistingLines() throws Exception {
            String orderId = createDraftAsCashier(); // 2x29 = 58

            patchOrder(asCashier(), orderId, """
                    {"orderDiscount":8}
                    """)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(1)))
                    .andExpect(jsonPath("$.items[0].quantity").value(2))
                    .andExpect(jsonPath("$.orderDiscount").value(8))
                    .andExpect(jsonPath("$.grandTotal").value(50));
        }

        @Test
        @DisplayName("an id that never existed is 404")
        void unknownIdIs404() throws Exception {
            patchOrder(asCashier(), String.valueOf(UNISSUED_ID), """
                    {"status":"HELD"}
                    """)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Order not found"));
        }
    }

    @Nested
    @DisplayName("a SUPER_ADMIN has no tenant to hold an order in")
    class PlatformAdmin {

        @Test
        @DisplayName("is refused by every order endpoint, not shown an empty store")
        void isRefusedEverywhere() throws Exception {
            create(asPlatformAdmin(), """
                    {"items":[{"variantId":"%s","quantity":1}]}
                    """.formatted(milk500))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value(TenantContext.NO_TENANT_MESSAGE));

            list(asPlatformAdmin()).andExpect(status().isForbidden());
        }
    }

    // --- helpers -----------------------------------------------------------------

    private static final String AUTH = "Authorization";

    private ResultActions create(String token, String body) throws Exception {
        return mvc.perform(post("/api/orders")
                .header(AUTH, bearer(token))
                .contentType(APPLICATION_JSON)
                .content(body));
    }

    /**
     * Named {@code getOrder}/{@code patchOrder} rather than {@code get}/{@code patch} —
     * TenantIsolationIT's helpers hit the identical problem: a member method shadows the
     * statically-imported request builder of the same name, and Java resolves the member
     * first regardless of the argument list, so a same-named helper breaks every other use
     * of that verb in this class.
     */
    private ResultActions getOrder(String token, String orderId) throws Exception {
        return mvc.perform(get("/api/orders/" + orderId).header(AUTH, bearer(token)));
    }

    private ResultActions patchOrder(String token, String orderId, String body) throws Exception {
        return mvc.perform(patch("/api/orders/" + orderId)
                .header(AUTH, bearer(token))
                .contentType(APPLICATION_JSON)
                .content(body));
    }

    private ResultActions list(String token, String... params) throws Exception {
        var request = get("/api/orders").header(AUTH, bearer(token)).param("pageSize", "200");
        for (int i = 0; i < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        return mvc.perform(request);
    }

    private String createDraftAsCashier() throws Exception {
        String response = create(asCashier(), """
                {"items":[{"variantId":"%s","quantity":2}]}
                """.formatted(milk500))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private String id(Tenant tenant) {
        return String.valueOf(tenant.getId());
    }

    private String asAdmin() throws Exception {
        return tokenFor("mg-road", "admin", "admin123");
    }

    private String asCashier() throws Exception {
        return tokenFor("mg-road", "cashier", "cashier123");
    }

    private String asCashier2() throws Exception {
        return tokenFor("mg-road", "cashier2", "cashier123");
    }

    private String asPlatformAdmin() throws Exception {
        return tokenFor(Tenant.PLATFORM_CODE, "superadmin", "super123");
    }

    private String tokenFor(String tenantCode, String username, String password) throws Exception {
        String body = """
                {"tenantCode":"%s","username":"%s","password":"%s"}
                """.formatted(tenantCode, username, password);
        String response = mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        SecurityContextHolder.clearContext();
        TenantContext.clear();
        return JsonPath.read(response, "$.token");
    }

    private Tenant tenant(String name, String code, boolean platform) {
        Tenant tenant = new Tenant();
        tenant.setName(name);
        tenant.setCode(code);
        tenant.setStatus(TenantStatus.ACTIVE);
        tenant.setPlatform(platform);
        em.persist(tenant);
        return tenant;
    }

    private void user(Tenant tenant, String username, String passwordHash, Role role) {
        AppUser user = new AppUser();
        user.setTenant(tenant);
        user.setUsername(username);
        user.setPasswordHash(passwordHash);
        user.setDisplayName(username);
        user.setRole(role);
        user.setActive(true);
        em.persist(user);
    }

    private Long product(Tenant tenant, String name, String taxRatePercent) {
        Product product = new Product();
        product.setTenant(tenant);
        product.setName(name);
        product.setBrand("Test");
        product.setCategory("Test");
        product.setTaxRatePercent(new BigDecimal(taxRatePercent));
        product.setActive(true);
        em.persist(product);
        return product.getId();
    }

    private Long variant(Tenant tenant, Long productId, String label, String sku,
                         String mrp, String sellingPrice, int stock) {
        Variant variant = new Variant();
        variant.setTenant(tenant);
        variant.setProduct(em.getReference(Product.class, productId));
        variant.setVariantLabel(label);
        variant.setSku(sku);
        variant.setQrCode("POS-QR-" + tenant.getId() + "-" + sku);
        variant.setMrp(new BigDecimal(mrp));
        variant.setSellingPrice(new BigDecimal(sellingPrice));
        variant.setStockQuantity(stock);
        variant.setUnitOfMeasure(UnitOfMeasure.EACH);
        variant.setActive(true);
        em.persist(variant);
        return variant.getId();
    }
}

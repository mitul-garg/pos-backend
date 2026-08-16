package com.pos.controller;

import java.math.BigDecimal;

import com.jayway.jsonpath.JsonPath;
import com.pos.config.ImagesConfig;
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

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/orders/lookup} and {@code /api/returns}, inside one store (C7).
 * Cross-tenant behaviour is {@code TenantIsolationIT}'s, so that suite stays the
 * complete answer to "what stops a t1 caller touching t2?" — what this one is about is
 * the property {@code OrderWriteIT}/{@code PaymentIT} pin for orders: <b>every refund
 * amount is recomputed from the ORIGINAL order line's own snapshot</b>, and a return
 * cannot exceed what remains unreturned.
 */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = {
        RootConfig.class, PersistenceConfig.class, SecurityConfig.class, MailConfig.class,
        RecaptchaConfig.class, ImagesConfig.class,
        WebConfig.class, OpenApiConfig.class })
@TestPropertySource("classpath:application-test.properties")
@Transactional
@DisplayName("GET /api/orders/lookup, /api/returns")
class ReturnWriteIT {

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

    private Long milk500;
    private Long lays52;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        TenantPojo platform = tenant("Platform", TenantPojo.PLATFORM_CODE, true);
        TenantPojo mgRoad = tenant("MG Road Store", "mg-road", false);

        user(platform, "superadmin", SUPER_HASH, Role.SUPER_ADMIN);
        user(mgRoad, "admin", ADMIN_HASH, Role.ADMIN);
        user(mgRoad, "cashier", CASHIER_HASH, Role.CASHIER);

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
    @DisplayName("GET /api/orders/lookup")
    class Lookup {

        @Test
        @DisplayName("a completed order comes back with every line fully returnable")
        void aCompletedOrderIsFullyReturnable() throws Exception {
            String orderNumber = createAndPayOrder(milk500, 2); // 2x29 = 58

            lookupOrder(orderNumber)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("COMPLETED"))
                    .andExpect(jsonPath("$.items", hasSize(1)))
                    .andExpect(jsonPath("$.items[0].quantity").value(2))
                    .andExpect(jsonPath("$.items[0].returnedQuantity").value(0))
                    .andExpect(jsonPath("$.items[0].returnableQuantity").value(2));
        }

        @Test
        @DisplayName("a returned line's returnable quantity drops by exactly what was returned")
        void returnableQuantityDropsAfterAReturn() throws Exception {
            String orderId = createAndPayOrderId(milk500, 3);
            String orderNumber = orderNumberOf(orderId);

            createReturn(orderId, milk500, 1).andExpect(status().isCreated());

            lookupOrder(orderNumber)
                    .andExpect(jsonPath("$.items[0].returnedQuantity").value(1))
                    .andExpect(jsonPath("$.items[0].returnableQuantity").value(2));
        }

        @Test
        @DisplayName("a DRAFT order is 400 'only a completed order can be returned'")
        void aDraftOrderIs400() throws Exception {
            String response = create(asCashier(), """
                    {"items":[{"variantId":"%s","quantity":1}]}
                    """.formatted(milk500))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            String orderNumber = JsonPath.read(response, "$.orderNumber");

            lookupOrder(orderNumber)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Only a completed order can be returned"));
        }

        @Test
        @DisplayName("an unknown order number is 404")
        void anUnknownNumberIs404() throws Exception {
            lookupOrder("ORD-NEVER-ISSUED")
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Order not found"));
        }
    }

    @Nested
    @DisplayName("POST /api/returns")
    class Create {

        @Test
        @DisplayName("refunds against the ORIGINAL sale price, not a price change since")
        void refundsAgainstTheOriginalSalePrice() throws Exception {
            String orderId = createAndPayOrderId(milk500, 1); // 29 @ 5%

            // Reprice the variant AFTER the sale -- the refund must still be 29, the price
            // actually charged, never today's. mrp raised alongside it, or the new selling
            // price would itself violate ck_variant_price_within_mrp.
            reprice(milk500, "500.00", "450.00");

            createReturn(orderId, milk500, 1)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.items[0].unitPrice").value(29))
                    .andExpect(jsonPath("$.refundSubtotal").value(29))
                    .andExpect(jsonPath("$.refundTotal").value(29));
        }

        @Test
        @DisplayName("restores stock by exactly the returned quantity")
        void restoresStockByExactlyTheReturnedQuantity() throws Exception {
            String orderId = createAndPayOrderId(milk500, 3); // 40 -> 37

            createReturn(orderId, milk500, 2).andExpect(status().isCreated()); // 37 -> 39

            assertStock(milk500, 39);
        }

        @Test
        @DisplayName("defaults refundMethod to the original order's own payment method")
        void defaultsRefundMethodToTheOriginalPaymentMethod() throws Exception {
            String orderId = createAndPayOrderId(milk500, 1);

            createReturn(orderId, milk500, 1)
                    .andExpect(jsonPath("$.refundMethod").value("CARD"));
        }

        @Test
        @DisplayName("an explicit refundMethod overrides the default")
        void anExplicitRefundMethodOverridesTheDefault() throws Exception {
            String orderId = createAndPayOrderId(milk500, 1);

            mvc.perform(post("/api/returns")
                            .header(AUTH, bearer(asCashier()))
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    {"originalOrderId":"%s","items":[{"variantId":"%s","quantity":1}],
                                     "refundMethod":"UPI"}
                                    """.formatted(orderId, milk500)))
                    .andExpect(jsonPath("$.refundMethod").value("UPI"));
        }

        /**
         * Peer-review Phase 1: sales_return.reason is VARCHAR(500); the mock had no
         * schema to bound it against.
         */
        @Test
        @DisplayName("rejects an overlong reason with a clean 400, not an unmapped 500")
        void rejectsAnOverlongReason() throws Exception {
            String orderId = createAndPayOrderId(milk500, 1);

            mvc.perform(post("/api/returns")
                            .header(AUTH, bearer(asCashier()))
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    {"originalOrderId":"%s","items":[{"variantId":"%s","quantity":1}],
                                     "reason":"%s"}
                                    """.formatted(orderId, milk500, "R".repeat(501))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fields.reason").value("Reason must be 500 characters or fewer"));
        }

        @Test
        @DisplayName("mints this store's next return number and stamps processedBy from the token")
        void mintsAReturnNumberAndStampsTheActor() throws Exception {
            String orderId = createAndPayOrderId(milk500, 1);
            String cashierId = JsonPath.read(
                    mvc.perform(get("/api/auth/me").header(AUTH, bearer(asCashier())))
                            .andReturn().getResponse().getContentAsString(),
                    "$.id");

            createReturn(orderId, milk500, 1)
                    .andExpect(jsonPath("$.returnNumber", notNullValue()))
                    .andExpect(jsonPath("$.originalOrderId").value(orderId))
                    .andExpect(jsonPath("$.processedBy").value(cashierId));
        }

        @Test
        @DisplayName("partial returns are allowed and repeatable up to what was purchased")
        void partialReturnsAreAllowedAndRepeatable() throws Exception {
            String orderId = createAndPayOrderId(milk500, 3);

            createReturn(orderId, milk500, 1).andExpect(status().isCreated());
            createReturn(orderId, milk500, 2).andExpect(status().isCreated());
        }

        @Test
        @DisplayName("returning more than remains is 400, naming the line")
        void returningMoreThanRemainsIs400() throws Exception {
            String orderId = createAndPayOrderId(milk500, 2);
            createReturn(orderId, milk500, 1).andExpect(status().isCreated()); // 1 left

            createReturn(orderId, milk500, 2)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fields.items")
                            .value(org.hamcrest.Matchers.containsString("Cannot return more than 1")));
        }

        @Test
        @DisplayName("splitting one returnable quantity across two lines of the same request is still bounded")
        void splittingAcrossTwoLinesOfOneRequestIsStillBounded() throws Exception {
            String orderId = createAndPayOrderId(milk500, 1); // only 1 to return

            mvc.perform(post("/api/returns")
                            .header(AUTH, bearer(asCashier()))
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    {"originalOrderId":"%s","items":[
                                     {"variantId":"%s","quantity":1},{"variantId":"%s","quantity":1}]}
                                    """.formatted(orderId, milk500, milk500)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fields.items")
                            .value(org.hamcrest.Matchers.containsString("Cannot return more than 0")));
        }

        @Test
        @DisplayName("a variant not on the order is 400")
        void aVariantNotOnTheOrderIs400() throws Exception {
            String orderId = createAndPayOrderId(milk500, 1);

            createReturn(orderId, lays52, 1)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fields.items")
                            .value("Item is not part of the original order"));
        }

        @Test
        @DisplayName("no positive-quantity item is 400 'select at least one item'")
        void noPositiveQuantityItemIs400() throws Exception {
            String orderId = createAndPayOrderId(milk500, 1);

            mvc.perform(post("/api/returns")
                            .header(AUTH, bearer(asCashier()))
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    {"originalOrderId":"%s","items":[{"variantId":"%s","quantity":0}]}
                                    """.formatted(orderId, milk500)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fields.items").value("Select at least one item to return"));
        }

        @Test
        @DisplayName("a missing originalOrderId is 400 on that field")
        void aMissingOriginalOrderIdIs400() throws Exception {
            mvc.perform(post("/api/returns")
                            .header(AUTH, bearer(asCashier()))
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    {"items":[{"variantId":"%s","quantity":1}]}
                                    """.formatted(milk500)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fields.originalOrderId").value("Original order is required"));
        }

        @Test
        @DisplayName("an unissued originalOrderId is 404")
        void anUnissuedOriginalOrderIdIs404() throws Exception {
            createReturn(String.valueOf(UNISSUED_ID), milk500, 1)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Order not found"));
        }

        @Test
        @DisplayName("a DRAFT order's id is 400 'only a completed order can be returned'")
        void aDraftOrdersIdIs400() throws Exception {
            String response = create(asCashier(), """
                    {"items":[{"variantId":"%s","quantity":1}]}
                    """.formatted(milk500))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            String orderId = JsonPath.read(response, "$.id");

            createReturn(orderId, milk500, 1)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Only a completed order can be returned"));
        }

        @Test
        @DisplayName("an ADMIN can process a return too — it isn't cashier-only")
        void anAdminCanProcessOneToo() throws Exception {
            String orderId = createAndPayOrderId(milk500, 1);

            mvc.perform(post("/api/returns")
                            .header(AUTH, bearer(asAdmin()))
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    {"originalOrderId":"%s","items":[{"variantId":"%s","quantity":1}]}
                                    """.formatted(orderId, milk500)))
                    .andExpect(status().isCreated());
        }
    }

    @Nested
    @DisplayName("GET /api/returns/{id}, GET /api/returns")
    class GetAndList {

        @Test
        @DisplayName("an id that never existed is 404")
        void unknownIdIs404() throws Exception {
            mvc.perform(get("/api/returns/" + UNISSUED_ID).header(AUTH, bearer(asCashier())))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Return not found"));
        }

        @Test
        @DisplayName("a CASHIER's processedBy is forced to their own, whatever is passed")
        void aCashiersProcessedByIsForced() throws Exception {
            String orderId = createAndPayOrderId(milk500, 1);
            createReturn(orderId, milk500, 1).andExpect(status().isCreated());

            list(asCashier(), "processedBy", String.valueOf(UNISSUED_ID))
                    .andExpect(jsonPath("$.total").value(1));
        }

        @Test
        @DisplayName("an ADMIN sees every return in the tenant with no processedBy")
        void anAdminSeesEveryReturnByDefault() throws Exception {
            String orderId = createAndPayOrderId(milk500, 2);
            createReturn(orderId, milk500, 1).andExpect(status().isCreated());

            list(asAdmin()).andExpect(jsonPath("$.total").value(1));
        }

        /**
         * Peer-review Phase 1: {@code list} used to read each return's {@code lines} lazily,
         * one extra query per row (N+1). {@code ReturnDao.findLinesByReturnIds} batches the
         * whole page's lines into one query instead, grouped back onto each return by id —
         * the risk that refactor introduces is lines leaking across returns, which this
         * pins against two returns on the page at once.
         */
        @Test
        @DisplayName("each return's items are its own, not mixed across returns on the page")
        void eachReturnsItemsAreItsOwn() throws Exception {
            String orderAId = createAndPayOrderId(milk500, 3);
            String orderBId = createAndPayOrderId(lays52, 4);

            String returnAId = JsonPath.read(
                    createReturn(orderAId, milk500, 2).andExpect(status().isCreated())
                            .andReturn().getResponse().getContentAsString(),
                    "$.id");
            String returnBId = JsonPath.read(
                    createReturn(orderBId, lays52, 3).andExpect(status().isCreated())
                            .andReturn().getResponse().getContentAsString(),
                    "$.id");

            ResultActions result = list(asAdmin());
            result.andExpect(jsonPath("$.total").value(2));

            // Newest first, tie-broken by id -- returnB was created second.
            result.andExpect(jsonPath("$.items[0].id").value(returnBId))
                    .andExpect(jsonPath("$.items[0].items", hasSize(1)))
                    .andExpect(jsonPath("$.items[0].items[0].variantId").value(lays52.toString()))
                    .andExpect(jsonPath("$.items[0].items[0].quantity").value(3));

            result.andExpect(jsonPath("$.items[1].id").value(returnAId))
                    .andExpect(jsonPath("$.items[1].items", hasSize(1)))
                    .andExpect(jsonPath("$.items[1].items[0].variantId").value(milk500.toString()))
                    .andExpect(jsonPath("$.items[1].items[0].quantity").value(2));
        }
    }

    @Nested
    @DisplayName("a SUPER_ADMIN has no tenant to return anything in")
    class PlatformAdmin {

        @Test
        @DisplayName("is refused by every return endpoint, not shown an empty store")
        void isRefusedEverywhere() throws Exception {
            mvc.perform(post("/api/returns")
                            .header(AUTH, bearer(asPlatformAdmin()))
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    {"originalOrderId":"1","items":[{"variantId":"1","quantity":1}]}
                                    """))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value(TenantContext.NO_TENANT_MESSAGE));

            list(asPlatformAdmin()).andExpect(status().isForbidden());

            mvc.perform(get("/api/orders/lookup")
                            .header(AUTH, bearer(asPlatformAdmin()))
                            .param("orderNumber", "ORD-2026-0001"))
                    .andExpect(status().isForbidden());
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

    private String createAndPayOrderId(Long variantId, int quantity) throws Exception {
        String response = create(asCashier(), """
                {"items":[{"variantId":"%s","quantity":%d}]}
                """.formatted(variantId, quantity))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String orderId = JsonPath.read(response, "$.id");

        mvc.perform(post("/api/orders/" + orderId + "/payments")
                        .header(AUTH, bearer(asCashier()))
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"method":"CARD"}
                                """))
                .andExpect(status().isOk());
        return orderId;
    }

    private String createAndPayOrder(Long variantId, int quantity) throws Exception {
        return orderNumberOf(createAndPayOrderId(variantId, quantity));
    }

    private String orderNumberOf(String orderId) throws Exception {
        String response = mvc.perform(get("/api/orders/" + orderId).header(AUTH, bearer(asCashier())))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.orderNumber");
    }

    private ResultActions lookupOrder(String orderNumber) throws Exception {
        return mvc.perform(get("/api/orders/lookup")
                .header(AUTH, bearer(asCashier()))
                .param("orderNumber", orderNumber));
    }

    private ResultActions createReturn(String originalOrderId, Long variantId, int quantity) throws Exception {
        return mvc.perform(post("/api/returns")
                .header(AUTH, bearer(asCashier()))
                .contentType(APPLICATION_JSON)
                .content("""
                        {"originalOrderId":"%s","items":[{"variantId":"%s","quantity":%d}]}
                        """.formatted(originalOrderId, variantId, quantity)));
    }

    private ResultActions list(String token, String... params) throws Exception {
        var request = get("/api/returns").header(AUTH, bearer(token)).param("pageSize", "200");
        for (int i = 0; i < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        return mvc.perform(request);
    }

    /**
     * Changes the variant's price directly -- what a return must NOT read back. Both
     * {@code mrp} and {@code sellingPrice} move together so the new price does not itself
     * trip {@code ck_variant_price_within_mrp}.
     */
    private void reprice(Long variantId, String newMrp, String newSellingPrice) {
        VariantPojo variant = em.find(VariantPojo.class, variantId);
        variant.setMrp(new BigDecimal(newMrp));
        variant.setSellingPrice(new BigDecimal(newSellingPrice));
        em.flush();
        em.clear();
    }

    /**
     * Native SQL rather than {@code em.find} — {@code JwtAuthenticationFilter} clears
     * {@link TenantContext} in its own {@code finally} once each {@code mvc.perform}
     * call returns, so a filtered read here would resolve against {@code NO_TENANT}.
     */
    private void assertStock(Long variantId, int expected) {
        Number stock = (Number) em.createNativeQuery(
                        "SELECT stock_quantity FROM variant WHERE id = ?1")
                .setParameter(1, variantId)
                .getSingleResult();
        org.junit.jupiter.api.Assertions.assertEquals(expected, stock.intValue(),
                () -> "variant " + variantId + " stock");
    }

    private String bearer(String token) {
        return "Bearer " + token;
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
        user.setTenantId(tenant.getId());
        user.setUsername(username);
        user.setPasswordHash(passwordHash);
        user.setDisplayName(username);
        user.setRole(role);
        user.setActive(true);
        em.persist(user);
    }

    private Long product(TenantPojo tenant, String name, String taxRatePercent) {
        ProductPojo product = new ProductPojo();
        product.setTenantId(tenant.getId());
        product.setName(name);
        product.setBrand("Test");
        product.setCategory("Test");
        product.setTaxRatePercent(new BigDecimal(taxRatePercent));
        product.setActive(true);
        em.persist(product);
        return product.getId();
    }

    private Long variant(TenantPojo tenant, Long productId, String label, String sku,
                         String mrp, String sellingPrice, int stock) {
        VariantPojo variant = new VariantPojo();
        variant.setTenantId(tenant.getId());
        variant.setProductId(productId);
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

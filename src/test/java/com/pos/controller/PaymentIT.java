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
import com.pos.pojo.AppUser;
import com.pos.pojo.Product;
import com.pos.pojo.Role;
import com.pos.pojo.Tenant;
import com.pos.pojo.TenantStatus;
import com.pos.pojo.UnitOfMeasure;
import com.pos.pojo.Variant;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /api/orders/{id}/payments} (C6) — the port of {@code paymentService.js},
 * plus the hard stock check the mock had no ledger to enforce.
 *
 * <p><b>This is where inventory actually moves</b>, which is what the suite is really
 * about: not just that a payment answers the right JSON, but that stock is decremented by
 * exactly the paid quantity, that a shortfall is rejected atomically, and that a rejected
 * payment leaves <i>every</i> line's stock exactly as it found it — including the lines
 * that individually had enough. See {@code PaymentService.decrementStock}'s Javadoc for
 * why one statement handles both the check and the mutation.
 *
 * <h2>Not {@code @Transactional}, and it cannot be — same reasoning as
 * {@code VariantSequenceIT}</h2>
 * The rollback case is the one this class exists for, and it cannot be observed under a
 * wrapping test transaction. {@code PaymentService.pay} joins whatever transaction is
 * already open (the default {@code REQUIRED} propagation); under a class-level
 * {@code @Transactional} test, that is the <i>test's own</i> transaction, so a failing
 * {@code pay()} only marks it rollback-only — the physical {@code ROLLBACK} is deferred to
 * the outermost boundary, which is the test method's end, not the moment the exception
 * propagates. A read on the same connection in between (raw SQL included) still sees the
 * earlier, uncommitted decrement, because nothing has actually been undone yet. In
 * production {@code pay()} <i>is</i> the outermost boundary, so its rollback is real and
 * immediate — which is what a real {@code mvn jetty:run} + {@code curl} session confirmed
 * by hand before this suite was written (see {@code prompts/c6-orders.md}). Fixtures are
 * therefore built and torn down with a real, committed {@link TransactionTemplate}, exactly
 * like {@code VariantSequenceIT}, so every {@code mvc.perform} call here is genuinely the
 * outermost transaction MockMvc's request thread opens — the same shape production has.
 */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = {
        RootConfig.class, PersistenceConfig.class, SecurityConfig.class, MailConfig.class,
        RecaptchaConfig.class,
        WebConfig.class, OpenApiConfig.class })
@TestPropertySource("classpath:application-test.properties")
@DisplayName("POST /api/orders/{id}/payments")
class PaymentIT {

    private static final BCryptPasswordEncoder HASHER = new BCryptPasswordEncoder();

    private static final long UNISSUED_ID = 9_999_999L;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager em;

    private TransactionTemplate transactions;
    private MockMvc mvc;

    private Long milk500;
    private Long lays52;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        transactions = new TransactionTemplate(transactionManager);

        Long[] ids = transactions.execute(status -> {
            Tenant platform = tenant("Platform", Tenant.PLATFORM_CODE, true);
            Tenant mgRoad = tenant("MG Road Store", "mg-road", false);

            user(platform, "superadmin", "super123", Role.SUPER_ADMIN);
            user(mgRoad, "admin", "admin123", Role.ADMIN);
            user(mgRoad, "cashier", "cashier123", Role.CASHIER);

            Long milkProduct = product(mgRoad, "Amul Taaza Toned Milk", "5.00");
            Long milkVariant = variant(mgRoad, milkProduct, "500 ml", "AMUL-MILK-500",
                    "30.00", "29.00", 40);
            Long laysProduct = product(mgRoad, "Lay's Classic Salted", "12.00");
            // Deliberately thin stock -- one unit, so a two-unit line is a clean,
            // immediate shortfall without needing to exhaust anything first.
            Long laysVariant = variant(mgRoad, laysProduct, "52 g", "LAYS-SALT-52",
                    "20.00", "20.00", 1);

            return new Long[] { milkVariant, laysVariant };
        });

        milk500 = ids[0];
        lays52 = ids[1];
    }

    @AfterEach
    void tearDown() {
        transactions.executeWithoutResult(status -> {
            // FK order: lines before their parents, sequences and catalogue rows before
            // the tenant they point at. Native SQL because a bulk HQL delete would carry
            // whatever tenant (or none) is on this thread and quietly delete nothing --
            // the identical reasoning VariantSequenceIT's teardown documents.
            em.createNativeQuery("DELETE FROM order_line").executeUpdate();
            em.createNativeQuery("DELETE FROM pos_order").executeUpdate();
            em.createNativeQuery("DELETE FROM variant").executeUpdate();
            em.createNativeQuery("DELETE FROM tenant_sequence").executeUpdate();
            em.createNativeQuery("DELETE FROM product").executeUpdate();
            em.createNativeQuery("DELETE FROM app_user").executeUpdate();
            em.createNativeQuery("DELETE FROM tenant").executeUpdate();
        });
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Nested
    @DisplayName("a successful payment")
    class SuccessfulPayment {

        @Test
        @DisplayName("CASH: records amount, tendered and change, and completes the order")
        void cashRecordsAmountTenderedAndChange() throws Exception {
            String orderId = createOrder(milk500, 2); // 2x29 = 58

            pay(orderId, """
                    {"method":"CASH","amountTendered":100}
                    """)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("COMPLETED"))
                    .andExpect(jsonPath("$.payment.method").value("CASH"))
                    .andExpect(jsonPath("$.payment.amount").value(58))
                    .andExpect(jsonPath("$.payment.amountTendered").value(100))
                    .andExpect(jsonPath("$.payment.change").value(42))
                    .andExpect(jsonPath("$.payment.reference").doesNotExist());
        }

        @Test
        @DisplayName("CARD: amount equals grandTotal, no tendered check, a dummy reference")
        void cardNeedsNoTenderedAndGetsADummyReference() throws Exception {
            String orderId = createOrder(milk500, 1); // 29

            pay(orderId, """
                    {"method":"CARD"}
                    """)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.payment.amount").value(29))
                    .andExpect(jsonPath("$.payment.amountTendered").value(29))
                    .andExpect(jsonPath("$.payment.change").value(0))
                    .andExpect(jsonPath("$.payment.reference",
                            org.hamcrest.Matchers.startsWith("MOCK-CARD-")));
        }

        @Test
        @DisplayName("a supplied reference is used verbatim")
        void aSuppliedReferenceIsUsedVerbatim() throws Exception {
            String orderId = createOrder(milk500, 1);

            pay(orderId, """
                    {"method":"UPI","reference":"UPI-TEST-REF-1"}
                    """)
                    .andExpect(jsonPath("$.payment.reference").value("UPI-TEST-REF-1"));
        }

        @Test
        @DisplayName("decrements stock by exactly the paid quantity")
        void decrementsStockByExactlyThePaidQuantity() throws Exception {
            String orderId = createOrder(milk500, 3);

            pay(orderId, """
                    {"method":"CARD"}
                    """).andExpect(status().isOk());

            assertStock(milk500, 37);
        }

        @Test
        @DisplayName("re-priced HELD order pays for the CURRENT lines, not the original ones")
        void paysForTheCurrentLinesAfterAHold() throws Exception {
            String orderId = createOrder(milk500, 1); // 29

            mvc.perform(patch("/api/orders/" + orderId)
                            .header(AUTH, bearer(asCashier()))
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    {"items":[{"variantId":"%s","quantity":3}],"status":"HELD"}
                                    """.formatted(milk500)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.grandTotal").value(87)); // 3x29

            pay(orderId, """
                    {"method":"CARD"}
                    """)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.payment.amount").value(87));

            assertStock(milk500, 37); // 40 - 3, the re-priced quantity, not the original 1
        }
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        @DisplayName("CASH with insufficient tendered is 400 on the amountTendered field")
        void cashWithInsufficientTenderedIs400() throws Exception {
            String orderId = createOrder(milk500, 2); // 58

            pay(orderId, """
                    {"method":"CASH","amountTendered":50}
                    """)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fields.amountTendered")
                            .value("Amount tendered is less than the total due"));
        }

        @Test
        @DisplayName("a missing method is 400 on the method field")
        void aMissingMethodIs400() throws Exception {
            String orderId = createOrder(milk500, 1);

            pay(orderId, "{}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fields.method").value("Choose a payment method"));
        }

        /**
         * Peer-review Phase 1: pos_order.payment_reference is VARCHAR(64); the mock had
         * no schema to bound it against.
         */
        @Test
        @DisplayName("an overlong reference is a clean 400, not an unmapped 500")
        void anOverlongReferenceIs400() throws Exception {
            String orderId = createOrder(milk500, 1);

            pay(orderId, """
                    {"method":"UPI","reference":"%s"}
                    """.formatted("R".repeat(65)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fields.reference").value("Reference must be 64 characters or fewer"));
        }

        @Test
        @DisplayName("paying twice is 400 'Order is already paid', and doesn't double-decrement")
        void payingTwiceIsRejected() throws Exception {
            String orderId = createOrder(milk500, 2);
            pay(orderId, """
                    {"method":"CARD"}
                    """).andExpect(status().isOk());

            pay(orderId, """
                    {"method":"CARD"}
                    """)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Order is already paid"));

            assertStock(milk500, 38); // decremented once, not twice
        }

        @Test
        @DisplayName("a completed order can no longer be patched")
        void aCompletedOrderCanNoLongerBePatched() throws Exception {
            String orderId = createOrder(milk500, 1);
            pay(orderId, """
                    {"method":"CARD"}
                    """).andExpect(status().isOk());

            mvc.perform(patch("/api/orders/" + orderId)
                            .header(AUTH, bearer(asCashier()))
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    {"orderDiscount":1}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("A completed order can no longer be modified"));
        }

        @Test
        @DisplayName("an id that never existed is 404")
        void unknownIdIs404() throws Exception {
            pay(String.valueOf(UNISSUED_ID), """
                    {"method":"CARD"}
                    """)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Order not found"));
        }

        @Test
        @DisplayName("a SUPER_ADMIN cannot pay — no tenant to hold the order in")
        void aPlatformAdminCannotPay() throws Exception {
            String orderId = createOrder(milk500, 1);

            mvc.perform(post("/api/orders/" + orderId + "/payments")
                            .header(AUTH, bearer(asPlatformAdmin()))
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    {"method":"CARD"}
                                    """))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value(TenantContext.NO_TENANT_MESSAGE));
        }
    }

    /**
     * <b>The case this whole suite exists for.</b> {@code PaymentService.decrementStock}
     * loops an order's lines inside {@code pay()}'s own transaction; a shortfall on any
     * line must roll back every decrement <i>already applied in that same call</i>, not
     * merely fail to apply its own. Asserted by checking the line that <b>did</b> have
     * enough stock, not the one that didn't — a test that only checked the failing line
     * would pass even if the working line's decrement had leaked through. Observable at all
     * only because this class is not itself {@code @Transactional} — see the class Javadoc.
     */
    @Nested
    @DisplayName("insufficient stock")
    class InsufficientStock {

        @Test
        @DisplayName("rejects with 400 naming the short item")
        void rejectsWithTheShortItemNamed() throws Exception {
            String orderId = createOrder(lays52, 2); // only 1 in stock

            pay(orderId, """
                    {"method":"CARD"}
                    """)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fields.items")
                            .value(org.hamcrest.Matchers.containsString(
                                    "Insufficient stock for Lay's Classic Salted")));
        }

        @Test
        @DisplayName("leaves stock untouched, including the order's status")
        void leavesStockAndStatusUntouched() throws Exception {
            String orderId = createOrder(lays52, 2);

            pay(orderId, """
                    {"method":"CARD"}
                    """).andExpect(status().isBadRequest());

            assertStock(lays52, 1);
            getOrder(orderId).andExpect(jsonPath("$.status").value("DRAFT"));
        }

        @Test
        @DisplayName("a multi-line order rolls back a line that DID have enough stock")
        void rollsBackALineThatHadEnoughStock() throws Exception {
            // milk500 (40 in stock) has plenty; lays52 (1 in stock) does not. The whole
            // payment must fail, and milk500's stock must come back exactly as it was --
            // not decremented by 2 and left that way.
            String orderId = createTwoLineOrder(milk500, 2, lays52, 5);

            pay(orderId, """
                    {"method":"CARD"}
                    """)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fields.items")
                            .value(org.hamcrest.Matchers.containsString("Lay's")));

            assertStock(milk500, 40);
            assertStock(lays52, 1);
        }
    }

    // --- helpers -----------------------------------------------------------------

    private static final String AUTH = "Authorization";

    private ResultActions pay(String orderId, String body) throws Exception {
        return mvc.perform(post("/api/orders/" + orderId + "/payments")
                .header(AUTH, bearer(asCashier()))
                .contentType(APPLICATION_JSON)
                .content(body));
    }

    private ResultActions getOrder(String orderId) throws Exception {
        return mvc.perform(get("/api/orders/" + orderId).header(AUTH, bearer(asCashier())));
    }

    private String createOrder(Long variantId, int quantity) throws Exception {
        String response = mvc.perform(post("/api/orders")
                        .header(AUTH, bearer(asCashier()))
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"items":[{"variantId":"%s","quantity":%d}]}
                                """.formatted(variantId, quantity)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    private String createTwoLineOrder(Long variantId1, int qty1, Long variantId2, int qty2) throws Exception {
        String response = mvc.perform(post("/api/orders")
                        .header(AUTH, bearer(asCashier()))
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"items":[{"variantId":"%s","quantity":%d},
                                           {"variantId":"%s","quantity":%d}]}
                                """.formatted(variantId1, qty1, variantId2, qty2)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    /**
     * Native SQL rather than {@code em.find} — {@code JwtAuthenticationFilter} clears
     * {@link TenantContext} in its own {@code finally} the moment each {@code mvc.perform}
     * call returns, so a filtered {@code em.find} here would resolve against
     * {@code NO_TENANT} and answer {@code null} for a row that exists. Native SQL bypasses
     * the Hibernate filter entirely.
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

    private String asCashier() throws Exception {
        return tokenFor("mg-road", "cashier", "cashier123");
    }

    private String asPlatformAdmin() throws Exception {
        return tokenFor(Tenant.PLATFORM_CODE, "superadmin", "super123");
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

    private Tenant tenant(String name, String code, boolean platform) {
        Tenant tenant = new Tenant();
        tenant.setName(name);
        tenant.setCode(code);
        tenant.setStatus(TenantStatus.ACTIVE);
        tenant.setPlatform(platform);
        em.persist(tenant);
        return tenant;
    }

    private void user(Tenant tenant, String username, String password, Role role) {
        AppUser user = new AppUser();
        user.setTenant(tenant);
        user.setUsername(username);
        user.setPasswordHash(HASHER.encode(password));
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

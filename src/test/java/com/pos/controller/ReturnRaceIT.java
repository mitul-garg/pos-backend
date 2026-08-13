package com.pos.controller;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * <b>Returning the same order from two requests at once</b> (C7) — the fourth
 * concurrency suite, after {@code TenantThreadLocalIT}, {@code VariantSequenceIT} and
 * {@code StockRaceIT}.
 *
 * <p>{@code ReturnService.create} locks the original order row
 * ({@code OrderDao.findForUpdate}) before reading how much of it has already been
 * returned, precisely so this cannot happen: two returns against one order, submitted
 * at the same instant, must not both see the same unreturned baseline and together
 * refund more than was purchased. Without the lock this is the identical shape as the
 * stock race {@code StockRaceIT} proves — a read, a check and a write with a gap for a
 * second transaction to land in — except the referee here is a row lock rather than an
 * atomic {@code UPDATE ... WHERE}, because "how much remains returnable" is a sum over
 * several rows, not one column on one row.
 *
 * <h2>Not {@code @Transactional}, and it cannot be — same reasoning as {@code StockRaceIT}</h2>
 * The worker threads must see a committed fixture and must genuinely commit or block
 * against the database for the lock to be real; a wrapping test transaction would hide
 * the fixture from the worker connections entirely. Fixtures are built and torn down
 * with a real, committed {@link TransactionTemplate}, exactly like {@code StockRaceIT}.
 */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = {
        RootConfig.class, PersistenceConfig.class, SecurityConfig.class, MailConfig.class,
        RecaptchaConfig.class,
        WebConfig.class, OpenApiConfig.class })
@TestPropertySource("classpath:application-test.properties")
@DisplayName("returning the same order under concurrency")
class ReturnRaceIT {

    /** Two racers, matching {@code pos.db.pool.maxSize} -- see StockRaceIT's note. */
    private static final int THREADS = 2;

    /** Rounds of two returns racing the same order. */
    private static final int RACES = 5;

    private static final BCryptPasswordEncoder HASHER = new BCryptPasswordEncoder();

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager em;

    private TransactionTemplate transactions;
    private MockMvc mvc;

    private String cashierToken;
    private Long productId;
    private Long tenantId;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        transactions = new TransactionTemplate(transactionManager);

        Long[] ids = transactions.execute(status -> {
            TenantPojo mgRoad = tenant("MG Road Store", "mg-road");
            user(mgRoad, "cashier", "cashier123", Role.CASHIER);
            return new Long[] { mgRoad.getId(), product(mgRoad, "Amul Taaza Toned Milk") };
        });
        tenantId = ids[0];
        productId = ids[1];

        cashierToken = tokenFor("mg-road", "cashier", "cashier123");
    }

    @AfterEach
    void tearDown() {
        transactions.executeWithoutResult(status -> {
            // FK order: lines before their parents, sequences and catalogue rows before
            // the tenant. Native SQL for the same reason StockRaceIT's teardown gives.
            em.createNativeQuery("DELETE FROM return_line").executeUpdate();
            em.createNativeQuery("DELETE FROM sales_return").executeUpdate();
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

    /**
     * <b>The headline.</b> One order for 4 units, two returns for 3 units each fired at
     * the same instant — together they ask for 6 against a purchase of 4. Exactly one
     * must succeed and exactly one must be rejected with the ordinary
     * "cannot return more than N" 400 (N being whatever the loser sees left after the
     * winner's return lands), never both succeeding and never both rejected. Stock must
     * land on exactly 4 minus whatever the winning return actually took, never counted
     * twice.
     *
     * <p>Repeated {@code RACES} times with a fresh order each round, since a single race
     * winning "by luck" — the two threads happening to serialize rather than truly
     * overlap — would let a missing lock pass once.
     */
    @Test
    @DisplayName("two returns racing the same order: exactly one completes, one gets a clean 400")
    void racingTheSameOrderProducesOneWinnerAndOneCleanRejection() throws Exception {
        for (int round = 0; round < RACES; round++) {
            int roundNumber = round;
            Long variantId = transactions.execute(status ->
                    variant(productId, "RACE-" + roundNumber, 100));

            String orderId = createAndPayOrder(variantId, 4);

            List<Callable<MvcResult>> calls = List.of(
                    () -> createReturn(orderId, variantId, 3),
                    () -> createReturn(orderId, variantId, 3));

            int completed = 0;
            int rejected = 0;
            for (MvcResult result : runConcurrently(calls)) {
                String body = result.getResponse().getContentAsString();
                switch (result.getResponse().getStatus()) {
                    case 201 -> completed++;
                    case 400 -> {
                        rejected++;
                        assertEquals(true,
                                body.contains("Cannot return more than"),
                                "the loser must get the ordinary over-return 400, "
                                        + "not a server error: " + body);
                    }
                    default -> throw new AssertionError(
                            "unexpected status " + result.getResponse().getStatus()
                                    + " -- a lost race must not surface as a server error: "
                                    + body);
                }
            }

            assertEquals(1, completed,
                    "round " + roundNumber + ": exactly one of the two returns should have completed");
            assertEquals(1, rejected,
                    "round " + roundNumber + ": exactly one of the two returns should have been rejected");
            // 100 (fixture) - 4 (the sale) + 3 (the ONE winning return) = 99, never 102.
            assertStock(variantId, 99);
        }
    }

    // --- helpers -----------------------------------------------------------------

    private List<MvcResult> runConcurrently(List<Callable<MvcResult>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<MvcResult> results = new ArrayList<>();
            for (Future<MvcResult> future : pool.invokeAll(calls, 60, TimeUnit.SECONDS)) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private String createAndPayOrder(Long variantId, int quantity) throws Exception {
        String response = mvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + cashierToken)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"items":[{"variantId":"%s","quantity":%d}]}
                                """.formatted(variantId, quantity)))
                .andReturn().getResponse().getContentAsString();
        String orderId = JsonPath.read(response, "$.id");

        mvc.perform(post("/api/orders/" + orderId + "/payments")
                        .header("Authorization", "Bearer " + cashierToken)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"method":"CARD"}
                                """))
                .andReturn();
        return orderId;
    }

    /**
     * Through the endpoint, deliberately -- same reasoning as {@code StockRaceIT.pay}:
     * the pessimistic lock has to run inside {@code ReturnService.create}'s own
     * transaction boundary, which nothing but the real {@code @Transactional} method
     * recreates.
     */
    private MvcResult createReturn(String orderId, Long variantId, int quantity) throws Exception {
        return mvc.perform(post("/api/returns")
                        .header("Authorization", "Bearer " + cashierToken)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"originalOrderId":"%s","items":[{"variantId":"%s","quantity":%d}]}
                                """.formatted(orderId, variantId, quantity)))
                .andReturn();
    }

    /**
     * Native SQL rather than {@code em.find} — same reason as {@code StockRaceIT.assertStock}:
     * {@code JwtAuthenticationFilter} clears {@link TenantContext} once each request
     * returns, so a filtered read here would resolve against {@code NO_TENANT}.
     */
    private void assertStock(Long variantId, int expected) {
        Number stock = (Number) em.createNativeQuery(
                        "SELECT stock_quantity FROM variant WHERE id = ?1")
                .setParameter(1, variantId)
                .getSingleResult();
        assertEquals(expected, stock.intValue(), () -> "variant " + variantId + " stock");
    }

    private String tokenFor(String tenantCode, String username, String password) throws Exception {
        String body = """
                {"tenantCode":"%s","username":"%s","password":"%s"}
                """.formatted(tenantCode, username, password);
        String response = mvc.perform(post("/api/auth/login").with(TestIps.remoteAddr(TestIps.fresh())).contentType(APPLICATION_JSON).content(body))
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

    private void user(TenantPojo tenant, String username, String password, Role role) {
        AppUserPojo user = new AppUserPojo();
        user.setTenant(tenant);
        user.setUsername(username);
        user.setPasswordHash(HASHER.encode(password));
        user.setDisplayName(username);
        user.setRole(role);
        user.setActive(true);
        em.persist(user);
    }

    private Long product(TenantPojo tenant, String name) {
        ProductPojo product = new ProductPojo();
        product.setTenantId(tenant.getId());
        product.setName(name);
        product.setBrand("Amul");
        product.setCategory("Dairy");
        product.setTaxRatePercent(new BigDecimal("5.00"));
        product.setActive(true);
        em.persist(product);
        return product.getId();
    }

    private Long variant(Long productId, String sku, int stock) {
        VariantPojo variant = new VariantPojo();
        variant.setTenant(em.getReference(TenantPojo.class, tenantId));
        variant.setProduct(em.getReference(ProductPojo.class, productId));
        variant.setVariantLabel(sku);
        variant.setSku(sku);
        variant.setQrCode("POS-QR-" + tenantId + "-" + sku);
        variant.setMrp(new BigDecimal("30.00"));
        variant.setSellingPrice(new BigDecimal("29.00"));
        variant.setStockQuantity(stock);
        variant.setUnitOfMeasure(UnitOfMeasure.EACH);
        variant.setActive(true);
        em.persist(variant);
        return variant.getId();
    }
}

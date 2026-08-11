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
 * <b>The stock decrement under real contention</b> (backend-plan.md section 7) — the third
 * test the frontend's mock could never have written, after {@code TenantThreadLocalIT} and
 * {@code VariantSequenceIT}.
 *
 * <p>{@code PaymentService.decrementStock}'s whole reason for being one conditional
 * {@code UPDATE} rather than a read-then-write is to survive exactly this: two cashiers
 * racing the last unit of the same variant. A read-then-write would let both transactions
 * read {@code stock_quantity = 1}, both decide there is enough, and both commit -- selling
 * the same unit twice. The atomic {@code WHERE stock_quantity >= ?} makes the database
 * itself the referee: whichever {@code UPDATE} commits first changes the row the second one
 * reads, so the second's {@code WHERE} clause matches zero rows and it is rejected cleanly,
 * with no lock held across the check-and-decrement gap.
 *
 * <h2>Not {@code @Transactional}, and it cannot be -- same reasoning as {@code PaymentIT}
 * and {@code VariantSequenceIT}</h2>
 * The worker threads must see a committed fixture and must genuinely commit (or roll back)
 * against the database for the race to be real; a wrapping test transaction would either
 * hide the fixture from the worker connections or absorb their rollbacks into one shared,
 * still-open transaction. Fixtures are built and torn down with a real, committed
 * {@link TransactionTemplate}, exactly like {@code PaymentIT}.
 */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = {
        RootConfig.class, PersistenceConfig.class, SecurityConfig.class, MailConfig.class,
        WebConfig.class, OpenApiConfig.class })
@TestPropertySource("classpath:application-test.properties")
@DisplayName("the stock decrement under concurrency")
class StockRaceIT {

    /** Two racers, matching {@code pos.db.pool.maxSize} -- see VariantSequenceIT's note. */
    private static final int THREADS = 2;

    /** Rounds of two cashiers racing one unit. */
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
            Tenant mgRoad = tenant("MG Road Store", "mg-road");
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
            // FK order: lines and orders before the variant/product they point at,
            // sequences and catalogue rows before the tenant. Native SQL for the same
            // reason PaymentIT's and VariantSequenceIT's teardowns give.
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
     * <b>The headline.</b> One unit in stock, two DRAFT orders for one unit each, two
     * payments fired at the same instant: exactly one must complete and exactly one must be
     * rejected with the ordinary insufficient-stock 400, never both completing and never
     * both rejected, and the row must land on zero -- not go negative and not stay at one.
     *
     * <p>Repeated {@code RACES} times with a fresh variant each round, since a single race
     * winning "by luck" (the two threads happening to serialize rather than truly overlap)
     * would let a broken read-then-write pass once.
     */
    @Test
    @DisplayName("two payments racing the last unit: exactly one completes, one gets a clean 400")
    void racingTheLastUnitProducesOneWinnerAndOneCleanRejection() throws Exception {
        for (int round = 0; round < RACES; round++) {
            int roundNumber = round;
            Long variantId = transactions.execute(status ->
                    variant(productId, "RACE-" + roundNumber, 1));

            String orderA = createOrder(variantId);
            String orderB = createOrder(variantId);

            List<Callable<MvcResult>> calls = List.of(
                    () -> pay(orderA),
                    () -> pay(orderB));

            int completed = 0;
            int rejected = 0;
            for (MvcResult result : runConcurrently(calls)) {
                String body = result.getResponse().getContentAsString();
                switch (result.getResponse().getStatus()) {
                    case 200 -> completed++;
                    case 400 -> {
                        rejected++;
                        assertEquals(true,
                                body.contains("Insufficient stock"),
                                "the loser must get the ordinary insufficient-stock 400, "
                                        + "not a server error: " + body);
                    }
                    default -> throw new AssertionError(
                            "unexpected status " + result.getResponse().getStatus()
                                    + " -- a lost race must not surface as a server error: "
                                    + body);
                }
            }

            assertEquals(1, completed,
                    "round " + roundNumber + ": exactly one of the two payments should have completed");
            assertEquals(1, rejected,
                    "round " + roundNumber + ": exactly one of the two payments should have been rejected");
            assertStock(variantId, 0);
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

    private String createOrder(Long variantId) throws Exception {
        String response = mvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + cashierToken)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"items":[{"variantId":"%s","quantity":1}]}
                                """.formatted(variantId)))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    /**
     * Through the endpoint, deliberately -- same reasoning as {@code VariantSequenceIT}'s
     * {@code createVariant}: the conditional decrement has to run inside {@code pay()}'s
     * own transaction boundary, which is defined by the {@code @Transactional} on
     * {@code PaymentService}, not by anything a test can recreate by calling a DAO directly.
     */
    private MvcResult pay(String orderId) throws Exception {
        return mvc.perform(post("/api/orders/" + orderId + "/payments")
                        .header("Authorization", "Bearer " + cashierToken)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"method":"CARD"}
                                """))
                .andReturn();
    }

    /**
     * Native SQL rather than {@code em.find} -- same reason as {@code PaymentIT.assertStock}:
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
        String response = mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString();
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        return JsonPath.read(response, "$.token");
    }

    private Tenant tenant(String name, String code) {
        Tenant tenant = new Tenant();
        tenant.setName(name);
        tenant.setCode(code);
        tenant.setStatus(TenantStatus.ACTIVE);
        tenant.setPlatform(false);
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

    private Long product(Tenant tenant, String name) {
        Product product = new Product();
        product.setTenant(tenant);
        product.setName(name);
        product.setBrand("Amul");
        product.setCategory("Dairy");
        product.setTaxRatePercent(new BigDecimal("5.00"));
        product.setActive(true);
        em.persist(product);
        return product.getId();
    }

    private Long variant(Long productId, String sku, int stock) {
        Variant variant = new Variant();
        variant.setTenant(em.getReference(Tenant.class, tenantId));
        variant.setProduct(em.getReference(Product.class, productId));
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

package com.pos.dao;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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
 * <b>The per-tenant sequence under real contention</b> (backend-plan.md section 4) — the
 * second of the tests the frontend's mock could never have written, after
 * {@code TenantThreadLocalIT}.
 *
 * <p>Going multi-tenant meant giving up {@code AUTO_INCREMENT} for QR codes, order numbers
 * and return numbers — a concurrency-safe generator the database gave us free — and
 * replacing it with something we have to make safe ourselves. C5 is where that replacement
 * first runs, so this is where it first gets tested; <b>C6 and C7 reuse
 * {@link TenantSequenceDao} unchanged for the numbers that end up printed on invoices</b>,
 * and the failure there is a duplicate order number rather than a duplicate label.
 *
 * <p>The mechanism has two halves and they fail differently, so there is a test for each:
 *
 * <ul>
 *   <li><b>The lock reserves a value</b> — without {@code SELECT … FOR UPDATE}, two
 *       transactions both read the same {@code next_value} and mint the same code. MySQL's
 *       default REPEATABLE READ does not prevent it: a plain {@code SELECT} is a
 *       non-locking snapshot read, so both seeing the same row is defined behaviour.</li>
 *   <li><b>The unique index is the referee</b> — for anything a caller chooses rather than
 *       the server, a service-level "is this free?" is a read and a write with a gap. What
 *       matters is that the loser gets the same clean 400 as an uncontended duplicate,
 *       not a 500 with a stack trace in the log.</li>
 * </ul>
 *
 * <h2>Not {@code @Transactional}, and it cannot be</h2>
 * The worker threads must see committed fixtures, and a test-managed transaction is never
 * committed — the same reasoning as {@code TenantThreadLocalIT}, whose teardown notes apply
 * here too: native SQL, because a bulk HQL {@code DELETE} would carry the tenant filter
 * from a thread that has no tenant and quietly delete nothing.
 */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = {
        RootConfig.class, PersistenceConfig.class, SecurityConfig.class, MailConfig.class,
        RecaptchaConfig.class, ImagesConfig.class,
        WebConfig.class, OpenApiConfig.class })
@TestPropertySource("classpath:application-test.properties")
@DisplayName("the per-tenant QR sequence under concurrency")
class VariantSequenceIT {

    /**
     * Two, matching {@code pos.db.pool.maxSize} in the test properties. More workers than
     * connections would queue on Hikari rather than contend on the row lock — and two
     * transactions is all it takes: the race is one reading {@code next_value} while the
     * other has read it and not yet written it back.
     */
    private static final int THREADS = 2;

    /** Enough passes that a lost update would have to be lucky ten times to hide. */
    private static final int CREATES = 10;

    /** Rounds of two callers racing one SKU. */
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

    private String adminToken;
    private String tenantId;
    private Long productId;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        transactions = new TransactionTemplate(transactionManager);

        Long[] ids = transactions.execute(status -> {
            TenantPojo mgRoad = tenant("MG Road Store", "mg-road");
            user(mgRoad, "admin", "admin123", Role.ADMIN);
            return new Long[] { mgRoad.getId(), product(mgRoad, "Amul Taaza Toned Milk") };
        });

        tenantId = String.valueOf(ids[0]);
        productId = ids[1];
        adminToken = tokenFor("mg-road", "admin", "admin123");
    }

    @AfterEach
    void tearDown() {
        transactions.executeWithoutResult(status -> {
            // Order matters: variant references product and tenant, and tenant_sequence
            // references tenant. Native SQL for the reason on TenantThreadLocalIT.
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
     * <b>The headline.</b> Ten parallel creates in one store must produce ten codes, not
     * nine and a collision.
     *
     * <p>Asserted as the exact set {@code 1..10} rather than merely "ten distinct values",
     * because those fail differently and both are worth catching: duplicates mean the lock
     * is missing, while a gap means a value was consumed by a transaction that then rolled
     * back — which nothing here should be doing.
     */
    @Test
    @DisplayName("parallel creates each get their own code, with no duplicate and no gap")
    void parallelCreatesMintDistinctCodes() throws Exception {
        List<Callable<MvcResult>> calls = IntStream.range(0, CREATES)
                .<Callable<MvcResult>>mapToObj(i -> () -> createVariant("PARALLEL-" + i))
                .toList();

        List<String> codes = new ArrayList<>();
        for (MvcResult result : runConcurrently(calls)) {
            assertEquals(201, result.getResponse().getStatus(),
                    "every create should have succeeded: "
                            + result.getResponse().getContentAsString());
            codes.add(JsonPath.read(result.getResponse().getContentAsString(), "$.qrCode"));
        }

        Set<String> expected = IntStream.rangeClosed(1, CREATES)
                .mapToObj(n -> "POS-QR-%s-%06d".formatted(tenantId, n))
                .collect(Collectors.toSet());

        assertEquals(expected, new HashSet<>(codes),
                "the sequence handed out a duplicate or skipped a value under contention");
        assertEquals(CREATES, new HashSet<>(codes).size(),
                "two variants were minted the same QR code, so two labels are now identical");
    }

    /**
     * The other half: a value the <i>caller</i> chose.
     *
     * <p>Which layer catches the duplicate depends on timing — the service's pre-check when
     * the first insert has already committed, the unique index when it has not — and the
     * point is that <b>a caller cannot tell which</b>. Both answers are 400 on the
     * {@code sku} field with the same sentence, so losing a race is not a different bug to
     * report.
     *
     * <p>Deliberately not asserting <i>that</i> the index-caught path ran: that would make
     * the test depend on winning a timing race, which is how a suite becomes flaky.
     * {@code ApiExceptionHandlerTest} covers that path deterministically instead; this
     * covers the property that matters either way.
     */
    @Test
    @DisplayName("two callers racing one SKU: one wins, the loser gets the ordinary 400")
    void racingTheSameSkuProducesOneWinnerAndOneCleanRejection() throws Exception {
        for (int round = 0; round < RACES; round++) {
            String sku = "RACE-" + round;
            List<Callable<MvcResult>> calls = List.of(
                    () -> createVariant(sku),
                    () -> createVariant(sku));

            int created = 0;
            int rejected = 0;
            for (MvcResult result : runConcurrently(calls)) {
                String body = result.getResponse().getContentAsString();
                switch (result.getResponse().getStatus()) {
                    case 201 -> created++;
                    case 400 -> {
                        rejected++;
                        assertEquals("SKU is already in use",
                                JsonPath.read(body, "$.fields.sku"),
                                "the loser of the race must be told what an uncontended "
                                        + "duplicate would have been told");
                    }
                    default -> throw new AssertionError(
                            "unexpected status " + result.getResponse().getStatus()
                                    + " -- a lost race must not surface as a server error: "
                                    + body);
                }
            }

            assertEquals(1, created, "exactly one of the two should have been created");
            assertEquals(1, rejected, "exactly one of the two should have been rejected");
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

    /**
     * Through the endpoint rather than the DAO, deliberately: the lock has to be taken and
     * released inside the same transaction the insert runs in, and it is the
     * {@code @Transactional} boundary on the service that defines that. Calling the DAO
     * from a test would put the boundary somewhere production never puts it.
     */
    private MvcResult createVariant(String sku) throws Exception {
        return mvc.perform(post("/api/products/" + productId + "/variants")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"variantLabel":"%s","sku":"%s","mrp":30,"sellingPrice":29}
                                """.formatted(sku, sku)))
                .andReturn();
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
        user.setTenantId(tenant.getId());
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
}

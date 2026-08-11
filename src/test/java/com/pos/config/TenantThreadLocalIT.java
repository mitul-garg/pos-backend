package com.pos.config;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.jayway.jsonpath.JsonPath;
import com.pos.pojo.AppUser;
import com.pos.pojo.Product;
import com.pos.pojo.Role;
import com.pos.pojo.Tenant;
import com.pos.pojo.TenantStatus;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <b>The test the frontend's mock could never have written</b> — the tenant filter under
 * genuine thread reuse (backend-plan.md section 10, risk 1).
 *
 * <p>Jetty serves requests on a pooled thread, and every other suite here is
 * single-threaded. The shape below is the one that exposes what they cannot: a <b>fixed pool
 * of two threads</b> serving twenty times that many requests, so each thread is reused
 * constantly and each reuse is a fresh chance to inherit. The two catalogues are disjoint,
 * so a wrong tenant does not merely produce a wrong count — it returns rows the caller could
 * never have named.
 *
 * <p><b>What it proves, precisely.</b> That the filter's parameter is resolved <i>per
 * query</i> rather than once per session: one pooled thread, reusing sessions and
 * connections, serves two tenants and a tenant-less admin in rotation without any of them
 * bleeding. That is the assumption the entire design rests on, and it is not one that
 * reading Hibernate's source settles.
 *
 * <p><b>What it does not prove</b>, measured rather than assumed: removing the
 * {@code finally} from {@code JwtAuthenticationFilter} leaves this test <b>green</b>. Every
 * request here carries a token, and {@code TenantContext.set()} removes on null, so each one
 * overwrites or clears whatever it inherited before doing any work.
 * {@link #aRequestLeavesNothingBehind()} and {@code JwtAuthenticationFilterTest} are what
 * pin the clear. Recorded here so a green run is not read as covering more than it does.
 *
 * <h2>Not {@code @Transactional}, unlike every other IT here</h2>
 * The worker threads need to see the fixtures, and a test-managed transaction is never
 * committed. So the rows are committed through a {@link TransactionTemplate} and removed in
 * {@code @AfterEach} with native SQL — native because a bulk HQL {@code DELETE} would be
 * subject to the tenant filter, from a thread that has no tenant.
 */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = {
        RootConfig.class, PersistenceConfig.class, SecurityConfig.class, MailConfig.class,
        WebConfig.class, OpenApiConfig.class })
@TestPropertySource("classpath:application-test.properties")
@DisplayName("TenantContext under a reused thread pool")
class TenantThreadLocalIT {

    /**
     * Two, matching {@code pos.db.pool.maxSize} in the test properties — more workers than
     * connections would queue on Hikari rather than exercise the thing under test. Two is
     * also the minimum that can interleave, and every extra request is another reuse.
     */
    private static final int THREADS = 2;

    /** Enough reuse that a leak is a near-certainty rather than a coin flip. */
    private static final int REQUESTS = 40;

    private static final BCryptPasswordEncoder HASHER = new BCryptPasswordEncoder();

    @Autowired
    private WebApplicationContext context;

    /**
     * {@code PersistenceConfig} declares a {@code PlatformTransactionManager}, not a
     * {@code TransactionTemplate} — the application has no use for one, because services
     * own {@code @Transactional} declaratively. This suite is the exception: it needs
     * fixtures <b>committed</b> before other threads can see them.
     */
    @Autowired
    private PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager em;

    private TransactionTemplate transactions;
    private MockMvc mvc;

    private String mgRoadToken;
    private String airportToken;
    private String platformToken;
    private String mgRoadId;
    private String airportId;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        transactions = new TransactionTemplate(transactionManager);

        Long[] ids = transactions.execute(status -> {
            Tenant platform = platformTenant();
            Tenant mgRoad = tenant("MG Road Store", "mg-road");
            Tenant airport = tenant("Airport Store", "airport");

            user(platform, "superadmin", "super123", Role.SUPER_ADMIN);
            user(mgRoad, "cashier", "cashier123", Role.CASHIER);
            user(airport, "cashier", "cashier123", Role.CASHIER);

            // Disjoint catalogues, one row each. An inherited tenant therefore returns a
            // product the caller could not have named, not merely a different count.
            product(mgRoad, "Amul Taaza Toned Milk");
            product(airport, "Travel Neck Pillow");

            return new Long[] { mgRoad.getId(), airport.getId() };
        });

        mgRoadId = String.valueOf(ids[0]);
        airportId = String.valueOf(ids[1]);
        mgRoadToken = tokenFor("mg-road", "cashier", "cashier123");
        airportToken = tokenFor("airport", "cashier", "cashier123");
        platformToken = tokenFor("platform", "superadmin", "super123");
    }

    @AfterEach
    void tearDown() {
        transactions.executeWithoutResult(status -> {
            // Native SQL, not HQL: a bulk DELETE through Hibernate would carry the tenant
            // filter, and this thread has no tenant -- so it would delete nothing and the
            // next test would inherit these rows.
            em.createNativeQuery("DELETE FROM product").executeUpdate();
            em.createNativeQuery("DELETE FROM app_user").executeUpdate();
            em.createNativeQuery("DELETE FROM tenant").executeUpdate();
        });
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    @DisplayName("two tenants and a tenant-less admin share a two-thread pool without crossing")
    void poolReuseNeverLeaksATenant() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<Callable<String>> calls = new ArrayList<>();

        for (int i = 0; i < REQUESTS; i++) {
            // Rotating three ways so that consecutive requests on a reused thread are
            // usually for different callers -- the sequence a sequential suite never
            // produces, and the one under which the filter, the session and the resolver
            // all have to agree.
            //
            // Honest about its limits, because it was measured rather than assumed:
            // REMOVING THE `finally` FROM JwtAuthenticationFilter DOES NOT REDDEN THIS
            // TEST. Every request here carries a token, and TenantContext.set() removes
            // on null, so each one either overwrites or clears whatever it inherited
            // before doing any work. The clear is pinned by aRequestLeavesNothingBehind
            // and by JwtAuthenticationFilterTest instead. What this test does prove is
            // the other half: that the resolver is consulted per query, so one session
            // and one thread genuinely serve two tenants without bleeding.
            switch (i % 3) {
                case 0 -> calls.add(() -> assertOwnRowsOnly(mgRoadToken, mgRoadId));
                case 1 -> calls.add(() -> assertOwnRowsOnly(airportToken, airportId));
                default -> calls.add(this::assertRefusedForHavingNoTenant);
            }
        }

        List<Future<String>> results = pool.invokeAll(calls, 60, TimeUnit.SECONDS);
        pool.shutdown();

        List<String> failures = new ArrayList<>();
        for (Future<String> result : results) {
            String failure = result.get();
            if (failure != null) {
                failures.add(failure);
            }
        }

        assertEquals(List.of(), failures,
                "a request was served with another tenant's context");
    }

    @Test
    @DisplayName("leaves the serving thread clean, so an unauthenticated request inherits nothing")
    void aRequestLeavesNothingBehind() throws Exception {
        // The sequential half of the same property, and the cheaper one to debug when the
        // concurrent test above goes red. An anonymous request must be anonymous even
        // when the previous request on that thread was not.
        mvc.perform(get("/api/products").param("pageSize", "200")
                        .header("Authorization", "Bearer " + mgRoadToken))
                .andExpect(status().isOk());

        assertFalse(TenantContext.isPresent(),
                "the request handed its thread back still carrying a tenant");

        mvc.perform(get("/api/products")).andExpect(status().isUnauthorized());
    }

    /**
     * A tenant-less caller interleaved with two tenant-ful ones. It must be refused on
     * every thread, whichever store was served there a moment earlier — a 200 would mean
     * it had somehow acquired a tenant it does not have.
     */
    private String assertRefusedForHavingNoTenant() throws Exception {
        int status = mvc.perform(get("/api/products").param("pageSize", "200")
                        .header("Authorization", "Bearer " + platformToken))
                .andReturn().getResponse().getStatus();

        if (status != 403) {
            return "a tenant-less SUPER_ADMIN got " + status + " instead of 403, so it "
                    + "inherited a tenant from the previous request on this thread";
        }
        return null;
    }

    /** Returns null when the response was correct, or a description of what leaked. */
    private String assertOwnRowsOnly(String token, String expectedTenantId) throws Exception {
        String body = mvc.perform(get("/api/products").param("pageSize", "200")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<String> tenantIds = JsonPath.read(body, "$.items[*].tenantId");
        int total = JsonPath.read(body, "$.total");

        if (total != 1 || !List.of(expectedTenantId).equals(tenantIds)) {
            return "expected exactly one row for tenant " + expectedTenantId
                    + " but got total=" + total + " tenantIds=" + tenantIds;
        }
        return null;
    }

    // --- fixtures ----------------------------------------------------------------

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

    private Tenant tenant(String name, String code) {
        Tenant tenant = new Tenant();
        tenant.setName(name);
        tenant.setCode(code);
        tenant.setStatus(TenantStatus.ACTIVE);
        tenant.setPlatform(false);
        em.persist(tenant);
        return tenant;
    }

    /** The reserved row a SUPER_ADMIN belongs to; it reports no tenant on the wire. */
    private Tenant platformTenant() {
        Tenant tenant = tenant("Platform", Tenant.PLATFORM_CODE);
        tenant.setPlatform(true);
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

    private void product(Tenant tenant, String name) {
        Product product = new Product();
        product.setTenant(tenant);
        product.setName(name);
        product.setBrand("Test");
        product.setCategory("Test");
        product.setTaxRatePercent(new BigDecimal("18.00"));
        product.setActive(true);
        em.persist(product);
    }
}

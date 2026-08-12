package com.pos.controller;

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
import com.pos.pojo.Role;
import com.pos.pojo.Tenant;
import com.pos.pojo.TenantStatus;
import com.pos.util.TenantContext;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * <b>Two admins racing to deactivate each other</b> (C8) — the fifth concurrency suite,
 * after {@code TenantThreadLocalIT}, {@code VariantSequenceIT}, {@code StockRaceIT} and
 * {@code ReturnRaceIT}.
 *
 * <p>{@code UserService.deactivate} locks the tenant row ({@code AppUserDao.lockTenant})
 * before trusting {@code countActiveAdmins}, precisely so this cannot happen: two
 * {@code DELETE /api/users/{id}} calls against a tenant's last two active admins, fired at
 * the same instant, must not both read "2 active admins" and both succeed — which would
 * leave the store with none and nobody able to log in to fix it. Same shape as
 * {@code ReturnRaceIT}'s returnable-quantity race: a read-then-write with a gap, except
 * the aggregate here is "how many admins in this tenant are active" rather than "how much
 * of this order has already been returned" — see {@code AppUserDao.lockTenant}'s Javadoc
 * for why the tenant row is what gets locked when there is no single row the way an order
 * is for a return.
 *
 * <h2>Not {@code @Transactional}, and it cannot be — same reasoning as {@code StockRaceIT}</h2>
 * The worker threads must see a committed fixture and must genuinely commit or block
 * against the database for the lock to be real; a wrapping test transaction would hide the
 * fixture from the worker connections entirely. Fixtures are built and torn down with a
 * real, committed {@link TransactionTemplate}.
 *
 * <h2>A fresh tenant every round, unlike {@code ReturnRaceIT}'s fresh order in one tenant</h2>
 * The hazard here is scoped to a whole tenant's admin count, not one row, so a leftover
 * admin from a previous round would pad the count and mask the race for the next one.
 * Each round gets its own tenant with exactly two admins, race each other, and leave
 * exactly one standing.
 */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = {
        RootConfig.class, PersistenceConfig.class, SecurityConfig.class, MailConfig.class,
        WebConfig.class, OpenApiConfig.class })
@TestPropertySource("classpath:application-test.properties")
@DisplayName("deactivating a tenant's admins under concurrency")
class LastAdminRaceIT {

    /** Two racers, matching {@code pos.db.pool.maxSize} -- see StockRaceIT's note. */
    private static final int THREADS = 2;

    /** Rounds of two admins racing to deactivate each other. */
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

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        transactions = new TransactionTemplate(transactionManager);
    }

    @AfterEach
    void tearDown() {
        transactions.executeWithoutResult(status -> {
            em.createNativeQuery("DELETE FROM app_user").executeUpdate();
            em.createNativeQuery("DELETE FROM tenant").executeUpdate();
        });
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    /**
     * <b>The headline.</b> A fresh store with exactly two active admins, each asked at the
     * same instant to deactivate the <i>other</i> — together the two requests ask to leave
     * zero admins standing. Exactly one must succeed and exactly one must be rejected with
     * the ordinary "last active admin" 400, never both succeeding and never both rejected.
     *
     * <p>Repeated {@code RACES} times with a fresh tenant each round, since a race winning
     * "by luck" — the two threads happening to serialize rather than truly overlap — would
     * let a missing lock pass once.
     */
    @Test
    @DisplayName("two admins racing to deactivate each other: exactly one succeeds, one gets a clean 400")
    void racingToDeactivateEachOtherLeavesExactlyOneAdmin() throws Exception {
        for (int round = 0; round < RACES; round++) {
            int roundNumber = round;
            String code = "race-" + round;

            Long[] ids = transactions.execute(status -> {
                Tenant tenant = tenant("Race Store " + roundNumber, code);
                return new Long[] {
                        user(tenant, "admin-a", "pass123", Role.ADMIN),
                        user(tenant, "admin-b", "pass123", Role.ADMIN)
                };
            });
            Long adminAId = ids[0];
            Long adminBId = ids[1];

            String tokenA = tokenFor(code, "admin-a", "pass123");
            String tokenB = tokenFor(code, "admin-b", "pass123");

            List<Callable<MvcResult>> calls = List.of(
                    () -> deactivate(tokenA, adminBId),
                    () -> deactivate(tokenB, adminAId));

            int completed = 0;
            int rejected = 0;
            for (MvcResult result : runConcurrently(calls)) {
                String body = result.getResponse().getContentAsString();
                switch (result.getResponse().getStatus()) {
                    case 200 -> completed++;
                    case 400 -> {
                        rejected++;
                        assertTrue(body.contains("Cannot deactivate the last active admin"),
                                "the loser must get the ordinary last-admin 400, "
                                        + "not a server error: " + body);
                    }
                    default -> throw new AssertionError(
                            "unexpected status " + result.getResponse().getStatus()
                                    + " -- a lost race must not surface as a server error: "
                                    + body);
                }
            }

            assertEquals(1, completed,
                    "round " + round + ": exactly one of the two deactivations should have completed");
            assertEquals(1, rejected,
                    "round " + round + ": exactly one of the two deactivations should have been rejected");
            assertActiveAdminCount(code, 1);
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
     * Through the endpoint, deliberately -- same reasoning as {@code StockRaceIT.pay} and
     * {@code ReturnRaceIT.createReturn}: the pessimistic lock has to run inside
     * {@code UserService.deactivate}'s own transaction boundary, which nothing but the
     * real {@code @Transactional} method recreates.
     */
    private MvcResult deactivate(String token, Long userId) throws Exception {
        return mvc.perform(delete("/api/users/" + userId)
                        .header("Authorization", "Bearer " + token))
                .andReturn();
    }

    /**
     * Native SQL rather than a filtered read -- same reason as {@code StockRaceIT.assertStock}:
     * {@code JwtAuthenticationFilter} clears {@link TenantContext} once each request
     * returns, so a filtered read here would resolve against {@code NO_TENANT}, and
     * {@code AppUser} carries no filter to begin with.
     */
    private void assertActiveAdminCount(String tenantCode, int expected) {
        Number count = (Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM app_user u JOIN tenant t ON t.id = u.tenant_id "
                                + "WHERE t.code = ?1 AND u.role = 'ADMIN' AND u.is_active = true")
                .setParameter(1, tenantCode)
                .getSingleResult();
        assertEquals(expected, count.intValue(), () -> "active admin count for tenant " + tenantCode);
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

    private Tenant tenant(String name, String code) {
        Tenant tenant = new Tenant();
        tenant.setName(name);
        tenant.setCode(code);
        tenant.setStatus(TenantStatus.ACTIVE);
        tenant.setPlatform(false);
        em.persist(tenant);
        return tenant;
    }

    private Long user(Tenant tenant, String username, String password, Role role) {
        AppUser user = new AppUser();
        user.setTenant(tenant);
        user.setUsername(username);
        user.setPasswordHash(HASHER.encode(password));
        user.setDisplayName(username);
        user.setRole(role);
        user.setActive(true);
        em.persist(user);
        return user.getId();
    }
}

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
import com.pos.pojo.PosOrderPojo;
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

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /api/tenants} (C8) — the port of {@code tenantService.test.js}'s 12 cases
 * (backend-plan.md section 8): the platform gate, atomic creation, reserved/duplicate
 * codes, and suspend → locked out → reactivate.
 *
 * <p><b>Gated entirely by {@code SecurityConfig}, not by a message this suite can
 * assert on.</b> The mock's {@code requireSuperAdmin()} throws a specific
 * {@code "Only a platform administrator..."} string; the backend's equivalent is a URL
 * rule, so a non-{@code SUPER_ADMIN} gets the same generic 403
 * {@code ApiExceptionHandler}/{@code SecurityConfig}'s access-denied handler produces for
 * every other role-gated endpoint — {@code ProductWriteIT.RoleRules} pins the identical
 * shape for the catalogue. That is the one deliberate divergence from the mock this suite
 * documents rather than reproduces.
 */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = {
        RootConfig.class, PersistenceConfig.class, SecurityConfig.class, MailConfig.class,
        RecaptchaConfig.class,
        WebConfig.class, OpenApiConfig.class })
@TestPropertySource("classpath:application-test.properties")
@Transactional
@DisplayName("GET/POST/PATCH /api/tenants")
class TenantAdminIT {

    private static final BCryptPasswordEncoder HASHER = new BCryptPasswordEncoder();
    private static final String ADMIN_HASH = HASHER.encode("admin123");
    private static final String CASHIER_HASH = HASHER.encode("cashier123");
    private static final String SUPER_HASH = HASHER.encode("super123");

    private static final long UNISSUED_ID = 9_999_999L;

    private static final String NEW_STORE = """
            {"name":"Harbour Store","code":"harbour",
             "adminUsername":"admin","adminPassword":"harbour123"}
            """;

    @Autowired
    private WebApplicationContext context;

    @PersistenceContext
    private EntityManager em;

    private MockMvc mvc;

    private TenantPojo platform;
    private TenantPojo mgRoad;
    private TenantPojo airport;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        platform = tenant("Platform", TenantPojo.PLATFORM_CODE, true);
        mgRoad = tenant("MG Road Store", "mg-road", false);
        airport = tenant("Airport Store", "airport", false);

        user(platform, "superadmin", SUPER_HASH, Role.SUPER_ADMIN);
        AppUserPojo mgRoadAdmin = user(mgRoad, "admin", ADMIN_HASH, Role.ADMIN);
        user(mgRoad, "cashier", CASHIER_HASH, Role.CASHIER);
        user(airport, "admin", ADMIN_HASH, Role.ADMIN);

        // Non-zero counts, so "with the row counts a suspension needs" is actually
        // exercised rather than trivially true at 0/0/0.
        product(mgRoad, "Amul Taaza Toned Milk");
        order(mgRoad, mgRoadAdmin, "ORD-2026-0001");

        em.flush();
        em.clear();
    }

    @AfterEach
    void clearThreadState() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Nested
    @DisplayName("only the platform role reaches this surface")
    class RoleGate {

        @Test
        @DisplayName("a tenant ADMIN is refused on every verb")
        void rejectsATenantAdmin() throws Exception {
            String admin = asMgRoadAdmin();
            listTenants(admin).andExpect(status().isForbidden());
            createTenant(admin, NEW_STORE).andExpect(status().isForbidden());
            patchStatus(admin, id(mgRoad), """
                    {"status":"SUSPENDED"}
                    """).andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("a CASHIER is refused too")
        void rejectsACashier() throws Exception {
            listTenants(asMgRoadCashier()).andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("an anonymous caller is refused before any role is even considered")
        void rejectsAnonymous() throws Exception {
            mvc.perform(get("/api/tenants")).andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("GET /api/tenants")
    class Listing {

        @Test
        @DisplayName("returns every tenant but the platform row, with the counts a suspension needs")
        void returnsEveryTenantWithCounts() throws Exception {
            listTenants(asSuperAdmin())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[*].code", containsInAnyOrder("mg-road", "airport")));

            getTenant(asSuperAdmin(), id(mgRoad))
                    .andExpect(jsonPath("$.userCount").value(2))
                    .andExpect(jsonPath("$.productCount").value(1))
                    .andExpect(jsonPath("$.orderCount").value(1));

            getTenant(asSuperAdmin(), id(airport))
                    .andExpect(jsonPath("$.userCount").value(1))
                    .andExpect(jsonPath("$.productCount").value(0))
                    .andExpect(jsonPath("$.orderCount").value(0));
        }

        /**
         * Peer-review Phase 1: {@code list} used to run {@code AppUserDao.countByTenant} /
         * {@code TenantDao.productCount}/{@code orderCount} once per tenant ({@code 3N+1}
         * queries for the page). {@code countsByTenants}/{@code productCountsByTenants}/
         * {@code orderCountsByTenants} batch all three into one {@code GROUP BY} query each,
         * grouped back onto each tenant by id — the risk that refactor introduces is one
         * tenant's counts leaking onto another's, which this pins directly against the
         * <b>list</b> response (unlike the test above, which only reads counts off the
         * single-tenant {@code GET /{id}}). Airport's zero product/order counts also prove
         * a tenant absent from a {@code GROUP BY} result — nothing to group for zero rows —
         * still comes back as {@code 0}, not {@code null} or a missing field.
         */
        @Test
        @DisplayName("the list response's own counts are correct per tenant, not mixed up")
        void listResponseCountsAreCorrectPerTenant() throws Exception {
            listTenants(asSuperAdmin())
                    .andExpect(jsonPath("$[?(@.code=='mg-road')].userCount").value(hasItem(2)))
                    .andExpect(jsonPath("$[?(@.code=='mg-road')].productCount").value(hasItem(1)))
                    .andExpect(jsonPath("$[?(@.code=='mg-road')].orderCount").value(hasItem(1)))
                    .andExpect(jsonPath("$[?(@.code=='airport')].userCount").value(hasItem(1)))
                    .andExpect(jsonPath("$[?(@.code=='airport')].productCount").value(hasItem(0)))
                    .andExpect(jsonPath("$[?(@.code=='airport')].orderCount").value(hasItem(0)));
        }

        @Test
        @DisplayName("an unknown tenant id is 404")
        void unknownIdIs404() throws Exception {
            getTenant(asSuperAdmin(), String.valueOf(UNISSUED_ID))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Tenant not found"));
        }

        /**
         * {@code TenantPojo.isPlatform()}'s own Javadoc: "excluded from GET /api/tenants and
         * cannot be suspended" — the other half of "excluded" is a direct-by-id fetch, and
         * this is the case that pins it.
         */
        @Test
        @DisplayName("the platform row itself is 404 by id, identical to one that never existed")
        void platformRowIs404ById() throws Exception {
            getTenant(asSuperAdmin(), id(platform)).andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("POST /api/tenants")
    class Creating {

        @Test
        @DisplayName("creates the store and its first admin together, ready to sign in")
        void createsTheStoreAndItsFirstAdmin() throws Exception {
            createTenant(asSuperAdmin(), NEW_STORE)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.name").value("Harbour Store"))
                    .andExpect(jsonPath("$.code").value("harbour"))
                    .andExpect(jsonPath("$.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.userCount").value(1))
                    .andExpect(jsonPath("$.productCount").value(0))
                    .andExpect(jsonPath("$.orderCount").value(0));

            String response = mvc.perform(post("/api/auth/login").with(TestIps.remoteAddr(TestIps.fresh()))
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    {"tenantCode":"harbour","username":"admin","password":"harbour123"}
                                    """))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertEquals("Harbour Store", JsonPath.read(response, "$.user.tenantName"));
            assertEquals("ADMIN", JsonPath.read(response, "$.user.role"));
        }

        @Test
        @DisplayName("refuses a reserved code, so the platform login can never be shadowed")
        void refusesAReservedCode() throws Exception {
            createTenant(asSuperAdmin(), """
                    {"name":"Sneaky","code":"platform",
                     "adminUsername":"admin","adminPassword":"x"}
                    """)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fields.code").value(containsString("reserved")));
        }

        @Test
        @DisplayName("refuses a duplicate code -- the one globally unique field")
        void refusesADuplicateCode() throws Exception {
            createTenant(asSuperAdmin(), """
                    {"name":"Copycat","code":"mg-road",
                     "adminUsername":"admin","adminPassword":"x"}
                    """)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fields.code").value("That tenant code is already taken"));
        }

        @Test
        @DisplayName("refuses a malformed code")
        void refusesAMalformedCode() throws Exception {
            createTenant(asSuperAdmin(), """
                    {"name":"Bad","code":"Not Valid!",
                     "adminUsername":"admin","adminPassword":"x"}
                    """)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fields.code").value(
                            "Use lowercase letters, numbers and hyphens only"));
        }

        @Test
        @DisplayName("refuses to create a tenant with no admin to sign in with, and leaves no orphan row")
        void refusesWithNoAdminUsername() throws Exception {
            createTenant(asSuperAdmin(), """
                    {"name":"Orphan","code":"orphan","adminPassword":"x"}
                    """)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fields.adminUsername")
                            .value("The first admin's username is required"));

            // The code is NOT now "taken" by a half-created tenant -- validation ran
            // before either row was written.
            createTenant(asSuperAdmin(), """
                    {"name":"Orphan Retried","code":"orphan",
                     "adminUsername":"admin","adminPassword":"x"}
                    """).andExpect(status().isCreated());
        }

        @Test
        @DisplayName("refuses a blank admin password")
        void refusesWithNoAdminPassword() throws Exception {
            createTenant(asSuperAdmin(), """
                    {"name":"Orphan","code":"orphan2","adminUsername":"admin"}
                    """)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fields.adminPassword")
                            .value("The first admin's password is required"));
        }

        @Test
        @DisplayName("reports every broken field at once, not just the first")
        void reportsEveryBrokenFieldTogether() throws Exception {
            createTenant(asSuperAdmin(), "{}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fields.name").exists())
                    .andExpect(jsonPath("$.fields.code").exists())
                    .andExpect(jsonPath("$.fields.adminUsername").exists())
                    .andExpect(jsonPath("$.fields.adminPassword").exists());
        }

        /**
         * Peer-review Phase 1: length bounds the mock had no schema to answer to. One
         * request covering all four bounded fields on this form, the multi-error
         * shape {@code reportsEveryBrokenFieldTogether} already exercises above --
         * {@code code} needs a valid-format 70-char value to reach its length check at
         * all, since {@code CODE_PATTERN} would otherwise reject it first.
         */
        @Test
        @DisplayName("rejects overlong fields with a clean 400, not an unmapped 500")
        void rejectsOverlongFields() throws Exception {
            String longName = "N".repeat(121);
            String longCode = "c".repeat(70);
            String longUsername = "u".repeat(65);
            String longPassword = "p".repeat(73);

            createTenant(asSuperAdmin(), """
                    {"name":"%s","code":"%s","adminUsername":"%s","adminPassword":"%s"}
                    """.formatted(longName, longCode, longUsername, longPassword))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fields.name").value("Tenant name must be 120 characters or fewer"))
                    .andExpect(jsonPath("$.fields.code").value("Tenant code must be 64 characters or fewer"))
                    .andExpect(jsonPath("$.fields.adminUsername")
                            .value("The first admin's username must be 64 characters or fewer"))
                    .andExpect(jsonPath("$.fields.adminPassword")
                            .value("The first admin's password must be 72 characters or fewer"));
        }
    }

    @Nested
    @DisplayName("PATCH /api/tenants/{id}")
    class SuspendReactivate {

        @Test
        @DisplayName("locks the tenant out and lets it back in, without touching other tenants")
        void locksOutAndReactivates() throws Exception {
            patchStatus(asSuperAdmin(), id(airport), """
                    {"status":"SUSPENDED"}
                    """)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("SUSPENDED"));

            mvc.perform(post("/api/auth/login").with(TestIps.remoteAddr(TestIps.fresh())).contentType(APPLICATION_JSON).content("""
                            {"tenantCode":"airport","username":"admin","password":"admin123"}
                            """))
                    .andExpect(status().isForbidden());

            // mg-road is untouched.
            mvc.perform(post("/api/auth/login").with(TestIps.remoteAddr(TestIps.fresh())).contentType(APPLICATION_JSON).content("""
                            {"tenantCode":"mg-road","username":"admin","password":"admin123"}
                            """))
                    .andExpect(status().isOk());

            patchStatus(asSuperAdmin(), id(airport), """
                    {"status":"ACTIVE"}
                    """).andExpect(status().isOk());

            mvc.perform(post("/api/auth/login").with(TestIps.remoteAddr(TestIps.fresh())).contentType(APPLICATION_JSON).content("""
                            {"tenantCode":"airport","username":"admin","password":"admin123"}
                            """))
                    .andExpect(status().isOk());
        }

        /**
         * {@code TenantStatusForm.status} is typed as the enum, matching
         * {@code OrderForm.status} — an unrecognised value fails Jackson binding rather
         * than the mock's {@code "Invalid tenant status"} message. Recorded as a
         * deliberate divergence, the same way C6 recorded its own status-string decisions.
         */
        @Test
        @DisplayName("an unrecognised status is a malformed-body 400, not a field-level one")
        void unknownStatusIsMalformedBody() throws Exception {
            patchStatus(asSuperAdmin(), id(mgRoad), """
                    {"status":"DELETED"}
                    """).andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("status absent is a no-op -- only status is patchable")
        void absentStatusIsANoOp() throws Exception {
            patchStatus(asSuperAdmin(), id(mgRoad), "{}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("ACTIVE"));
        }

        @Test
        @DisplayName("an unknown tenant id is 404")
        void unknownIdIs404() throws Exception {
            patchStatus(asSuperAdmin(), String.valueOf(UNISSUED_ID), """
                    {"status":"ACTIVE"}
                    """)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Tenant not found"));
        }

        @Test
        @DisplayName("the platform row cannot be suspended -- it has no lifecycle")
        void platformRowCannotBeSuspended() throws Exception {
            patchStatus(asSuperAdmin(), id(platform), """
                    {"status":"SUSPENDED"}
                    """).andExpect(status().isNotFound());

            // And the platform login still works -- nothing was silently applied.
            mvc.perform(post("/api/auth/login").with(TestIps.remoteAddr(TestIps.fresh())).contentType(APPLICATION_JSON).content("""
                            {"tenantCode":"platform","username":"superadmin","password":"super123"}
                            """))
                    .andExpect(status().isOk());
        }
    }

    // --- helpers -----------------------------------------------------------------

    private static final String AUTH = "Authorization";

    private ResultActions listTenants(String token) throws Exception {
        return mvc.perform(get("/api/tenants").header(AUTH, bearer(token)));
    }

    private ResultActions getTenant(String token, String id) throws Exception {
        return mvc.perform(get("/api/tenants/" + id).header(AUTH, bearer(token)));
    }

    private ResultActions createTenant(String token, String body) throws Exception {
        return mvc.perform(post("/api/tenants")
                .header(AUTH, bearer(token))
                .contentType(APPLICATION_JSON)
                .content(body));
    }

    private ResultActions patchStatus(String token, String id, String body) throws Exception {
        return mvc.perform(patch("/api/tenants/" + id)
                .header(AUTH, bearer(token))
                .contentType(APPLICATION_JSON)
                .content(body));
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private String id(TenantPojo tenant) {
        return String.valueOf(tenant.getId());
    }

    private String asSuperAdmin() throws Exception {
        return tokenFor(TenantPojo.PLATFORM_CODE, "superadmin", "super123");
    }

    private String asMgRoadAdmin() throws Exception {
        return tokenFor("mg-road", "admin", "admin123");
    }

    private String asMgRoadCashier() throws Exception {
        return tokenFor("mg-road", "cashier", "cashier123");
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

    private AppUserPojo user(TenantPojo tenant, String username, String passwordHash, Role role) {
        AppUserPojo user = new AppUserPojo();
        user.setTenant(tenant);
        user.setUsername(username);
        user.setPasswordHash(passwordHash);
        user.setDisplayName(username);
        user.setRole(role);
        user.setActive(true);
        em.persist(user);
        return user;
    }

    private void product(TenantPojo tenant, String name) {
        ProductPojo product = new ProductPojo();
        product.setTenant(tenant);
        product.setName(name);
        product.setBrand("Amul");
        product.setCategory("Dairy");
        product.setTaxRatePercent(new BigDecimal("5.00"));
        product.setActive(true);
        em.persist(product);
    }

    /** The minimum {@link PosOrderPojo} needs, for a count fixture with no cart behind it. */
    private void order(TenantPojo tenant, AppUserPojo cashier, String orderNumber) {
        PosOrderPojo order = new PosOrderPojo();
        order.setTenant(tenant);
        order.setCashier(cashier);
        order.setOrderNumber(orderNumber);
        order.setSubtotal(BigDecimal.TEN);
        order.setTotalTax(BigDecimal.ONE);
        order.setRoundOff(BigDecimal.ZERO);
        order.setGrandTotal(BigDecimal.TEN);
        em.persist(order);
    }
}

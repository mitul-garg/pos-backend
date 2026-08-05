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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <b>The headline suite.</b> A port of {@code frontend/src/services/isolation.test.js},
 * whose cases are already an executable statement of what "isolated" means — each one
 * re-expressed as an HTTP request carrying a <i>t1</i> token at a <i>t2</i> resource. The
 * product-shaped cases land here in C4; the order, return, variant and user ones join them
 * as C5–C8 give them endpoints to aim at.
 *
 * <p>Every case is an <b>attempt</b> to reach another tenant's data through an ordinary,
 * well-formed request, and every one must fail <b>closed</b>: a list that omits the row, or
 * a 404 indistinguishable from an id that never existed. None may answer 403, which would
 * confirm the id is real somewhere else (requirements.md section 13.3).
 *
 * <p>The fixtures make those attempts realistic rather than theoretical. Both stores sell
 * Bisleri, so a substring search that skipped scoping returns two rows instead of one;
 * {@code Travel Essentials} exists in one store only, so a categories list that crossed
 * would name it. A t1 user knowing a t2 id is precisely the threat model — ids leak through
 * URLs, receipts and printed labels.
 *
 * <p><b>Mutation-checked rather than merely observed to pass</b> — {@code c4-tenancy.md}
 * records which mutation reddens which case. A green isolation suite that would stay green
 * with isolation switched off is worse than none, because it manufactures confidence.
 */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = {
        RootConfig.class, PersistenceConfig.class, SecurityConfig.class,
        WebConfig.class, OpenApiConfig.class })
@TestPropertySource("classpath:application-test.properties")
@Transactional
@DisplayName("tenant isolation — /api/products")
class TenantIsolationIT {

    private static final BCryptPasswordEncoder HASHER = new BCryptPasswordEncoder();
    private static final String ADMIN_HASH = HASHER.encode("admin123");
    private static final String CASHIER_HASH = HASHER.encode("cashier123");
    private static final String SUPER_HASH = HASHER.encode("super123");

    /** No product will ever carry it, so it is the "genuinely missing" control. */
    private static final long UNISSUED_ID = 9_999_999L;

    @Autowired
    private WebApplicationContext context;

    @PersistenceContext
    private EntityManager em;

    private MockMvc mvc;

    private Tenant mgRoad;
    private Tenant airport;

    /** t2-owned ids in a t1 user's hands — the threat model, as fields. */
    private Long airportBisleri;
    private Long airportPillow;
    private Long mgRoadBisleri;
    private Long mgRoadRetired;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        Tenant platform = tenant("Platform", "platform", true);
        mgRoad = tenant("MG Road Store", "mg-road", false);
        airport = tenant("Airport Store", "airport", false);

        user(platform, "superadmin", SUPER_HASH, Role.SUPER_ADMIN);
        user(mgRoad, "admin", ADMIN_HASH, Role.ADMIN);
        user(mgRoad, "cashier", CASHIER_HASH, Role.CASHIER);
        user(airport, "admin", ADMIN_HASH, Role.ADMIN);

        // Deliberate collision: the same product in both stores, so a search that crossed
        // the boundary returns two rows rather than one.
        mgRoadBisleri = product(mgRoad, "Bisleri Packaged Drinking Water", "Bisleri", "Beverages", true);
        product(mgRoad, "Amul Taaza Toned Milk", "Amul", "Dairy", true);
        mgRoadRetired = product(mgRoad, "Discontinued Cola", "Generic", "Beverages", false);

        airportBisleri = product(airport, "Bisleri Packaged Drinking Water", "Bisleri", "Beverages", true);
        // A category only this store uses, so a leaking categories list names it.
        airportPillow = product(airport, "Travel Neck Pillow", "Wildcraft", "Travel Essentials", true);

        // NOT tidiness -- without it, three of the cases below pass while asserting
        // nothing.
        //
        // These fixtures were persisted in the test's own transaction, so they are
        // MANAGED in the shared persistence context. em.find() checks that context first
        // and hands back the managed instance without issuing SQL at all -- and a filter
        // can only scope a query that actually runs. The cross-tenant get would answer
        // 200, not because scoping failed but because the setup pre-loaded the row.
        //
        // Production cannot reach that state: every request gets its own session, and
        // nothing can load another tenant's row into it in the first place, since every
        // load is filtered. Flushing and detaching is what makes this suite test the
        // application rather than its own fixtures.
        em.flush();
        em.clear();
    }

    @AfterEach
    void clearThreadState() {
        // Both ThreadLocals, because MockMvc reuses the test thread and JUnit reuses it
        // between classes. Either left behind would authenticate or scope the NEXT test --
        // harmless here, and the exact shape of the bug this suite exists to prove cannot
        // happen on Jetty.
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Nested
    @DisplayName("lists are pre-filtered to the caller's tenant")
    class ListsArePreFiltered {

        @Test
        @DisplayName("products — a t2 row is absent, not merely ordered last")
        void products() throws Exception {
            listProducts(asMgRoadCashier())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(2)))
                    .andExpect(jsonPath("$.items[*].tenantId", everyItem(is(id(mgRoad)))))
                    .andExpect(jsonPath("$.items[*].id", not(hasItem(id(airportBisleri)))));
        }

        @Test
        @DisplayName("total counts only the caller's rows, so the pager cannot leak a count")
        void totalIsScopedToo() throws Exception {
            // Subtler than the items check, and worth its own case: the count is a
            // separate statement, and scoping the page while leaving the count global
            // would tell a t1 user exactly how many products t2 has.
            listProducts(asMgRoadCashier()).andExpect(jsonPath("$.total").value(2));
            listProducts(asAirportAdmin()).andExpect(jsonPath("$.total").value(2));
        }

        @Test
        @DisplayName("categories — one only the other store uses is invisible")
        void categories() throws Exception {
            listCategories(asMgRoadCashier())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", containsInAnyOrder("Beverages", "Dairy")));

            listCategories(asAirportAdmin())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", containsInAnyOrder("Beverages", "Travel Essentials")));
        }

        @Test
        @DisplayName("search never crosses over, even on a name both stores stock")
        void searchNeverCrossesOver() throws Exception {
            listProducts(asMgRoadCashier(), "search", "bisleri")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(1)))
                    .andExpect(jsonPath("$.items[0].id").value(id(mgRoadBisleri)));
        }

        @Test
        @DisplayName("filtering by a category only the other store uses is empty, not an error")
        void filteringByAForeignCategoryIsEmpty() throws Exception {
            listProducts(asMgRoadCashier(), "category", "Travel Essentials")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(0))
                    .andExpect(jsonPath("$.items", hasSize(0)));
        }

        @Test
        @DisplayName("includeInactive widens the status filter, never the tenant one")
        void includeInactiveStaysScoped() throws Exception {
            // The admin management view. Relaxing one predicate must not relax the other:
            // a query rebuilt for a new flag is exactly where a WHERE gets dropped.
            listProducts(asMgRoadCashier(), "includeInactive", "true")
                    .andExpect(jsonPath("$.total").value(3))
                    .andExpect(jsonPath("$.items[*].tenantId", everyItem(is(id(mgRoad)))));
        }
    }

    @Nested
    @DisplayName("fetching a known out-of-tenant id resolves as NOT FOUND")
    class OutOfTenantIdIsNotFound {

        @Test
        @DisplayName("a t2 product id is 404 for a t1 caller")
        void crossTenantGetIs404() throws Exception {
            // C4's "done when", stated once: a t1 token provably cannot read a t2 product
            // by id. Everything else in this suite elaborates on it.
            getProduct(asMgRoadCashier(), airportBisleri)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Product not found"));
        }

        @Test
        @DisplayName("and is byte-identical to an id that never existed")
        void crossTenantIsIndistinguishableFromMissing() throws Exception {
            // The property that matters more than the status code. If these two bodies
            // ever diverge -- a different message, an extra field, different casing --
            // the difference IS the oracle.
            String crossTenant = getProduct(asMgRoadCashier(), airportPillow)
                    .andExpect(status().isNotFound())
                    .andReturn().getResponse().getContentAsString();

            String neverExisted = getProduct(asMgRoadCashier(), UNISSUED_ID)
                    .andExpect(status().isNotFound())
                    .andReturn().getResponse().getContentAsString();

            assertEquals(neverExisted, crossTenant,
                    "another tenant's id must be indistinguishable from one that never existed");
        }

        @Test
        @DisplayName("the same ids DO resolve for their real owner")
        void resolvesForTheOwner() throws Exception {
            // The other half, and what proves this suite tests isolation rather than a
            // broken endpoint. Without it, a get() that always 404'd would pass every
            // case above.
            getProduct(asAirportAdmin(), airportBisleri)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.tenantId").value(id(airport)))
                    .andExpect(jsonPath("$.name").value("Bisleri Packaged Drinking Water"));
        }

        @Test
        @DisplayName("a soft-deleted row is scoped too, not left behind by the active filter")
        void inactiveRowsAreScopedToo() throws Exception {
            getProduct(asAirportAdmin(), mgRoadRetired).andExpect(status().isNotFound());
        }
    }

    /**
     * The write half (C5). <b>A leak through a write is worse than one through a read</b>
     * — a cross-tenant read shows a caller data that is not theirs, a cross-tenant write
     * silently corrupts a store that never made the request and has no way to notice.
     *
     * <p>These cases also cover the half the Hibernate filter does <i>not</i>: it appends
     * to {@code WHERE} clauses, so it scopes the lookup that precedes an {@code UPDATE}
     * but has nothing to say about an {@code INSERT}. The first two below are the filter
     * working; the last one is entirely down to {@code ProductService} stamping the
     * tenant from the session, and it is the only thing standing behind it.
     *
     * <p><b>Mutation-checked, and the split is the point</b> — these four cases are not
     * four ways of asserting one thing:
     *
     * <table>
     *   <caption>What each mutation reddens</caption>
     *   <tr><td>{@code applyToLoadByKey = false}</td>
     *       <td>the three by-id cases here, plus the three C4 read ones — and
     *           <b>not</b> the create case</td></tr>
     *   <tr><td>{@code create} stamps the tenant from the request body</td>
     *       <td><b>only</b> the create case, here and its twin in
     *           {@code ProductWriteIT} — every filter-backed case stays green</td></tr>
     * </table>
     *
     * <p>Row two is why {@code aCreateIgnoresTheTenantInTheBody} exists at all: it is the
     * single case covering the one write path the filter cannot reach.
     */
    @Nested
    @DisplayName("writes cannot cross the boundary either")
    class WritesAreScopedToo {

        @Test
        @DisplayName("updating a t2 product is 404, and identical to an id that never existed")
        void crossTenantUpdateIs404() throws Exception {
            String crossTenant = updateProduct(asAirportAdmin(), mgRoadBisleri, """
                    {"brand":"Written from the wrong store"}
                    """)
                    .andExpect(status().isNotFound())
                    .andReturn().getResponse().getContentAsString();

            String neverExisted = updateProduct(asAirportAdmin(), UNISSUED_ID, """
                    {"brand":"Written from the wrong store"}
                    """)
                    .andExpect(status().isNotFound())
                    .andReturn().getResponse().getContentAsString();

            assertEquals(neverExisted, crossTenant,
                    "a write to another tenant's id must look like a write to a missing one");
        }

        @Test
        @DisplayName("deactivating a t2 product is 404")
        void crossTenantDeactivateIs404() throws Exception {
            deleteProduct(asAirportAdmin(), mgRoadBisleri)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Product not found"));
        }

        /**
         * <b>The case that matters more than either status code.</b> A 404 that had
         * already written would be catastrophic and invisible — the caller sees a
         * failure, the owner sees a changed row, and nothing anywhere reports a problem.
         */
        @Test
        @DisplayName("and neither refusal changed the owner's row")
        void theRefusedWritesDidNotHappen() throws Exception {
            updateProduct(asAirportAdmin(), mgRoadBisleri, """
                    {"brand":"Written from the wrong store","isActive":false}
                    """).andExpect(status().isNotFound());
            deleteProduct(asAirportAdmin(), mgRoadBisleri).andExpect(status().isNotFound());

            // Flush THEN clear, and in that order for a reason. A write that leaked would
            // have left a dirty managed entity; flushing forces it to the database, and
            // clearing makes the read below fetch a row rather than answer from the
            // persistence context. Without this the assertion could pass against an
            // in-memory copy of exactly the state it is trying to disprove.
            em.flush();
            em.clear();

            getProduct(asMgRoadCashier(), mgRoadBisleri)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.brand").value("Bisleri"))
                    .andExpect(jsonPath("$.isActive").value(true));
        }

        @Test
        @DisplayName("a create lands in the caller's tenant however the body is addressed")
        void aCreateIgnoresTheTenantInTheBody() throws Exception {
            // The insert the filter cannot help with. The body names t1 by id and even
            // gives a plausible id of its own; the row has to land in t2 regardless.
            mvc.perform(post("/api/products")
                            .header("Authorization", "Bearer " + asAirportAdmin())
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    {"name":"Smuggled into MG Road","taxRatePercent":5,
                                     "tenantId":"%s","id":"%s"}
                                    """.formatted(id(mgRoad), id(mgRoadBisleri))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.tenantId").value(id(airport)));

            // And t1's catalogue is the size it was. Asserted through the API rather than
            // a count query, because that is the surface a real leak would show up on.
            listProducts(asMgRoadCashier()).andExpect(jsonPath("$.total").value(2));
            listProducts(asAirportAdmin()).andExpect(jsonPath("$.total").value(3));
        }
    }

    @Nested
    @DisplayName("a SUPER_ADMIN has no tenant context")
    class PlatformAdminHasNoTenant {

        /**
         * Requirements.md section 13.2: the POS screens are <b>unavailable</b> to a
         * platform admin, not merely empty. The frontend's equivalent throws
         * "requires a store account" rather than returning {@code []}.
         */
        @Test
        @DisplayName("is refused by tenant-scoped endpoints rather than shown empty lists")
        void isRefusedRatherThanShownNothing() throws Exception {
            listProducts(asPlatformAdmin())
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value(TenantContext.NO_TENANT_MESSAGE));
            listCategories(asPlatformAdmin()).andExpect(status().isForbidden());
            getProduct(asPlatformAdmin(), mgRoadBisleri).andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("and this 403 is safe, unlike every other one this suite forbids")
        void theForbiddenIsSafeHere() throws Exception {
            // The one place C4 answers something other than 404, and the exception has a
            // reason: nothing is concealed. A SUPER_ADMIN is not being told whether an id
            // exists -- it gets the identical answer for an id that never did.
            getProduct(asPlatformAdmin(), UNISSUED_ID).andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("the chain in front of all of it")
    class Chain {

        @Test
        @DisplayName("refuses an unauthenticated caller before scoping is reached at all")
        void anonymousIs401() throws Exception {
            mvc.perform(get("/api/products")).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/products/" + mgRoadBisleri)).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("no endpoint accepts a tenantId, and passing one changes nothing")
        void aTenantIdParameterIsIgnored() throws Exception {
            // The rule the whole design serves, asserted rather than assumed. Spring binds
            // only declared parameters, so this must read exactly like the plain request --
            // and the day someone adds a tenantId parameter, this case is what notices.
            listProducts(asMgRoadCashier(), "tenantId", id(airport))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(2))
                    .andExpect(jsonPath("$.items[*].tenantId", everyItem(is(id(mgRoad)))));
        }
    }

    // --- helpers -----------------------------------------------------------------

    /**
     * Named {@code listProducts} rather than {@code products} because a nested class's
     * {@code @Test products()} would otherwise shadow it — Java resolves the inner name
     * first, and the compile error it produces is nowhere near obvious.
     */
    private ResultActions listProducts(String token, String... params) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/products")
                .header("Authorization", "Bearer " + token)
                .param("pageSize", "200");
        for (int i = 0; i < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        return mvc.perform(request);
    }

    private ResultActions getProduct(String token, Long id) throws Exception {
        return mvc.perform(get("/api/products/" + id).header("Authorization", "Bearer " + token));
    }

    /**
     * Named for the resource rather than the verb, like {@link #listProducts}: a bare
     * {@code delete} would shadow the statically imported request builder of the same
     * name, and Java resolves the member first.
     */
    private ResultActions updateProduct(String token, Long id, String body) throws Exception {
        return mvc.perform(put("/api/products/" + id)
                .header("Authorization", "Bearer " + token)
                .contentType(APPLICATION_JSON)
                .content(body));
    }

    private ResultActions deleteProduct(String token, Long id) throws Exception {
        return mvc.perform(delete("/api/products/" + id).header("Authorization", "Bearer " + token));
    }

    private ResultActions listCategories(String token) throws Exception {
        return mvc.perform(get("/api/products/categories").header("Authorization", "Bearer " + token));
    }

    /** Ids are strings on the wire (see {@code JsonId}), so assertions compare strings. */
    private String id(Long value) {
        return String.valueOf(value);
    }

    private String id(Tenant tenant) {
        return String.valueOf(tenant.getId());
    }

    private String asMgRoadCashier() throws Exception {
        return tokenFor("mg-road", "cashier", "cashier123");
    }

    private String asAirportAdmin() throws Exception {
        return tokenFor("airport", "admin", "admin123");
    }

    private String asPlatformAdmin() throws Exception {
        return tokenFor("platform", "superadmin", "super123");
    }

    private String tokenFor(String tenantCode, String username, String password) throws Exception {
        String body = """
                {"tenantCode":"%s","username":"%s","password":"%s"}
                """.formatted(tenantCode, username, password);
        String response = mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Logging in leaves its own state on the test thread; the request under test has
        // to establish its own from its own token. Cleared here so that a fixture step can
        // never be what makes an assertion pass.
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

    /**
     * Persisted directly rather than through {@code ProductDao}, and not only for brevity:
     * {@code em.persist} is unfiltered, so fixtures for two tenants can be built from a
     * thread that has no tenant of its own. A filtered <i>read</i> from this test body
     * would be answered against {@code NO_TENANT} and find nothing — which is worth
     * knowing before writing a fixture that queries.
     */
    private Long product(Tenant tenant, String name, String brand, String category, boolean active) {
        Product product = new Product();
        product.setTenant(tenant);
        product.setName(name);
        product.setBrand(brand);
        product.setCategory(category);
        product.setTaxRatePercent(new BigDecimal("18.00"));
        product.setActive(active);
        em.persist(product);
        return product.getId();
    }
}

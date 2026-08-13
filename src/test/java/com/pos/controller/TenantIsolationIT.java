package com.pos.controller;

import java.math.BigDecimal;
import java.util.List;

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
import com.pos.pojo.enums.SequenceKind;
import com.pos.pojo.TenantPojo;
import com.pos.pojo.TenantSequencePojo;
import com.pos.pojo.enums.TenantStatus;
import com.pos.pojo.enums.UnitOfMeasure;
import com.pos.pojo.VariantPojo;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesRegex;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
        RootConfig.class, PersistenceConfig.class, SecurityConfig.class, MailConfig.class,
        RecaptchaConfig.class,
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

    private TenantPojo mgRoad;
    private TenantPojo airport;

    /** t2-owned ids in a t1 user's hands — the threat model, as fields. */
    private Long airportBisleri;
    private Long airportPillow;
    private Long mgRoadBisleri;
    private Long mgRoadRetired;

    /** And the variant-shaped half of it, including a label from each store. */
    private Long mgRoadBisleriVariant;
    private Long airportBisleriVariant;
    private String mgRoadBisleriQr;
    private String airportBisleriQr;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        TenantPojo platform = tenant("Platform", "platform", true);
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

        // The same SKU in both stores -- legal, because uniqueness is (tenant_id, sku) --
        // and a label from each. Both stores' runs start at 000001, so the two codes differ
        // only in the tenant segment, which is exactly the confusion a scan has to survive.
        mgRoadBisleriQr = "POS-QR-" + mgRoad.getId() + "-000001";
        mgRoadBisleriVariant = variant(mgRoad, mgRoadBisleri, "1 L", "BISLERI-1L", mgRoadBisleriQr, 80);
        variant(mgRoad, mgRoadBisleri, "500 ml", "BISLERI-500", "POS-QR-" + mgRoad.getId() + "-000002", 100);

        airportBisleriQr = "POS-QR-" + airport.getId() + "-000001";
        airportBisleriVariant =
                variant(airport, airportBisleri, "1 L", "BISLERI-1L", airportBisleriQr, 60);

        // A fixture that writes codes by hand has to leave the counter where the generator
        // would have left it. Without this the next real create mints 000001 again and
        // trips uk_variant_tenant_qrcode -- which is how these two lines came to exist:
        // the SKU case below failed on a DUPLICATE QR, and read at a glance like a leak.
        qrSequence(mgRoad, 3);
        qrSequence(airport, 2);

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

    /**
     * The variant cases from {@code isolation.test.js} (C5), and <b>the scan is the one
     * this whole suite is named for</b>.
     *
     * <p>Every other case here defends against an id that leaked through a URL, a receipt
     * or a bookmark. This one defends against a physical object: a printed label can be
     * carried from one store to another in a way a URL cannot, and the operator scanning it
     * is not an attacker — they are doing their job with the wrong box in their hand. It
     * has to resolve to nothing, exactly as an unissued code does.
     *
     * <h2>Mutation-checked, and the result was not what was expected</h2>
     * Removing {@code @Filter} from {@code VariantPojo} <b>leaves the scan, the search and the
     * by-product list green</b>. It reddens exactly two cases here plus both coverage
     * assertions.
     *
     * <p>The reason is real rather than a fluke: every read in {@code VariantDao} is
     * {@code JOIN FETCH v.product p}, and {@code ProductPojo} is filtered — so Hibernate scopes
     * those queries through the join whether or not the variant carries a filter of its
     * own. What reddens is precisely the two paths that do <b>not</b> join a product:
     * {@code em.find} by id (so {@code crossTenantVariantWritesAre404} answers 400 instead
     * of 404, having loaded the row) and {@code VariantDao.skuExists}, whose count then
     * spans every store and reports another tenant's SKU as taken.
     *
     * <p><b>Two consequences worth carrying into C6 and C7.</b> A query that joins a
     * filtered parent is scoped even if the child's own filter is missing — which means a
     * green isolation case does not prove the child is annotated, and
     * {@code TenantFilterCoverageTest} is what actually holds that line. And the reverse:
     * an aggregate over a child <i>alone</i> is protected by nothing but that annotation,
     * which is the shape {@code skuExists} has and the shape a stock or totals query will
     * have.
     */
    @Nested
    @DisplayName("variants — the scan, the search and the shared SKU")
    class VariantsAreScopedToo {

        @Test
        @DisplayName("scanning the other store's label resolves as an unknown code")
        void scanningAForeignLabelIs404() throws Exception {
            String crossTenant = lookup(asMgRoadCashier(), airportBisleriQr)
                    .andExpect(status().isNotFound())
                    .andReturn().getResponse().getContentAsString();

            String neverIssued = lookup(asMgRoadCashier(), "POS-QR-" + id(mgRoad) + "-999999")
                    .andExpect(status().isNotFound())
                    .andReturn().getResponse().getContentAsString();

            assertEquals(neverIssued, crossTenant,
                    "a label from another store must be indistinguishable from a code that "
                            + "was never issued");
        }

        @Test
        @DisplayName("but each store's own label scans fine")
        void yourOwnLabelScans() throws Exception {
            lookup(asMgRoadCashier(), mgRoadBisleriQr)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.tenantId").value(id(mgRoad)))
                    .andExpect(jsonPath("$.sku").value("BISLERI-1L"));

            lookup(asAirportAdmin(), airportBisleriQr)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.tenantId").value(id(airport)));
        }

        @Test
        @DisplayName("search never crosses over, on a product both stores stock")
        void searchNeverCrossesOver() throws Exception {
            // A substring match would happily return the other store's rows if the query
            // were not scoped first -- which is why both stores sell Bisleri. The counts
            // differ (MG Road stocks two sizes, Airport one), so a crossed search would
            // show three either way and neither store's answer could be mistaken for the
            // other's.
            searchVariants(asMgRoadCashier(), "bisleri")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(2)))
                    .andExpect(jsonPath("$[*].tenantId", everyItem(is(id(mgRoad)))));

            searchVariants(asAirportAdmin(), "bisleri")
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].tenantId").value(id(airport)));
        }

        @Test
        @DisplayName("listing a foreign product's variants is empty, not a 404 and not a leak")
        void listingAForeignProductsVariantsIsEmpty() throws Exception {
            mvc.perform(get("/api/products/" + airportBisleri + "/variants")
                            .header("Authorization", "Bearer " + asMgRoadCashier()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));
        }

        @Test
        @DisplayName("a t2 variant cannot be updated, deactivated or re-coded from t1")
        void crossTenantVariantWritesAre404() throws Exception {
            mvc.perform(put("/api/variants/" + airportBisleriVariant)
                            .header("Authorization", "Bearer " + asMgRoadAdmin())
                            .contentType(APPLICATION_JSON)
                            .content("{\"stockQuantity\":0}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Variant not found"));

            mvc.perform(delete("/api/variants/" + airportBisleriVariant)
                            .header("Authorization", "Bearer " + asMgRoadAdmin()))
                    .andExpect(status().isNotFound());

            // Re-coding someone else's variant would be the worst of the three: it does
            // not read their data, it invalidates the labels on their shelves.
            mvc.perform(post("/api/variants/" + airportBisleriVariant + "/qr-code")
                            .header("Authorization", "Bearer " + asMgRoadAdmin()))
                    .andExpect(status().isNotFound());

            em.flush();
            em.clear();

            lookup(asAirportAdmin(), airportBisleriQr)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.stockQuantity").value(60))
                    .andExpect(jsonPath("$.isActive").value(true));
        }

        /**
         * The pair from {@code isolation.test.js}: {@code BISLERI-1L} is taken in one store
         * and free in the other. A schema with a global unique key would pass the first
         * case and fail the second, which is why both are here.
         */
        @Test
        @DisplayName("a SKU is taken within a store and free in the other")
        void skuUniquenessIsPerTenant() throws Exception {
            createVariant(asMgRoadAdmin(), mgRoadBisleri, "BISLERI-1L")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fields.sku").value("SKU is already in use"));

            createVariant(asAirportAdmin(), airportPillow, "BISLERI-500")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.tenantId").value(id(airport)))
                    // And its code comes from ITS OWN run, not from a global counter.
                    .andExpect(jsonPath("$.qrCode").value(startsWith("POS-QR-" + id(airport) + "-")));
        }
    }

    /**
     * The order cases from {@code isolation.test.js} (C6). Orders have no cross-tenant
     * <i>write</i> case shaped like {@code aCreateIgnoresTheTenantInTheBody} above —
     * {@code OrderForm} has no {@code tenantId} field to smuggle one through in the first
     * place, unlike {@code ProductForm} — so what is worth proving here is different: that
     * the per-tenant order-number sequence really is independent (both stores' first order
     * can be {@code -0001}), and that every other endpoint answers the same 404-not-403 for
     * an id that leaked across the boundary, exactly like every resource before it.
     */
    @Nested
    @DisplayName("orders — per-tenant numbering, and every endpoint scoped")
    class OrdersAreScopedToo {

        @Test
        @DisplayName("both stores' first order is numbered -0001 — the sequences don't share a counter")
        void orderNumbersAreScopedPerTenant() throws Exception {
            createOrder(asMgRoadCashier(), mgRoadBisleriVariant, 1)
                    .andExpect(jsonPath("$.orderNumber", matchesRegex("ORD-\\d{4}-0001")));
            createOrder(asAirportAdmin(), airportBisleriVariant, 1)
                    .andExpect(jsonPath("$.orderNumber", matchesRegex("ORD-\\d{4}-0001")));
        }

        @Test
        @DisplayName("a t2 order id is 404 for a t1 caller, identical to one that never existed")
        void crossTenantGetIs404() throws Exception {
            Long orderId = createOrderId(asMgRoadCashier(), mgRoadBisleriVariant, 1);
            // The order just created is still MANAGED in this transaction's shared
            // persistence context -- em.find() would hand it back from the first-level
            // cache without issuing SQL at all, and a filter can only scope a query that
            // actually runs. See setUp()'s identical note on the em.persist fixtures.
            em.flush();
            em.clear();

            String crossTenant = getOrder(asAirportAdmin(), orderId)
                    .andExpect(status().isNotFound())
                    .andReturn().getResponse().getContentAsString();
            String neverExisted = getOrder(asAirportAdmin(), UNISSUED_ID)
                    .andExpect(status().isNotFound())
                    .andReturn().getResponse().getContentAsString();

            assertEquals(neverExisted, crossTenant,
                    "another tenant's order id must be indistinguishable from one that never existed");
        }

        @Test
        @DisplayName("a t2 caller cannot patch a t1 order, and nothing about it changed")
        void crossTenantPatchIs404AndChangesNothing() throws Exception {
            Long orderId = createOrderId(asMgRoadCashier(), mgRoadBisleriVariant, 1);
            em.flush();
            em.clear();

            mvc.perform(patch("/api/orders/" + orderId)
                            .header("Authorization", "Bearer " + asAirportAdmin())
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    {"status":"CANCELLED"}
                                    """))
                    .andExpect(status().isNotFound());

            getOrder(asMgRoadCashier(), orderId)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("DRAFT"));
        }

        @Test
        @DisplayName("a t2 caller cannot pay a t1 order, and it is still unpaid")
        void crossTenantPaymentIs404AndLeavesItUnpaid() throws Exception {
            Long orderId = createOrderId(asMgRoadCashier(), mgRoadBisleriVariant, 1);
            em.flush();
            em.clear();

            mvc.perform(post("/api/orders/" + orderId + "/payments")
                            .header("Authorization", "Bearer " + asAirportAdmin())
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    {"method":"CARD"}
                                    """))
                    .andExpect(status().isNotFound());

            getOrder(asMgRoadCashier(), orderId)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("DRAFT"))
                    .andExpect(jsonPath("$.payment").doesNotExist());
        }

        @Test
        @DisplayName("list never crosses over — each store sees only its own order")
        void listNeverCrossesOver() throws Exception {
            createOrder(asMgRoadCashier(), mgRoadBisleriVariant, 1);
            createOrder(asAirportAdmin(), airportBisleriVariant, 1);

            listOrders(asMgRoadCashier())
                    .andExpect(jsonPath("$.total").value(1))
                    .andExpect(jsonPath("$.items[0].tenantId").value(id(mgRoad)));
            listOrders(asAirportAdmin())
                    .andExpect(jsonPath("$.total").value(1))
                    .andExpect(jsonPath("$.items[0].tenantId").value(id(airport)));
        }
    }

    /**
     * The order-lookup cases from {@code isolation.test.js} (C7). Order numbers repeat
     * across tenants — both stores' first sale can be {@code -0001} — so the case worth
     * proving is not just "an unknown number is 404" but that the <b>same</b> number
     * resolves to each caller's own order, never the other tenant's.
     */
    @Nested
    @DisplayName("order lookup (for returns) is scoped too")
    class OrderLookupIsScopedToo {

        @Test
        @DisplayName("the same order number resolves to each tenant's own order, not the other's")
        void sameNumberResolvesToTheCallersOwnOrder() throws Exception {
            PaidOrder mgOrder = createAndPayOrder(asMgRoadCashier(), mgRoadBisleriVariant, 1);
            PaidOrder airportOrder = createAndPayOrder(asAirportAdmin(), airportBisleriVariant, 2);
            assertEquals(mgOrder.orderNumber(), airportOrder.orderNumber(),
                    "fixture assumption: both stores' first sale shares a number — if this "
                            + "ever fails, the two lookups below just prove less than intended");
            em.flush();
            em.clear();

            lookupOrder(asMgRoadCashier(), mgOrder.orderNumber())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.tenantId").value(id(mgRoad)))
                    .andExpect(jsonPath("$.items[0].quantity").value(1));

            lookupOrder(asAirportAdmin(), airportOrder.orderNumber())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.tenantId").value(id(airport)))
                    .andExpect(jsonPath("$.items[0].quantity").value(2));
        }

        @Test
        @DisplayName("a number that exists only in the other tenant is 404, identical to one that never existed")
        void aForeignOnlyNumberIs404() throws Exception {
            createAndPayOrder(asAirportAdmin(), airportBisleriVariant, 1);
            // Airport's SECOND number, guaranteed absent from MG Road regardless of
            // whether the two stores' FIRST numbers happen to collide.
            PaidOrder airportSecond = createAndPayOrder(asAirportAdmin(), airportBisleriVariant, 1);
            em.flush();
            em.clear();

            String crossTenant = lookupOrder(asMgRoadCashier(), airportSecond.orderNumber())
                    .andExpect(status().isNotFound())
                    .andReturn().getResponse().getContentAsString();
            String neverExisted = lookupOrder(asMgRoadCashier(), "NOPE-9999")
                    .andExpect(status().isNotFound())
                    .andReturn().getResponse().getContentAsString();

            assertEquals(neverExisted, crossTenant,
                    "an order number from another tenant must be indistinguishable from one "
                            + "that never existed");
        }
    }

    /**
     * The return cases from {@code isolation.test.js} (C7). A return references its
     * original order by id and inherits that order's tenant rather than re-reading the
     * session — everything downstream (get, list, the return number itself) has to stay
     * inside the boundary exactly like an order does.
     */
    @Nested
    @DisplayName("returns are scoped too")
    class ReturnsAreScopedToo {

        @Test
        @DisplayName("a t2 caller cannot return against a t1 order, and its stock stays decremented")
        void crossTenantCreateIs404AndLeavesStockAlone() throws Exception {
            PaidOrder mgOrder = createAndPayOrder(asMgRoadCashier(), mgRoadBisleriVariant, 1);
            em.flush();
            em.clear();

            createReturn(asAirportAdmin(), mgOrder.id(), mgRoadBisleriVariant, 1)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Order not found"));

            em.flush();
            em.clear();
            // Stock was decremented by the payment above (80 -> 79) and must NOT have been
            // restored by a return that should never have been allowed to happen.
            lookup(asMgRoadCashier(), mgRoadBisleriQr)
                    .andExpect(jsonPath("$.stockQuantity").value(79));
        }

        @Test
        @DisplayName("a t2 return id is 404 for a t1 caller, identical to one that never existed")
        void crossTenantGetIs404() throws Exception {
            PaidOrder mgOrder = createAndPayOrder(asMgRoadCashier(), mgRoadBisleriVariant, 1);
            String mgReturnId = createReturnId(asMgRoadCashier(), mgOrder.id(), mgRoadBisleriVariant, 1);
            em.flush();
            em.clear();

            String crossTenant = getReturn(asAirportAdmin(), mgReturnId)
                    .andExpect(status().isNotFound())
                    .andReturn().getResponse().getContentAsString();
            String neverExisted = getReturn(asAirportAdmin(), String.valueOf(UNISSUED_ID))
                    .andExpect(status().isNotFound())
                    .andReturn().getResponse().getContentAsString();

            assertEquals(neverExisted, crossTenant,
                    "another tenant's return id must be indistinguishable from one that never existed");
        }

        @Test
        @DisplayName("list never crosses over — each store sees only its own returns")
        void listNeverCrossesOver() throws Exception {
            PaidOrder mgOrder = createAndPayOrder(asMgRoadCashier(), mgRoadBisleriVariant, 1);
            createReturn(asMgRoadCashier(), mgOrder.id(), mgRoadBisleriVariant, 1)
                    .andExpect(status().isCreated());

            PaidOrder airportOrder = createAndPayOrder(asAirportAdmin(), airportBisleriVariant, 1);
            createReturn(asAirportAdmin(), airportOrder.id(), airportBisleriVariant, 1)
                    .andExpect(status().isCreated());

            listReturns(asMgRoadCashier())
                    .andExpect(jsonPath("$.total").value(1))
                    .andExpect(jsonPath("$.items[0].tenantId").value(id(mgRoad)));
            listReturns(asAirportAdmin())
                    .andExpect(jsonPath("$.total").value(1))
                    .andExpect(jsonPath("$.items[0].tenantId").value(id(airport)));
        }

        @Test
        @DisplayName("both stores' first return is numbered -0001 — the sequences don't share a counter")
        void returnNumbersAreScopedPerTenant() throws Exception {
            PaidOrder mgOrder = createAndPayOrder(asMgRoadCashier(), mgRoadBisleriVariant, 1);
            PaidOrder airportOrder = createAndPayOrder(asAirportAdmin(), airportBisleriVariant, 1);

            createReturn(asMgRoadCashier(), mgOrder.id(), mgRoadBisleriVariant, 1)
                    .andExpect(jsonPath("$.returnNumber", matchesRegex("RET-\\d{4}-0001")));
            createReturn(asAirportAdmin(), airportOrder.id(), airportBisleriVariant, 1)
                    .andExpect(jsonPath("$.returnNumber", matchesRegex("RET-\\d{4}-0001")));
        }
    }

    /**
     * {@code AppUserPojo} carries no {@code @Filter} (see its own class Javadoc) — the one
     * entity this suite exercises where scoping is enforced by hand rather than by the
     * Hibernate filter every other case here relies on. A regression in
     * {@code AppUserDao.findByTenant}/{@code findInTenant} would show up nowhere else:
     * {@code TenantFilterCoverageTest} explicitly excludes {@code AppUserPojo} as unfiltered
     * by design, so this is the only automated check that a t1 admin cannot read or write
     * a t2 user.
     */
    @Nested
    @DisplayName("users are scoped too")
    class UsersAreScopedToo {

        @Test
        @DisplayName("list never crosses over -- each store sees only its own users")
        void listNeverCrossesOver() throws Exception {
            listUsers(asMgRoadAdmin())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(2)))
                    .andExpect(jsonPath("$[*].tenantId", everyItem(is(id(mgRoad)))));
            listUsers(asAirportAdmin())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[*].tenantId", everyItem(is(id(airport)))));
        }

        @Test
        @DisplayName("a t2 user id is 404 for a t1 admin on both PUT and DELETE, identical to one that never existed")
        void crossTenantWriteIs404() throws Exception {
            Long airportAdminId = userId(asAirportAdmin(), "admin");

            String crossTenant = updateUser(asMgRoadAdmin(), airportAdminId, """
                    {"displayName":"pwned"}
                    """)
                    .andExpect(status().isNotFound())
                    .andReturn().getResponse().getContentAsString();
            String neverExisted = updateUser(asMgRoadAdmin(), UNISSUED_ID, """
                    {"displayName":"pwned"}
                    """)
                    .andExpect(status().isNotFound())
                    .andReturn().getResponse().getContentAsString();
            assertEquals(neverExisted, crossTenant,
                    "another tenant's user id must be indistinguishable from one that never existed");

            deleteUser(asMgRoadAdmin(), airportAdminId).andExpect(status().isNotFound());

            // And nothing changed -- the airport admin is still there, active, untouched.
            listUsers(asAirportAdmin())
                    .andExpect(jsonPath("$[?(@.username=='admin')].isActive").value(hasItem(true)))
                    .andExpect(jsonPath("$[?(@.username=='admin')].displayName").value(hasItem("admin")));
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

    private String id(TenantPojo tenant) {
        return String.valueOf(tenant.getId());
    }

    private ResultActions lookup(String token, String qrCode) throws Exception {
        return mvc.perform(get("/api/variants/lookup")
                .header("Authorization", "Bearer " + token)
                .param("qrCode", qrCode));
    }

    private ResultActions searchVariants(String token, String term) throws Exception {
        return mvc.perform(get("/api/variants/search")
                .header("Authorization", "Bearer " + token)
                .param("q", term));
    }

    private ResultActions createVariant(String token, Long productId, String sku) throws Exception {
        return mvc.perform(post("/api/products/" + productId + "/variants")
                .header("Authorization", "Bearer " + token)
                .contentType(APPLICATION_JSON)
                .content("""
                        {"variantLabel":"probe","sku":"%s","mrp":20,"sellingPrice":20}
                        """.formatted(sku)));
    }

    private ResultActions createOrder(String token, Long variantId, int quantity) throws Exception {
        return mvc.perform(post("/api/orders")
                .header("Authorization", "Bearer " + token)
                .contentType(APPLICATION_JSON)
                .content("""
                        {"items":[{"variantId":"%s","quantity":%d}]}
                        """.formatted(variantId, quantity)));
    }

    private Long createOrderId(String token, Long variantId, int quantity) throws Exception {
        String response = createOrder(token, variantId, quantity)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return Long.valueOf((String) JsonPath.read(response, "$.id"));
    }

    private ResultActions getOrder(String token, Long orderId) throws Exception {
        return mvc.perform(get("/api/orders/" + orderId).header("Authorization", "Bearer " + token));
    }

    private ResultActions listOrders(String token) throws Exception {
        return mvc.perform(get("/api/orders")
                .header("Authorization", "Bearer " + token)
                .param("pageSize", "200"));
    }

    /** What a return case needs from a completed order: the id to return against, and
     * the number two fixtures can compare without a second round trip. */
    private record PaidOrder(String id, String orderNumber) {
    }

    private PaidOrder createAndPayOrder(String token, Long variantId, int quantity) throws Exception {
        String response = createOrder(token, variantId, quantity)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String orderId = JsonPath.read(response, "$.id");
        String orderNumber = JsonPath.read(response, "$.orderNumber");

        mvc.perform(post("/api/orders/" + orderId + "/payments")
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"method":"CARD"}
                                """))
                .andExpect(status().isOk());

        return new PaidOrder(orderId, orderNumber);
    }

    private ResultActions lookupOrder(String token, String orderNumber) throws Exception {
        return mvc.perform(get("/api/orders/lookup")
                .header("Authorization", "Bearer " + token)
                .param("orderNumber", orderNumber));
    }

    private ResultActions createReturn(String token, String originalOrderId, Long variantId, int quantity)
            throws Exception {
        return mvc.perform(post("/api/returns")
                .header("Authorization", "Bearer " + token)
                .contentType(APPLICATION_JSON)
                .content("""
                        {"originalOrderId":"%s","items":[{"variantId":"%s","quantity":%d}]}
                        """.formatted(originalOrderId, variantId, quantity)));
    }

    private String createReturnId(String token, String originalOrderId, Long variantId, int quantity)
            throws Exception {
        String response = createReturn(token, originalOrderId, variantId, quantity)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    private ResultActions getReturn(String token, String id) throws Exception {
        return mvc.perform(get("/api/returns/" + id).header("Authorization", "Bearer " + token));
    }

    private ResultActions listReturns(String token) throws Exception {
        return mvc.perform(get("/api/returns")
                .header("Authorization", "Bearer " + token)
                .param("pageSize", "200"));
    }

    private ResultActions listUsers(String token) throws Exception {
        return mvc.perform(get("/api/users").header("Authorization", "Bearer " + token));
    }

    private ResultActions updateUser(String token, Long id, String body) throws Exception {
        return mvc.perform(put("/api/users/" + id)
                .header("Authorization", "Bearer " + token)
                .contentType(APPLICATION_JSON)
                .content(body));
    }

    private ResultActions deleteUser(String token, Long id) throws Exception {
        return mvc.perform(delete("/api/users/" + id).header("Authorization", "Bearer " + token));
    }

    /** Reads a seeded user's id back off the list, so a fixture never has to guess one. */
    private Long userId(String token, String username) throws Exception {
        String response = listUsers(token).andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(response, "$[?(@.username == '" + username + "')].id");
        return Long.valueOf(ids.get(0));
    }

    private String asMgRoadCashier() throws Exception {
        return tokenFor("mg-road", "cashier", "cashier123");
    }

    /** Writes need an ADMIN — the catalogue role rule lands before the tenant one. */
    private String asMgRoadAdmin() throws Exception {
        return tokenFor("mg-road", "admin", "admin123");
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
        String response = mvc.perform(post("/api/auth/login").with(TestIps.remoteAddr(TestIps.fresh())).contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Logging in leaves its own state on the test thread; the request under test has
        // to establish its own from its own token. Cleared here so that a fixture step can
        // never be what makes an assertion pass.
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
    /**
     * Persisted directly, and the {@code qrCode} is a literal rather than a value from
     * {@code TenantSequenceDao}. Deliberate: a fixture that called the real generator would
     * make the codes depend on insert order, and these two have to be predictable enough to
     * write into an assertion. The sequence has its own suite, {@code VariantSequenceIT}.
     */
    private Long variant(TenantPojo tenant, Long productId, String label, String sku,
                         String qrCode, int stock) {
        VariantPojo variant = new VariantPojo();
        variant.setTenant(tenant);
        variant.setProduct(em.getReference(ProductPojo.class, productId));
        variant.setVariantLabel(label);
        variant.setSku(sku);
        variant.setQrCode(qrCode);
        variant.setMrp(new BigDecimal("20.00"));
        variant.setSellingPrice(new BigDecimal("20.00"));
        variant.setStockQuantity(stock);
        variant.setUnitOfMeasure(UnitOfMeasure.EACH);
        variant.setActive(true);
        em.persist(variant);
        return variant.getId();
    }

    /** Where {@code TenantSequenceDao} would have left this store's QR counter. */
    private void qrSequence(TenantPojo tenant, long nextValue) {
        TenantSequencePojo sequence = new TenantSequencePojo(tenant, SequenceKind.QR);
        sequence.setNextValue(nextValue);
        em.persist(sequence);
    }

    private Long product(TenantPojo tenant, String name, String brand, String category, boolean active) {
        ProductPojo product = new ProductPojo();
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

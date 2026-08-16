package com.pos.controller;

import java.util.List;

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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.hasSize;
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
 * Peer-review Phase 3: the product/variant active-status sync — the bidirectional
 * cascade between {@code ProductService} and {@code VariantService} that
 * {@code ProductWriteIT}/{@code VariantIT} don't cover, since each of those suites is
 * about one endpoint family in isolation and this behaviour is the two of them talking
 * to each other. A dedicated file for the same reason
 * {@code AbandonedTenantCleanupServiceIT} is one — new cross-entity behaviour, not a
 * CRUD case that fits an existing suite's shape.
 *
 * <p>Manually verified end-to-end against a real running app (curl) before writing
 * this — all four cascade rules plus the zero-variant exemption, both via the
 * dedicated {@code DELETE} and via the merge-patch {@code {"isActive": false}} path.
 * See review/peer-review.md's Phase 3 item for the full spec these tests pin.
 */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = {
        RootConfig.class, PersistenceConfig.class, SecurityConfig.class, MailConfig.class,
        RecaptchaConfig.class, ImagesConfig.class,
        WebConfig.class, OpenApiConfig.class })
@TestPropertySource("classpath:application-test.properties")
@Transactional
@DisplayName("Product/variant active-status sync")
class ProductVariantActiveSyncIT {

    private static final BCryptPasswordEncoder HASHER = new BCryptPasswordEncoder();
    private static final String ADMIN_HASH = HASHER.encode("admin123");

    @Autowired
    private WebApplicationContext context;

    @PersistenceContext
    private EntityManager em;

    private MockMvc mvc;
    private TenantPojo mgRoad;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        mgRoad = tenant("MG Road Store", "mg-road");
        user(mgRoad, "admin", ADMIN_HASH, Role.ADMIN);
        em.flush();
        em.clear();
    }

    @AfterEach
    void clearThreadState() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    @DisplayName("deactivating a product cascades down to every one of its variants")
    void deactivatingAProductCascadesDown() throws Exception {
        String productId = createProduct();
        createVariant(productId, "V1");
        createVariant(productId, "V2");

        deleteProduct(productId).andExpect(jsonPath("$.isActive").value(false));

        listVariants(productId)
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].isActive").value(false))
                .andExpect(jsonPath("$[1].isActive").value(false));
    }

    @Test
    @DisplayName("reactivating a product cascades down to every one of its variants")
    void reactivatingAProductCascadesDown() throws Exception {
        String productId = createProduct();
        createVariant(productId, "V1");
        createVariant(productId, "V2");
        deleteProduct(productId);

        reactivateProduct(productId).andExpect(jsonPath("$.isActive").value(true));

        listVariants(productId)
                .andExpect(jsonPath("$[0].isActive").value(true))
                .andExpect(jsonPath("$[1].isActive").value(true));
    }

    /**
     * The "full symmetry" half of the spec: reactivating a product is not "restore
     * each variant's own prior state" — it is "every variant is active now", the same
     * as deactivating overrides every variant regardless of what each one was doing on
     * its own beforehand.
     */
    @Test
    @DisplayName("reactivating a product overrides a variant that was individually deactivated")
    void reactivatingAProductOverridesAnIndividuallyDeactivatedVariant() throws Exception {
        String productId = createProduct();
        String v1 = createVariant(productId, "V1");
        createVariant(productId, "V2");

        deleteVariant(v1);
        deleteProduct(productId);

        reactivateProduct(productId);

        listVariants(productId)
                .andExpect(jsonPath("$[0].isActive").value(true))
                .andExpect(jsonPath("$[1].isActive").value(true));
    }

    @Test
    @DisplayName("deactivating the last active variant auto-deactivates its product")
    void deactivatingTheLastActiveVariantAutoDeactivatesTheProduct() throws Exception {
        String productId = createProduct();
        String v1 = createVariant(productId, "V1");
        String v2 = createVariant(productId, "V2");

        deleteVariant(v1);
        getProduct(productId).andExpect(jsonPath("$.isActive").value(true));

        deleteVariant(v2);
        getProduct(productId).andExpect(jsonPath("$.isActive").value(false));
    }

    /**
     * The mirror rule, and precise about it: reactivating one variant brings the
     * product back, but does not itself reactivate the variant's own sibling.
     */
    @Test
    @DisplayName("reactivating one variant of an inactive product auto-reactivates the product, alone")
    void reactivatingOneVariantAutoReactivatesTheProduct() throws Exception {
        String productId = createProduct();
        String v1 = createVariant(productId, "V1");
        String v2 = createVariant(productId, "V2");
        deleteVariant(v1);
        deleteVariant(v2);
        getProduct(productId).andExpect(jsonPath("$.isActive").value(false));

        reactivateVariant(v1).andExpect(jsonPath("$.isActive").value(true));

        getProduct(productId).andExpect(jsonPath("$.isActive").value(true));
        String variants = listVariants(productId).andReturn().getResponse().getContentAsString();
        List<Boolean> siblingActive = JsonPath.read(variants,
                "$[?(@.id=='" + v2 + "')].isActive");
        assertEquals(List.of(false), siblingActive);
    }

    @Test
    @DisplayName("a product with zero variants is exempt: cascade-down is a no-op, not an error")
    void zeroVariantProductIsExempt() throws Exception {
        String productId = createProduct();

        deleteProduct(productId).andExpect(jsonPath("$.isActive").value(false));
        reactivateProduct(productId).andExpect(jsonPath("$.isActive").value(true));

        listVariants(productId).andExpect(jsonPath("$", hasSize(0)));
    }

    /**
     * The less-obvious path: {@code PUT} with {@code {"isActive": false}} means the
     * same thing as {@code DELETE} and has to cascade identically, even though the
     * frontend only ever reaches deactivation through {@code DELETE}.
     */
    @Test
    @DisplayName("a merge patch setting isActive:false cascades the same as DELETE, on both sides")
    void mergePatchDeactivationCascadesTheSameAsDelete() throws Exception {
        String productId = createProduct();
        createVariant(productId, "V1");

        updateProduct(productId, """
                {"isActive":false}
                """).andExpect(jsonPath("$.isActive").value(false));
        listVariants(productId).andExpect(jsonPath("$[0].isActive").value(false));

        String productId2 = createProduct();
        String onlyVariant = createVariant(productId2, "V1");
        updateVariant(onlyVariant, """
                {"isActive":false}
                """).andExpect(jsonPath("$.isActive").value(false));
        getProduct(productId2).andExpect(jsonPath("$.isActive").value(false));
    }

    // --- helpers -----------------------------------------------------------------

    private static final String AUTH = "Authorization";

    private String createProduct() throws Exception {
        String response = mvc.perform(post("/api/products")
                        .header(AUTH, bearer(asAdmin()))
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"name":"Sync Test Product","taxRatePercent":5}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    private String createVariant(String productId, String label) throws Exception {
        String response = mvc.perform(post("/api/products/" + productId + "/variants")
                        .header(AUTH, bearer(asAdmin()))
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"variantLabel":"%s","mrp":100,"sellingPrice":90,"stockQuantity":5}
                                """.formatted(label)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    /**
     * {@code em.clear()} after the request, not before: {@code ProductService}'s
     * cascade-down is a bulk JPQL {@code UPDATE} ({@code VariantDao.setActiveByProduct}),
     * which writes straight to the database and does not sync entities this same test
     * transaction already has loaded — the exact "same-transaction bulk write" gotcha
     * peer-review's session notes call out. A real HTTP request never hits this: each
     * one gets its own fresh persistence context, so this is a test-boundary artifact
     * (multiple {@code mvc.perform} calls sharing one {@code @Transactional} test's
     * {@code EntityManager}), not a production bug — verified live against a real
     * running app first, where a plain {@code GET} after the same sequence always saw
     * the cascaded state correctly.
     */
    private ResultActions deleteProduct(String productId) throws Exception {
        ResultActions result = mvc.perform(delete("/api/products/" + productId).header(AUTH, bearer(asAdmin())))
                .andExpect(status().isOk());
        em.clear();
        return result;
    }

    private ResultActions updateProduct(String productId, String body) throws Exception {
        ResultActions result = mvc.perform(put("/api/products/" + productId)
                .header(AUTH, bearer(asAdmin()))
                .contentType(APPLICATION_JSON)
                .content(body));
        em.clear();
        return result;
    }

    private ResultActions reactivateProduct(String productId) throws Exception {
        return updateProduct(productId, """
                {"isActive":true}
                """).andExpect(status().isOk());
    }

    private ResultActions deleteVariant(String variantId) throws Exception {
        return mvc.perform(delete("/api/variants/" + variantId).header(AUTH, bearer(asAdmin())))
                .andExpect(status().isOk());
    }

    private ResultActions updateVariant(String variantId, String body) throws Exception {
        return mvc.perform(put("/api/variants/" + variantId)
                .header(AUTH, bearer(asAdmin()))
                .contentType(APPLICATION_JSON)
                .content(body));
    }

    private ResultActions reactivateVariant(String variantId) throws Exception {
        return updateVariant(variantId, """
                {"isActive":true}
                """).andExpect(status().isOk());
    }

    private ResultActions getProduct(String productId) throws Exception {
        return mvc.perform(get("/api/products/" + productId).header(AUTH, bearer(asAdmin())));
    }

    private ResultActions listVariants(String productId) throws Exception {
        return mvc.perform(get("/api/products/" + productId + "/variants").header(AUTH, bearer(asAdmin())));
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private String asAdmin() throws Exception {
        String body = """
                {"tenantCode":"mg-road","username":"admin","password":"admin123"}
                """;
        String response = mvc.perform(post("/api/auth/login")
                        .with(TestIps.remoteAddr(TestIps.fresh()))
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Logging in leaves its own state on the test thread; the request under test
        // has to establish its own from its own token.
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
}

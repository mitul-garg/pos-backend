package com.pos.controller;

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
import com.pos.util.TestIps;
import com.pos.util.tenancy.TenantContext;
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

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Peer-review Phase 3: the three product-image endpoints
 * ({@code POST .../image-upload-url}, {@code PUT .../image}, {@code DELETE .../image}).
 * A dedicated file for the same reason {@code ProductVariantActiveSyncIT} is one — new
 * behaviour, not a CRUD case an existing suite's shape fits.
 *
 * <p><b>What this suite can and can't prove.</b> {@code pos.images.enabled=false} in
 * every test environment (application-test.properties, {@code NoopImageSigner} selected)
 * means the actual signing/GCS round trip is structurally unreachable from {@code mvn
 * test} — the same boundary {@code GoogleRecaptchaVerifierTest} draws for the real
 * Google network call. What's provable here instead: auth/role gating, tenant isolation
 * (404, independent of the enabled flag — {@code ProductService}'s check order puts
 * existence first specifically so this is testable), request-shape validation
 * (content-type, ordered before the enabled-gate for the same reason), and the
 * enabled-gate's own clean-400 behaviour. The real signing/upload/read/delete round trip
 * against the live bucket was verified manually against a real running app — see the
 * peer-review commit message.
 */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = {
        RootConfig.class, PersistenceConfig.class, SecurityConfig.class, MailConfig.class,
        RecaptchaConfig.class, ImagesConfig.class,
        WebConfig.class, OpenApiConfig.class })
@TestPropertySource("classpath:application-test.properties")
@Transactional
@DisplayName("Product image endpoints")
class ProductImageIT {

    private static final BCryptPasswordEncoder HASHER = new BCryptPasswordEncoder();
    private static final String ADMIN_HASH = HASHER.encode("admin123");
    private static final String AUTH = "Authorization";

    @Autowired
    private WebApplicationContext context;

    @PersistenceContext
    private EntityManager em;

    private MockMvc mvc;
    private TenantPojo mgRoad;
    private TenantPojo airport;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        mgRoad = tenant("MG Road Store", "mg-road");
        airport = tenant("Airport Store", "airport");
        user(mgRoad, "admin", ADMIN_HASH, Role.ADMIN);
        user(mgRoad, "cashier", ADMIN_HASH, Role.CASHIER);
        user(airport, "admin", ADMIN_HASH, Role.ADMIN);
        em.flush();
        em.clear();
    }

    @AfterEach
    void clearThreadState() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    @DisplayName("mint-upload-url: ADMIN on their own product gets the clean not-enabled 400, not a 500")
    void mintUploadUrlNotEnabled() throws Exception {
        String productId = createProduct(asAdmin(mgRoad, "admin"));

        mintUploadUrl(productId, "image/jpeg", asAdmin(mgRoad, "admin"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Product images are not enabled on this deployment"));
    }

    @Test
    @DisplayName("mint-upload-url: CASHIER is rejected by the security chain, not the service")
    void mintUploadUrlCashierForbidden() throws Exception {
        String productId = createProduct(asAdmin(mgRoad, "admin"));

        mintUploadUrl(productId, "image/jpeg", asAdmin(mgRoad, "cashier")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("mint-upload-url: a product belonging to another tenant answers 404, regardless of the enabled flag")
    void mintUploadUrlCrossTenant() throws Exception {
        String productId = createProduct(asAdmin(mgRoad, "admin"));

        mintUploadUrl(productId, "image/jpeg", asAdmin(airport, "admin")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("mint-upload-url: a nonexistent product id answers 404")
    void mintUploadUrlNotFound() throws Exception {
        mintUploadUrl("999999999", "image/jpeg", asAdmin(mgRoad, "admin")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("mint-upload-url: a disallowed content type is rejected before the enabled-gate")
    void mintUploadUrlRejectsBadContentType() throws Exception {
        String productId = createProduct(asAdmin(mgRoad, "admin"));

        mintUploadUrl(productId, "application/pdf", asAdmin(mgRoad, "admin"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.contentType").exists());
    }

    @Test
    @DisplayName("confirm: ADMIN on their own product gets the clean not-enabled 400")
    void confirmNotEnabled() throws Exception {
        String productId = createProduct(asAdmin(mgRoad, "admin"));

        confirmImage(productId, asAdmin(mgRoad, "admin"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Product images are not enabled on this deployment"));
    }

    @Test
    @DisplayName("confirm: CASHIER is rejected by the security chain")
    void confirmCashierForbidden() throws Exception {
        String productId = createProduct(asAdmin(mgRoad, "admin"));

        confirmImage(productId, asAdmin(mgRoad, "cashier")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("confirm: a product belonging to another tenant answers 404")
    void confirmCrossTenant() throws Exception {
        String productId = createProduct(asAdmin(mgRoad, "admin"));

        confirmImage(productId, asAdmin(airport, "admin")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("delete: ADMIN on their own product gets the clean not-enabled 400")
    void deleteImageNotEnabled() throws Exception {
        String productId = createProduct(asAdmin(mgRoad, "admin"));

        deleteImage(productId, asAdmin(mgRoad, "admin"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Product images are not enabled on this deployment"));
    }

    @Test
    @DisplayName("delete: CASHIER is rejected by the security chain")
    void deleteImageCashierForbidden() throws Exception {
        String productId = createProduct(asAdmin(mgRoad, "admin"));

        deleteImage(productId, asAdmin(mgRoad, "cashier")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("delete: a product belonging to another tenant answers 404")
    void deleteImageCrossTenant() throws Exception {
        String productId = createProduct(asAdmin(mgRoad, "admin"));

        deleteImage(productId, asAdmin(airport, "admin")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a fresh product's GET carries a null imageUrl, and creating one never sets it")
    void freshProductHasNoImageUrl() throws Exception {
        String productId = createProduct(asAdmin(mgRoad, "admin"));

        mvc.perform(get("/api/products/" + productId)
                        .header(AUTH, bearer(asAdmin(mgRoad, "admin"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imageUrl").doesNotExist());
    }

    // --- helpers -----------------------------------------------------------------

    /**
     * {@code em.clear()} after the request, not before — the same device
     * {@code ProductVariantActiveSyncIT.deleteProduct}'s Javadoc explains, for a
     * related but distinct reason here: every {@code mvc.perform} call in this test
     * class shares this test method's one transaction/session, so the entity this
     * just created stays in the first-level cache afterward — and {@code em.find()}
     * (by-id) checks that cache *before* running any SQL, which means a later cross-
     * tenant lookup for the same id would return the cached instance directly,
     * bypassing the tenant filter entirely (a filter only scopes SQL that actually
     * runs). A real HTTP request never hits this: each one gets its own fresh
     * persistence context in production, confirmed live against a real running app
     * first (the peer-review commit message) — this is a test-boundary artifact, not
     * a production bug.
     */
    private String createProduct(String token) throws Exception {
        String response = mvc.perform(post("/api/products")
                        .header(AUTH, bearer(token))
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"name":"Image Test Product","taxRatePercent":5}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        em.clear();
        return JsonPath.read(response, "$.id");
    }

    private ResultActions mintUploadUrl(String productId, String contentType, String token) throws Exception {
        return mvc.perform(post("/api/products/" + productId + "/image-upload-url")
                .header(AUTH, bearer(token))
                .contentType(APPLICATION_JSON)
                .content("""
                        {"contentType":"%s"}
                        """.formatted(contentType)));
    }

    private ResultActions confirmImage(String productId, String token) throws Exception {
        return mvc.perform(put("/api/products/" + productId + "/image").header(AUTH, bearer(token)));
    }

    private ResultActions deleteImage(String productId, String token) throws Exception {
        return mvc.perform(delete("/api/products/" + productId + "/image").header(AUTH, bearer(token)));
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private String asAdmin(TenantPojo tenant, String username) throws Exception {
        String body = """
                {"tenantCode":"%s","username":"%s","password":"admin123"}
                """.formatted(tenant.getCode(), username);
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

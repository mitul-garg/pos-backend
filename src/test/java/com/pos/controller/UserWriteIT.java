package com.pos.controller;

import java.util.List;

import com.jayway.jsonpath.JsonPath;
import com.pos.config.MailConfig;
import com.pos.config.OpenApiConfig;
import com.pos.config.PersistenceConfig;
import com.pos.config.RecaptchaConfig;
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

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /api/users} (C8) — the port of what a real {@code userService.js} test file
 * would have covered had one existed; requirements.md section 5.7 scoped the frontend's
 * management screen to list/create/deactivate only, with no dedicated suite of its own.
 *
 * <p><b>Cross-tenant attempts are not here.</b> They belong in
 * {@code TenantIsolationIT.UsersAreScopedToo}, with every other attempt to reach across
 * the boundary, so that one suite stays the complete answer to "what stops a t1 admin
 * touching t2?". This suite is about the endpoints behaving correctly <i>inside</i> one
 * store — same split {@code ProductWriteIT} draws for the catalogue.
 *
 * <p>Three properties are worth naming, since they are exactly what a mock could not pin
 * and what a reviewer would otherwise take on trust:
 *
 * <ul>
 *   <li><b>The tenant is stamped, not accepted</b> — a body naming another tenant produces
 *       a row in the caller's own, the same assertion {@code ProductWriteIT} makes.</li>
 *   <li><b>{@code role} is checked against {@code TENANT_ROLES}</b>, so a tenant
 *       {@code ADMIN} can never mint a {@code SUPER_ADMIN} — the escalation
 *       BUGS.md's Phase 8/B4 entry describes on the frontend side.</li>
 *   <li><b>The last-active-admin guard is per tenant</b>, and never trips for a
 *       {@code CASHIER} no matter how few are left.</li>
 * </ul>
 */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = {
        RootConfig.class, PersistenceConfig.class, SecurityConfig.class, MailConfig.class,
        RecaptchaConfig.class,
        WebConfig.class, OpenApiConfig.class })
@TestPropertySource("classpath:application-test.properties")
@Transactional
@DisplayName("GET/POST/PUT/DELETE /api/users")
class UserWriteIT {

    private static final BCryptPasswordEncoder HASHER = new BCryptPasswordEncoder();
    private static final String ADMIN_HASH = HASHER.encode("admin123");
    private static final String CASHIER_HASH = HASHER.encode("cashier123");
    private static final String SUPER_HASH = HASHER.encode("super123");

    /** No user will ever carry it. */
    private static final long UNISSUED_ID = 9_999_999L;

    private static final String NEW_CASHIER = """
            {"username":"newcashier","password":"pass123","displayName":"New Cashier","role":"CASHIER"}
            """;

    @Autowired
    private WebApplicationContext context;

    @PersistenceContext
    private EntityManager em;

    private MockMvc mvc;

    private Tenant mgRoad;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        Tenant platform = tenant("Platform", Tenant.PLATFORM_CODE, true);
        mgRoad = tenant("MG Road Store", "mg-road", false);

        user(platform, "superadmin", SUPER_HASH, Role.SUPER_ADMIN);
        user(mgRoad, "admin", ADMIN_HASH, Role.ADMIN);
        user(mgRoad, "cashier", CASHIER_HASH, Role.CASHIER);

        em.flush();
        em.clear();
    }

    @AfterEach
    void clearThreadState() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Nested
    @DisplayName("POST /api/users")
    class Create {

        @Test
        @DisplayName("answers 201 with an active user in the caller's own tenant, no password on the wire")
        void createsAnActiveUser() throws Exception {
            create(asAdmin(), NEW_CASHIER)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id", notNullValue()))
                    .andExpect(jsonPath("$.tenantId").value(id(mgRoad)))
                    .andExpect(jsonPath("$.username").value("newcashier"))
                    .andExpect(jsonPath("$.displayName").value("New Cashier"))
                    .andExpect(jsonPath("$.role").value("CASHIER"))
                    .andExpect(jsonPath("$.isActive").value(true))
                    .andExpect(jsonPath("$.createdAt", notNullValue()))
                    .andExpect(jsonPath("$.password").doesNotExist())
                    .andExpect(jsonPath("$.passwordHash").doesNotExist());
        }

        /**
         * {@code UserForm} declares no {@code tenantId} or {@code id} setter, so Jackson
         * drops both — the same protection {@code ProductWriteIT} pins for
         * {@code ProductForm}, asserting the outcome rather than the mechanism so it
         * survives someone adding a setter "for convenience".
         */
        @Test
        @DisplayName("ignores tenantId and id in the body")
        void ignoresIdentityFieldsInTheBody() throws Exception {
            create(asAdmin(), """
                    {"username":"probe","password":"x","role":"CASHIER",
                     "tenantId":"99","id":"12345"}
                    """)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.tenantId").value(id(mgRoad)))
                    .andExpect(jsonPath("$.id", not("12345")));
        }

        @Test
        @DisplayName("defaults displayName to the username when none is given")
        void defaultsDisplayNameToUsername() throws Exception {
            create(asAdmin(), """
                    {"username":"probe","password":"x","role":"CASHIER"}
                    """)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.displayName").value("probe"));
        }

        @Test
        @DisplayName("rejects a blank username")
        void rejectsABlankUsername() throws Exception {
            create(asAdmin(), """
                    {"password":"x","role":"CASHIER"}
                    """)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Username is required"))
                    .andExpect(jsonPath("$.fields.username").value("Username is required"));
        }

        @Test
        @DisplayName("rejects a blank password")
        void rejectsABlankPassword() throws Exception {
            create(asAdmin(), """
                    {"username":"probe","role":"CASHIER"}
                    """)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fields.password").value("Password is required"));
        }

        /**
         * The exact hole BUGS.md logs opening on the frontend when {@code SUPER_ADMIN}
         * joined the shared role list (Phase 8/B4) — a tenant {@code ADMIN} minting one
         * would be privilege escalation out of its own tenant.
         */
        @Test
        @DisplayName("refuses to mint a SUPER_ADMIN")
        void refusesToMintASuperAdmin() throws Exception {
            create(asAdmin(), """
                    {"username":"probe","password":"x","role":"SUPER_ADMIN"}
                    """)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fields.role").value("Choose a valid role"));
        }

        @Test
        @DisplayName("rejects a username already taken in this tenant")
        void rejectsADuplicateUsername() throws Exception {
            create(asAdmin(), """
                    {"username":"admin","password":"x","role":"CASHIER"}
                    """)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fields.username").value("Username is already taken"));
        }

        @Test
        @DisplayName("username matching is case-insensitive, mirroring login")
        void usernameUniquenessIsCaseInsensitive() throws Exception {
            create(asAdmin(), """
                    {"username":"ADMIN","password":"x","role":"CASHIER"}
                    """)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fields.username").value("Username is already taken"));
        }

        @Test
        @DisplayName("the new user is immediately in the list")
        void isVisibleToTheVeryNextRequest() throws Exception {
            create(asAdmin(), NEW_CASHIER).andExpect(status().isCreated());

            list(asAdmin())
                    .andExpect(jsonPath("$", hasSize(3)));
        }

        /**
         * Peer-review Phase 0 resource-creation guardrail — {@code pos.tenant.maxUsers}
         * (20 in {@code application.properties}, not overridden for tests). {@code mgRoad}
         * starts with 2 seeded users ({@code setUp}); 18 more by direct persistence reaches
         * the ceiling without 18 round trips, then the 21st goes through the real endpoint.
         */
        @Test
        @DisplayName("rejects a create once the tenant is at its user-count ceiling")
        void rejectsACreateAtTheUserCeiling() throws Exception {
            for (int i = 0; i < 18; i++) {
                user(mgRoad, "filler" + i, CASHIER_HASH, Role.CASHIER);
            }
            em.flush();
            em.clear();

            create(asAdmin(), """
                    {"username":"onemore","password":"pass123","role":"CASHIER"}
                    """)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("This store has reached its user limit"))
                    .andExpect(jsonPath("$.fields").doesNotExist());
        }
    }

    @Nested
    @DisplayName("PUT /api/users/{id}")
    class Update {

        @Test
        @DisplayName("patches only what it is given")
        void patchesOnlyWhatItIsGiven() throws Exception {
            String userId = createNewCashier();

            update(asAdmin(), userId, """
                    {"displayName":"Renamed"}
                    """)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.displayName").value("Renamed"))
                    // Everything absent from the patch survives it.
                    .andExpect(jsonPath("$.username").value("newcashier"))
                    .andExpect(jsonPath("$.role").value("CASHIER"))
                    .andExpect(jsonPath("$.isActive").value(true));
        }

        /**
         * The mock's {@code update} never reads a {@code username} field off the patch at
         * all — an account's username is permanent once created.
         */
        @Test
        @DisplayName("never changes the username, even if the body includes one")
        void neverChangesTheUsername() throws Exception {
            String userId = createNewCashier();

            update(asAdmin(), userId, """
                    {"username":"renamed-handle"}
                    """)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.username").value("newcashier"));
        }

        @Test
        @DisplayName("promotes CASHIER to ADMIN")
        void promotesToAdmin() throws Exception {
            String userId = createNewCashier();

            update(asAdmin(), userId, """
                    {"role":"ADMIN"}
                    """)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.role").value("ADMIN"));
        }

        @Test
        @DisplayName("refuses to promote to SUPER_ADMIN")
        void refusesToPromoteToSuperAdmin() throws Exception {
            String userId = createNewCashier();

            update(asAdmin(), userId, """
                    {"role":"SUPER_ADMIN"}
                    """)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fields.role").value("Choose a valid role"));
        }

        @Test
        @DisplayName("reactivates with isActive alone")
        void reactivatesWithIsActiveAlone() throws Exception {
            String userId = createNewCashier();
            deleteUser(asAdmin(), userId).andExpect(jsonPath("$.isActive").value(false));

            update(asAdmin(), userId, """
                    {"isActive":true}
                    """)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.isActive").value(true))
                    .andExpect(jsonPath("$.displayName").value("New Cashier"));
        }

        @Test
        @DisplayName("an empty password in the patch is a no-op, not a reset to nothing")
        void blankPasswordIsLeftAlone() throws Exception {
            String userId = createNewCashier();

            update(asAdmin(), userId, """
                    {"password":""}
                    """).andExpect(status().isOk());

            // The original password still logs in.
            tokenFor("mg-road", "newcashier", "pass123");
        }

        @Test
        @DisplayName("an id that never existed is 404")
        void unknownIdIs404() throws Exception {
            update(asAdmin(), String.valueOf(UNISSUED_ID), """
                    {"displayName":"x"}
                    """)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("User not found"));
        }
    }

    @Nested
    @DisplayName("DELETE /api/users/{id}")
    class Deactivate {

        @Test
        @DisplayName("deactivates and returns the updated row, still listed")
        void deactivatesAndStillLists() throws Exception {
            String userId = createNewCashier();

            deleteUser(asAdmin(), userId)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.isActive").value(false));

            // Unlike products, a deactivated user is NOT hidden from the default list --
            // the admin screen needs it visible to offer Reactivate.
            list(asAdmin()).andExpect(jsonPath("$", hasSize(3)));
        }

        @Test
        @DisplayName("is idempotent")
        void isIdempotent() throws Exception {
            String userId = createNewCashier();

            deleteUser(asAdmin(), userId).andExpect(status().isOk());
            deleteUser(asAdmin(), userId)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.isActive").value(false));
        }

        @Test
        @DisplayName("an id that never existed is 404")
        void unknownIdIs404() throws Exception {
            deleteUser(asAdmin(), String.valueOf(UNISSUED_ID))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("User not found"));
        }

        @Test
        @DisplayName("deactivating a CASHIER never trips the last-admin guard, however few cashiers remain")
        void cashierDeactivationNeverTripsTheGuard() throws Exception {
            String userId = createNewCashier();

            deleteUser(asAdmin(), userId).andExpect(status().isOk());
            // The tenant's ORIGINAL cashier too -- zero cashiers left, still not an admin
            // question.
            Long originalCashierId = userId(asAdmin(), "cashier");
            deleteUser(asAdmin(), String.valueOf(originalCashierId)).andExpect(status().isOk());
        }

        @Test
        @DisplayName("promoting a cashier to admin gives the tenant a second admin, so the first can now go")
        void twoAdminsMeansEitherCanBeDeactivated() throws Exception {
            String userId = createNewCashier();
            update(asAdmin(), userId, """
                    {"role":"ADMIN"}
                    """).andExpect(status().isOk());

            Long originalAdminId = userId(asAdmin(), "admin");
            deleteUser(asAdmin(), String.valueOf(originalAdminId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.isActive").value(false));
        }

        @Test
        @DisplayName("refuses to deactivate the last active admin")
        void refusesToDeactivateTheLastActiveAdmin() throws Exception {
            Long originalAdminId = userId(asAdmin(), "admin");

            deleteUser(asAdmin(), String.valueOf(originalAdminId))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Cannot deactivate the last active admin"));

            // And nothing changed.
            list(asAdmin())
                    .andExpect(jsonPath("$[?(@.username=='admin')].isActive").value(
                            hasItem(true)));
        }

        @Test
        @DisplayName("an already-deactivated admin doesn't count toward the guard for the real last one")
        void anAlreadyInactiveAdminDoesNotProtectTheLastOne() throws Exception {
            String userId = createNewCashier();
            update(asAdmin(), userId, """
                    {"role":"ADMIN"}
                    """).andExpect(status().isOk());

            // Deactivate the promoted admin first -- one active admin (the original) left.
            deleteUser(asAdmin(), userId).andExpect(status().isOk());

            Long originalAdminId = userId(asAdmin(), "admin");
            deleteUser(asAdmin(), String.valueOf(originalAdminId))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Cannot deactivate the last active admin"));
        }
    }

    /**
     * Requirements.md section 5.7: user management is an {@code ADMIN}'s, and unlike the
     * catalogue this gates the <b>whole screen</b>, {@code GET} included.
     */
    @Nested
    @DisplayName("the role rule")
    class RoleRules {

        @Test
        @DisplayName("a CASHIER cannot even list, let alone write")
        void aCashierCannotReachAnyOfIt() throws Exception {
            String userId = createNewCashier();
            String cashier = asCashier();

            list(cashier).andExpect(status().isForbidden());
            create(cashier, """
                    {"username":"probe","password":"x","role":"CASHIER"}
                    """).andExpect(status().isForbidden());
            update(cashier, userId, """
                    {"displayName":"x"}
                    """).andExpect(status().isForbidden());
            deleteUser(cashier, userId).andExpect(status().isForbidden());

            // And the refusal changed nothing.
            list(asAdmin()).andExpect(jsonPath("$", hasSize(3)));
        }

        @Test
        @DisplayName("a SUPER_ADMIN is refused too -- it has no tenant of its own to manage users in")
        void aPlatformAdminIsRefusedToo() throws Exception {
            list(asPlatformAdmin())
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message", not(TenantContext.NO_TENANT_MESSAGE)));
        }
    }

    // --- helpers -----------------------------------------------------------------

    private static final String AUTH = "Authorization";

    private ResultActions create(String token, String body) throws Exception {
        return mvc.perform(post("/api/users")
                .header(AUTH, bearer(token))
                .contentType(APPLICATION_JSON)
                .content(body));
    }

    private ResultActions update(String token, String userId, String body) throws Exception {
        return mvc.perform(put("/api/users/" + userId)
                .header(AUTH, bearer(token))
                .contentType(APPLICATION_JSON)
                .content(body));
    }

    private ResultActions deleteUser(String token, String userId) throws Exception {
        return mvc.perform(delete("/api/users/" + userId).header(AUTH, bearer(token)));
    }

    private ResultActions list(String token) throws Exception {
        return mvc.perform(get("/api/users").header(AUTH, bearer(token)));
    }

    /** Through the endpoint rather than {@code em.persist}, so the fixture is also a
     * create that is known to have worked. */
    private String createNewCashier() throws Exception {
        String response = create(asAdmin(), NEW_CASHIER)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    /** Reads a seeded user's id back off the list, so a fixture never has to guess one. */
    private Long userId(String token, String username) throws Exception {
        String response = list(token).andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(response, "$[?(@.username == '" + username + "')].id");
        return Long.valueOf(ids.get(0));
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private String id(Tenant tenant) {
        return String.valueOf(tenant.getId());
    }

    private String asAdmin() throws Exception {
        return tokenFor("mg-road", "admin", "admin123");
    }

    private String asCashier() throws Exception {
        return tokenFor("mg-road", "cashier", "cashier123");
    }

    private String asPlatformAdmin() throws Exception {
        return tokenFor(Tenant.PLATFORM_CODE, "superadmin", "super123");
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
}

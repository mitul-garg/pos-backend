package com.pos.controller;

import java.util.stream.Stream;

import com.jayway.jsonpath.JsonPath;
import com.pos.config.MailConfig;
import com.pos.config.OpenApiConfig;
import com.pos.config.PersistenceConfig;
import com.pos.config.RecaptchaConfig;
import com.pos.config.RootConfig;
import com.pos.config.SecurityConfig;
import com.pos.config.WebConfig;
import com.pos.exception.InvalidCredentialsException;
import com.pos.pojo.AppUserPojo;
import com.pos.pojo.enums.Role;
import com.pos.pojo.TenantPojo;
import com.pos.pojo.enums.TenantStatus;
import com.pos.util.TestIps;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The headline suite for C3 — a direct port of {@code frontend/src/services/authService.test.js},
 * whose 17 cases already state what correct means, plus the cases the mock could not
 * reach.
 *
 * <p><b>What is under test is mostly what a caller cannot learn.</b> Every
 * authentication failure has to produce one identical response, so login cannot be used
 * to discover which tenants or which usernames exist. The specific 403s are safe only
 * because they are unreachable until the password has already been proved — which makes
 * the <i>ordering</i> inside {@code AuthService} the thing these tests actually pin.
 *
 * <p>Runs the real chain: {@code springSecurity()} puts the actual filter chain in front
 * of MockMvc, so a test that passes here passes through the same code Jetty runs. Booting
 * the servlet context alone would test the controllers and none of the security.
 *
 * <p>Fixtures mirror the frontend's seed data, deliberate collisions included: both
 * stores have an {@code admin} and a {@code cashier} with the same passwords. That
 * collision is the entire reason login takes a tenant code.
 *
 * <p><b>Every {@code login} call carries its own fake source IP</b> ({@link #login}, via
 * {@code TestIps.fresh()}), shared plumbing with every other IT that needs the same
 * device — see {@code TestIps}'s Javadoc for why: {@code LoginRateLimiter}'s
 * 20-per-15-minutes budget is shared by every call that resolves to the same address,
 * and all real MockMvc calls resolve to the same loopback address otherwise. Without a
 * fresh IP per call, the rate-limiting tests below would exhaust the budget for every
 * other test in this class — and, if this class shares a Spring context with another IT,
 * for every other IT's login calls too.
 */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
// The whole application, in one context rather than the root/servlet pair the container
// builds. OpenApiConfig is here only because WebConfig scans com.pos.controller and
// OpenApiController needs the generator -- a reminder that a component scan makes every
// controller everyone's dependency.
@ContextConfiguration(classes = {
        RootConfig.class, PersistenceConfig.class, SecurityConfig.class, MailConfig.class,
        RecaptchaConfig.class,
        WebConfig.class, OpenApiConfig.class })
@TestPropertySource("classpath:application-test.properties")
@Transactional
@DisplayName("POST /api/auth/login, GET /api/auth/me")
class AuthControllerIT {

    /** Verbatim from the frontend's {@code INVALID_CREDENTIALS}. */
    private static final String GENERIC = InvalidCredentialsException.MESSAGE;

    /**
     * Hashed once for the whole class rather than per fixture. BCrypt costs ~100ms by
     * design, and re-hashing five users before every test would spend most of the run
     * proving the same thing. The verification on the login path is still the real
     * encoder against a real hash, which is the half that matters.
     */
    private static final BCryptPasswordEncoder HASHER = new BCryptPasswordEncoder();
    private static final String ADMIN_HASH = HASHER.encode("admin123");
    private static final String CASHIER_HASH = HASHER.encode("cashier123");
    private static final String SUPER_HASH = HASHER.encode("super123");

    @Autowired
    private WebApplicationContext context;

    @PersistenceContext
    private EntityManager em;

    private MockMvc mvc;

    private TenantPojo mgRoad;
    private TenantPojo airport;
    private AppUserPojo mgRoadCashier;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        TenantPojo platform = tenant("Platform", "platform", true);
        mgRoad = tenant("MG Road Store", "mg-road", false);
        airport = tenant("Airport Store", "airport", false);

        user(platform, "superadmin", SUPER_HASH, "Platform Admin", Role.SUPER_ADMIN);
        user(mgRoad, "admin", ADMIN_HASH, "MG Road Admin", Role.ADMIN);
        mgRoadCashier = user(mgRoad, "cashier", CASHIER_HASH, "MG Road Cashier", Role.CASHIER);
        user(airport, "admin", ADMIN_HASH, "Airport Admin", Role.ADMIN);
        user(airport, "cashier", CASHIER_HASH, "Airport Cashier", Role.CASHIER);
    }

    @AfterEach
    void clearSecurityContext() {
        // SecurityContextHolder is a ThreadLocal and MockMvc reuses the test thread, so a
        // context left behind would authenticate the NEXT test. Harmless here and the
        // exact shape of the bug C4 has to prevent for real, on Jetty's pooled threads.
        SecurityContextHolder.clearContext();
    }

    @Nested
    @DisplayName("tenant-code resolution")
    class TenantCodeResolution {

        @Test
        @DisplayName("resolves the same username to a different user per tenant")
        void resolvesPerTenant() throws Exception {
            String mgRoadBody = login("mg-road", "admin", "admin123")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.user.tenantName").value("MG Road Store"))
                    .andExpect(jsonPath("$.user.role").value("ADMIN"))
                    .andReturn().getResponse().getContentAsString();

            String airportBody = login("airport", "admin", "admin123")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.user.tenantName").value("Airport Store"))
                    .andReturn().getResponse().getContentAsString();

            assertNotEquals(JsonPath.read(mgRoadBody, "$.user.id").toString(),
                    JsonPath.read(airportBody, "$.user.id").toString(),
                    "the same username in two tenants must be two different users");
        }

        @Test
        @DisplayName("signs in the platform admin with the reserved code, with no tenant")
        void signsInThePlatformAdmin() throws Exception {
            // The reserved row exists in the database, but it is storage rather than a
            // tenant on the wire -- requirements.md section 3 says tenantId is null for a
            // SUPER_ADMIN, and the frontend branches on exactly that.
            //
            // Asserted as PRESENT AND NULL, not merely absent. Jackson's null inclusion is
            // left at ALWAYS on purpose (c1-skeleton.md) because omitting the key would
            // read as "not provided" rather than "belongs to no tenant" -- and
            // doesNotExist() passes for a null value too, so it would not have noticed the
            // day someone switched the mapper to NON_NULL.
            login("platform", "superadmin", "super123")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.user.role").value("SUPER_ADMIN"))
                    .andExpect(jsonPath("$.user", hasKey("tenantId")))
                    .andExpect(jsonPath("$.user", hasKey("tenantCode")))
                    .andExpect(jsonPath("$.user", hasKey("tenantName")))
                    .andExpect(jsonPath("$.user.tenantId").value(nullValue()))
                    .andExpect(jsonPath("$.user.tenantCode").value(nullValue()))
                    .andExpect(jsonPath("$.user.tenantName").value(nullValue()));
        }

        @Test
        @DisplayName("serializes ids as strings, so the frontend's === keeps working")
        void serializesIdsAsStrings() throws Exception {
            login("mg-road", "admin", "admin123")
                    .andExpect(jsonPath("$.user.id").isString())
                    .andExpect(jsonPath("$.user.tenantId").isString());
        }

        @Test
        @DisplayName("treats the code and username as case-insensitive")
        void isCaseInsensitive() throws Exception {
            // A cashier typing `Admin` at 8am is not a different account. The frontend
            // lower-cases both before sending; the backend must not depend on it having
            // done so.
            login("MG-Road", "ADMIN", "admin123")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.user.tenantCode").value("mg-road"));
        }
    }

    @Nested
    @DisplayName("401 — one message, so nothing can be enumerated")
    class GenericUnauthorized {

        /** The frontend's seven cases, unchanged. */
        static Stream<Arguments> credentialFailures() {
            return Stream.of(
                    Arguments.of("unknown tenant code", "nope", "admin", "admin123"),
                    Arguments.of("blank tenant code", "", "admin", "admin123"),
                    Arguments.of("username from another tenant", "mg-road", "superadmin", "super123"),
                    Arguments.of("wrong password", "mg-road", "admin", "wrong"),
                    Arguments.of("unknown username", "mg-road", "ghost", "admin123"),
                    Arguments.of("platform code with a tenant user", "platform", "admin", "admin123"),
                    Arguments.of("tenant code used as a platform login", "mg-road", "superadmin", "super123"));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("credentialFailures")
        @DisplayName("is indistinguishable for")
        void isIndistinguishable(String label, String code, String username, String password)
                throws Exception {
            login(code, username, password)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value(GENERIC))
                    // No field map: naming the offending field would say which input was
                    // wrong, which is the whole thing this message refuses to say.
                    .andExpect(jsonPath("$.fields").doesNotExist())
                    .andExpect(jsonPath("$.token").doesNotExist());
        }

        @Test
        @DisplayName("a blank tenant code is a failed login, not a validation error")
        void blankIsNotAValidationError() throws Exception {
            // The reason LoginForm carries no @NotBlank. A 400 here would split the list
            // above in two and hand a caller an oracle: 400 means "you got the shape
            // wrong", 401 means "the shape was right and the credentials were not".
            login("", "", "")
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value(GENERIC));
        }
    }

    @Nested
    @DisplayName("403 — specific, but only after the password is proved")
    class ForbiddenAfterPassword {

        @Test
        @DisplayName("names deactivation for a valid-but-inactive account")
        void namesDeactivation() throws Exception {
            mgRoadCashier.setActive(false);

            login("mg-road", "cashier", "cashier123")
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message", containsString("deactivated")));
        }

        @Test
        @DisplayName("names suspension for a valid login into a suspended tenant")
        void namesSuspension() throws Exception {
            airport.setStatus(TenantStatus.SUSPENDED);

            login("airport", "admin", "admin123")
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message", containsString("suspended")))
                    .andExpect(jsonPath("$.token").doesNotExist());
        }

        @Test
        @DisplayName("still returns the generic 401 for a WRONG password in a suspended tenant")
        void wrongPasswordInASuspendedTenantStaysGeneric() throws Exception {
            // The subtle one, and the reason the status checks run AFTER the password
            // check. Answering "suspended" here would confirm that the tenant exists and
            // that the username is in it -- to someone who just proved they do not know
            // the password.
            airport.setStatus(TenantStatus.SUSPENDED);

            login("airport", "admin", "wrong")
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value(GENERIC));
        }

        @Test
        @DisplayName("leaves the other tenant unaffected when one is suspended")
        void suspensionIsPerTenant() throws Exception {
            airport.setStatus(TenantStatus.SUSPENDED);

            login("mg-road", "admin", "admin123")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.user.tenantCode").value("mg-road"));
        }
    }

    @Nested
    @DisplayName("GET /api/auth/me")
    class CurrentSession {

        @Test
        @DisplayName("restores identity and tenant from the token")
        void restoresTheSession() throws Exception {
            String token = tokenFor("airport", "cashier", "cashier123");

            me(token)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.username").value("cashier"))
                    .andExpect(jsonPath("$.tenantName").value("Airport Store"))
                    .andExpect(jsonPath("$.role").value("CASHIER"))
                    .andExpect(jsonPath("$.isActive").value(true));
        }

        @Test
        @DisplayName("rejects a garbage token")
        void rejectsGarbage() throws Exception {
            me("not-a-token")
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value(GENERIC));
        }

        @Test
        @DisplayName("rejects a request with no token at all")
        void rejectsNoToken() throws Exception {
            mvc.perform(get("/api/auth/me"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value(GENERIC));
        }
    }

    /**
     * The upgrade over the mock. {@code authService.js} re-checked status only inside
     * {@code me()}, so a suspended store's open tab kept transacting until someone
     * refreshed the page — backend-plan.md section 1 lists closing that as deferred
     * obligation 5.
     */
    @Nested
    @DisplayName("mid-session enforcement (the mock could not do this)")
    class MidSession {

        @Test
        @DisplayName("a tenant suspended after the token was issued is locked out at the next request")
        void suspensionTakesEffectImmediately() throws Exception {
            String token = tokenFor("airport", "cashier", "cashier123");
            me(token).andExpect(status().isOk());

            airport.setStatus(TenantStatus.SUSPENDED);

            me(token)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message", containsString("suspended")));
        }

        @Test
        @DisplayName("a user deactivated after the token was issued is locked out at the next request")
        void deactivationTakesEffectImmediately() throws Exception {
            String token = tokenFor("mg-road", "cashier", "cashier123");
            me(token).andExpect(status().isOk());

            mgRoadCashier.setActive(false);

            me(token)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message", containsString("deactivated")));
        }
    }

    @Nested
    @DisplayName("the chain")
    class Chain {

        @Test
        @DisplayName("leaves the health probe open, so a bad token cannot look like an outage")
        void healthStaysOpen() throws Exception {
            mvc.perform(get("/api/health")).andExpect(status().isOk());
        }

        @Test
        @DisplayName("denies an unmapped path by default rather than 404ing it")
        void defaultsToDeny() throws Exception {
            // Default-deny means a new endpoint is protected the moment it exists. It also
            // means an anonymous caller cannot map which endpoints are real.
            mvc.perform(get("/api/products"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value(GENERIC));
        }

        @Test
        @DisplayName("requires a token to log out")
        void logoutRequiresAToken() throws Exception {
            mvc.perform(post("/api/auth/logout")).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("acknowledges a logout with 204 and no body")
        void logoutSucceedsWithAToken() throws Exception {
            String token = tokenFor("mg-road", "admin", "admin123");

            mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + token))
                    .andExpect(status().isNoContent())
                    .andExpect(content().string(""));
        }

        @Test
        @DisplayName("answers a filter-level failure in the same envelope as a controller-level one")
        void oneErrorEnvelope() throws Exception {
            // A @ControllerAdvice cannot see a failure inside a servlet filter, so the
            // envelope is produced by two writers. The frontend has exactly one error
            // path, so the two must be indistinguishable in shape.
            String fromTheFilter = mvc.perform(get("/api/auth/me").header("Authorization", "Bearer junk"))
                    .andExpect(status().isUnauthorized())
                    .andReturn().getResponse().getContentAsString();

            String fromTheAdvice = login("nope", "nobody", "nothing")
                    .andExpect(status().isUnauthorized())
                    .andReturn().getResponse().getContentAsString();

            org.junit.jupiter.api.Assertions.assertEquals(fromTheAdvice, fromTheFilter,
                    "filter and advice must produce the same error body");
        }
    }

    @Nested
    @DisplayName("credentials")
    class Credentials {

        @Test
        @DisplayName("never puts a password or its hash on the wire")
        void neverLeaksTheHash() throws Exception {
            // The concrete reason CONVENTIONS.md forbids returning entities: AppUserPojo
            // carries passwordHash, and every incident of this kind starts with
            // serializing one.
            String loginBody = login("mg-road", "admin", "admin123")
                    .andReturn().getResponse().getContentAsString();
            String meBody = me(JsonPath.read(loginBody, "$.token"))
                    .andReturn().getResponse().getContentAsString();

            for (String body : new String[] { loginBody, meBody }) {
                assertFalse(body.contains("$2a$"), () -> "a BCrypt hash reached the wire: " + body);
                assertFalse(body.contains("admin123"), () -> "a plaintext password reached the wire: " + body);
                assertFalse(body.toLowerCase().contains("password"),
                        () -> "the response mentions a password at all: " + body);
            }
        }

        @Test
        @DisplayName("stores a hash, not the password")
        void storesAHash() {
            AppUserPojo stored = em.createQuery(
                            "SELECT u FROM AppUserPojo u JOIN TenantPojo t ON t.id = u.tenantId "
                                    + "WHERE u.username = 'cashier' AND t.code = 'mg-road'",
                            AppUserPojo.class)
                    .getSingleResult();

            assertFalse(stored.getPasswordHash().contains("cashier123"),
                    "the password was stored in plaintext");
        }
    }

    @Nested
    @DisplayName("LoginRateLimiter")
    class LoginRateLimiting {

        /** {@code LoginRateLimiter.MAX_REQUESTS} — package-private to {@code com.pos.util},
         *  so mirrored here as a literal the same way {@code TenantRegistrationIT} mirrors
         *  {@code RegistrationRateLimiter.MAX_REQUESTS}. */
        private static final int MAX_REQUESTS = 20;

        @Test
        @DisplayName("trips 429 on the 21st call from the same IP within the window")
        void tripsAfterTwentyFromTheSameIp() throws Exception {
            String ip = "203.0.113.7";
            for (int i = 0; i < MAX_REQUESTS; i++) {
                login("mg-road", "admin", "wrong", ip).andExpect(status().isUnauthorized());
            }
            login("mg-road", "admin", "wrong", ip)
                    .andExpect(status().is(429))
                    .andExpect(jsonPath("$.message").value("Too many requests. Try again later."));
        }

        @Test
        @DisplayName("a successful login still counts against the IP budget")
        void successCountsToo() throws Exception {
            String ip = "203.0.113.8";
            login("mg-road", "admin", "admin123", ip).andExpect(status().isOk());
            for (int i = 0; i < MAX_REQUESTS - 1; i++) {
                login("mg-road", "admin", "wrong", ip).andExpect(status().isUnauthorized());
            }
            login("mg-road", "admin", "wrong", ip).andExpect(status().is(429));
        }

        @Test
        @DisplayName("a different IP has its own budget, even once the first is exhausted")
        void independentIps() throws Exception {
            String exhaustedIp = "203.0.113.9";
            for (int i = 0; i < MAX_REQUESTS; i++) {
                login("mg-road", "admin", "wrong", exhaustedIp).andExpect(status().isUnauthorized());
            }
            login("mg-road", "admin", "wrong", exhaustedIp).andExpect(status().is(429));

            login("mg-road", "admin", "admin123", TestIps.fresh()).andExpect(status().isOk());
        }
    }

    // --- helpers -----------------------------------------------------------------

    private ResultActions login(String tenantCode, String username, String password)
            throws Exception {
        return login(tenantCode, username, password, TestIps.fresh());
    }

    private ResultActions login(String tenantCode, String username, String password, String ip)
            throws Exception {
        String body = """
                {"tenantCode":"%s","username":"%s","password":"%s"}
                """.formatted(tenantCode, username, password);
        return mvc.perform(post("/api/auth/login")
                .with(TestIps.remoteAddr(ip))
                .contentType(APPLICATION_JSON)
                .content(body));
    }

    private String tokenFor(String tenantCode, String username, String password) throws Exception {
        String body = login(tenantCode, username, password)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.token");
    }

    private ResultActions me(String token) throws Exception {
        return mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token));
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

    private AppUserPojo user(TenantPojo tenant, String username, String passwordHash,
                         String displayName, Role role) {
        AppUserPojo user = new AppUserPojo();
        user.setTenantId(tenant.getId());
        user.setUsername(username);
        user.setPasswordHash(passwordHash);
        user.setDisplayName(displayName);
        user.setRole(role);
        user.setActive(true);
        em.persist(user);
        return user;
    }
}

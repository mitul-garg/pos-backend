package com.pos.controller;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.jayway.jsonpath.JsonPath;
import com.pos.config.OpenApiConfig;
import com.pos.config.PersistenceConfig;
import com.pos.config.RootConfig;
import com.pos.config.SecurityConfig;
import com.pos.config.WebConfig;
import com.pos.pojo.Tenant;
import com.pos.pojo.TenantStatus;
import com.pos.util.EmailSender;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /api/tenants/register|verify|resend-verification} (C9,
 * {@code tenant-registration-plan.md} §4) — the port of the plan's own test list:
 * happy path end-to-end, duplicate/reserved/malformed code, missing/malformed email,
 * honeypot tripped, rate limit tripped, expired/wrong/already-spent token, resend
 * regenerating and invalidating the old token, login blocked pre-verification, and a
 * sanity check that a self-registered tenant is otherwise ordinary.
 *
 * <p><b>Not {@code MailConfig} in the context.</b> Every other IT that needs
 * {@code RootConfig} pulls in {@code MailConfig} for a real (if log-only)
 * {@code EmailSender}; this suite needs to <i>read back</i> what was sent, so
 * {@link TestMailConfig} supplies a {@link CapturingEmailSender} instead — same
 * {@code EmailSender} bean type, satisfying {@code TenantRegistrationService}'s
 * dependency exactly like {@code MailConfig} would, just observable.
 *
 * <p><b>Every {@code register}/{@code resend-verification} call carries its own fake
 * source IP</b> ({@link #freshIp()}), unless a test is deliberately exercising
 * {@code RegistrationRateLimiter}. Without that, the 5-per-hour budget shared by every
 * call in this class (all real MockMvc calls resolve to the same loopback address
 * otherwise) would make unrelated tests fail from cross-contamination, not from
 * anything they're actually testing.
 */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = {
        RootConfig.class, PersistenceConfig.class, SecurityConfig.class,
        TenantRegistrationIT.TestMailConfig.class, WebConfig.class, OpenApiConfig.class })
@TestPropertySource("classpath:application-test.properties")
@Transactional
@DisplayName("POST /api/tenants/register|verify|resend-verification")
class TenantRegistrationIT {

    private static final String INVALID_OR_EXPIRED = "This verification link is invalid or has expired.";
    private static final String RESEND_ACK = "If that store is awaiting verification, we've re-sent the email.";
    private static final String PENDING_LOGIN_MESSAGE =
            "Verify your email before signing in — check your inbox for the link.";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private CapturingEmailSender emailSender;

    @PersistenceContext
    private EntityManager em;

    /**
     * {@code static} on purpose — JUnit5 creates a fresh {@code TenantRegistrationIT}
     * instance per test method, so an instance field would reset to the same starting
     * IP for every test and reintroduce the exact cross-test rate-limit collision
     * {@link #freshIp()} exists to avoid.
     */
    private static final AtomicInteger ipSeq = new AtomicInteger(0);

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        emailSender.clear();
    }

    @AfterEach
    void clearThreadState() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Nested
    @DisplayName("POST /api/tenants/register")
    class Register {

        @Test
        @DisplayName("register -> email captured -> login blocked -> verify -> login succeeds")
        void happyPathEndToEnd() throws Exception {
            String code = "harbor-cafe";

            register(validRegistration(code))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$", aMapWithSize(2)))
                    .andExpect(jsonPath("$.tenantCode").value(code))
                    .andExpect(jsonPath("$.adminEmail").value(emailFor(code)));

            assertEquals(1, emailSender.sent().size());
            CapturingEmailSender.Sent email = emailSender.last();
            assertEquals(emailFor(code), email.to());
            assertTrue(email.subject().contains(code + " Store"), "subject should name the store");
            assertTrue(email.body().contains("/verify?token="), "body should carry a verify link");
            String token = extractToken(email.body());

            login(code, "admin", "secret123")
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value(PENDING_LOGIN_MESSAGE));

            verify(token)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.tenantCode").value(code));

            login(code, "admin", "secret123")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.user.role").value("ADMIN"))
                    .andExpect(jsonPath("$.user.tenantCode").value(code))
                    .andExpect(jsonPath("$.user.displayName").value(code + " Store Admin"));
        }

        @Test
        @DisplayName("refuses a blank store name")
        void refusesBlankStoreName() throws Exception {
            register(registrationBody("", "blank-name-store", "admin", "a@example.com", "secret123", ""))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fields.storeName").value("Store name is required"));
        }

        @Test
        @DisplayName("refuses a blank tenant code")
        void refusesBlankTenantCode() throws Exception {
            register(registrationBody("Blank Code", "", "admin", "a@example.com", "secret123", ""))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fields.tenantCode").value("Tenant code is required"));
        }

        @Test
        @DisplayName("refuses a malformed code")
        void refusesAMalformedCode() throws Exception {
            register(registrationBody("Bad Code", "Not Valid!", "admin", "a@example.com", "secret123", ""))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fields.tenantCode")
                            .value("Use lowercase letters, numbers and hyphens only"));
        }

        @Test
        @DisplayName("refuses a reserved code, so the platform login can never be shadowed")
        void refusesAReservedCode() throws Exception {
            register(registrationBody("Sneaky", "admin", "admin", "a@example.com", "secret123", ""))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fields.tenantCode").value(containsString("reserved")));
        }

        @Test
        @DisplayName("refuses a duplicate code")
        void refusesADuplicateCode() throws Exception {
            register(validRegistration("dup-store")).andExpect(status().isCreated());

            register(validRegistration("dup-store"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fields.tenantCode").value("That tenant code is already taken"));
        }

        @Test
        @DisplayName("refuses a blank admin username")
        void refusesBlankAdminUsername() throws Exception {
            register(registrationBody("No Username", "no-username-store", "", "a@example.com", "secret123", ""))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fields.adminUsername").value("The admin's username is required"));
        }

        @Test
        @DisplayName("refuses a missing admin email")
        void refusesMissingAdminEmail() throws Exception {
            register(registrationBody("No Email", "no-email-store", "admin", "", "secret123", ""))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fields.adminEmail").value("Email is required"));
        }

        @Test
        @DisplayName("refuses a malformed admin email")
        void refusesMalformedAdminEmail() throws Exception {
            register(registrationBody("Bad Email", "bad-email-store", "admin", "not-an-email", "secret123", ""))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fields.adminEmail").value("Enter a valid email address"));
        }

        @Test
        @DisplayName("refuses a blank admin password")
        void refusesBlankAdminPassword() throws Exception {
            register(registrationBody("No Password", "no-password-store", "admin", "a@example.com", "", ""))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fields.adminPassword").value("The admin's password is required"));
        }

        @Test
        @DisplayName("reports every broken field at once, not just the first")
        void reportsEveryBrokenFieldTogether() throws Exception {
            register("{}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fields.storeName").exists())
                    .andExpect(jsonPath("$.fields.tenantCode").exists())
                    .andExpect(jsonPath("$.fields.adminUsername").exists())
                    .andExpect(jsonPath("$.fields.adminEmail").exists())
                    .andExpect(jsonPath("$.fields.adminPassword").exists());
        }

        @Test
        @DisplayName("a tripped honeypot resolves like success but creates nothing")
        void honeypotTripCreatesNothing() throws Exception {
            String code = "honeypot-store";

            register(registrationBody("Bot Store", code, "admin", "bot@example.com", "secret123",
                    "https://spam.example.com"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.tenantCode").value(code));

            assertEquals(0, emailSender.sent().size(), "a honeypot trip must never send an email");

            // Proof nothing was created: the same code registers for real right after.
            register(validRegistration(code)).andExpect(status().isCreated());
            assertEquals(1, emailSender.sent().size());
        }

        @Test
        @DisplayName("defaults the admin's display name to '<store name> Admin' when not given")
        void defaultsAdminDisplayName() throws Exception {
            String code = "no-display-name";
            register(validRegistration(code)).andExpect(status().isCreated());
            verify(extractToken(emailSender.last().body())).andExpect(status().isOk());

            login(code, "admin", "secret123")
                    .andExpect(jsonPath("$.user.displayName").value(code + " Store Admin"));
        }
    }

    @Nested
    @DisplayName("POST /api/tenants/verify")
    class Verify {

        @Test
        @DisplayName("an unknown token is generically rejected")
        void wrongTokenIsGenericallyRejected() throws Exception {
            verify("this-token-does-not-exist")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(INVALID_OR_EXPIRED));
        }

        @Test
        @DisplayName("an expired token is rejected with the identical generic message")
        void expiredTokenIsRejected() throws Exception {
            String code = "expiring-store";
            register(validRegistration(code)).andExpect(status().isCreated());
            String token = extractToken(emailSender.last().body());

            Tenant tenant = findByCode(code);
            tenant.setVerificationExpiresAt(Instant.now().minusSeconds(1));
            em.flush();
            em.clear();

            verify(token)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(INVALID_OR_EXPIRED));
        }

        @Test
        @DisplayName("a spent token cannot be replayed")
        void alreadySpentTokenCannotBeReplayed() throws Exception {
            String code = "spent-token-store";
            register(validRegistration(code)).andExpect(status().isCreated());
            String token = extractToken(emailSender.last().body());

            verify(token).andExpect(status().isOk());
            verify(token)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(INVALID_OR_EXPIRED));
        }

        /**
         * frontend/BUGS.md #17's regression case, ported: a {@code SUPER_ADMIN} suspending
         * a still-{@code PENDING_VERIFICATION} tenant must actually stick, even though the
         * token stays live until a *successful* verify clears it. Suspends by direct
         * entity mutation rather than through the platform endpoint — the guard under test
         * belongs to {@code verify()}, not to {@code PATCH /api/tenants/{id}}, which
         * {@code TenantAdminIT} already covers.
         */
        @Test
        @DisplayName("a tenant suspended while still pending resists its own still-valid token")
        void suspendedWhilePendingResistsItsOwnToken() throws Exception {
            String code = "susp-while-pending";
            register(validRegistration(code)).andExpect(status().isCreated());
            String token = extractToken(emailSender.last().body());

            Tenant tenant = findByCode(code);
            tenant.setStatus(TenantStatus.SUSPENDED);
            em.flush();
            em.clear();

            verify(token)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(INVALID_OR_EXPIRED));

            assertEquals(TenantStatus.SUSPENDED, findByCode(code).getStatus(),
                    "the still-live link must not have silently undone the suspension");
        }
    }

    @Nested
    @DisplayName("POST /api/tenants/resend-verification")
    class ResendVerification {

        @Test
        @DisplayName("regenerates the token and invalidates the old one")
        void regeneratesAndInvalidatesTheOldToken() throws Exception {
            String code = "resend-store";
            register(validRegistration(code)).andExpect(status().isCreated());
            String oldToken = extractToken(emailSender.last().body());

            resendVerification(resendBody(code, emailFor(code)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value(RESEND_ACK));

            assertEquals(2, emailSender.sent().size());
            String newToken = extractToken(emailSender.last().body());
            assertNotEquals(oldToken, newToken);

            verify(oldToken).andExpect(status().isBadRequest());
            verify(newToken).andExpect(status().isOk());
        }

        @Test
        @DisplayName("gives the identical acknowledgement for a fabricated pair, and sends nothing")
        void identicalAckForAFabricatedPair() throws Exception {
            resendVerification(resendBody("no-such-store", "nobody@nowhere.example.com"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value(RESEND_ACK));

            assertEquals(0, emailSender.sent().size());
        }

        @Test
        @DisplayName("gives the identical acknowledgement when the code matches but the email doesn't")
        void identicalAckWhenEmailDoesNotMatch() throws Exception {
            String code = "email-mismatch-store";
            register(validRegistration(code)).andExpect(status().isCreated());
            emailSender.clear();

            resendVerification(resendBody(code, "someone-else@example.com"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value(RESEND_ACK));

            assertEquals(0, emailSender.sent().size());
        }

        @Test
        @DisplayName("does nothing once the tenant is already verified, but still gives the same ack")
        void noResendOnceAlreadyVerified() throws Exception {
            String code = "already-verified-store";
            register(validRegistration(code)).andExpect(status().isCreated());
            verify(extractToken(emailSender.last().body())).andExpect(status().isOk());
            emailSender.clear();

            resendVerification(resendBody(code, emailFor(code)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value(RESEND_ACK));

            assertEquals(0, emailSender.sent().size());
        }
    }

    @Nested
    @DisplayName("RegistrationRateLimiter")
    class RateLimiting {

        @Test
        @DisplayName("register trips 429 on the 6th call from the same IP within the window")
        void registerTripsAfterFiveFromTheSameIp() throws Exception {
            String ip = "203.0.113.5";
            for (int i = 0; i < 5; i++) {
                register(validRegistration("rl-register-" + i), ip).andExpect(status().isCreated());
            }
            register(validRegistration("rl-register-6"), ip)
                    .andExpect(status().is(429))
                    .andExpect(jsonPath("$.message").value("Too many requests. Try again later."));
        }

        @Test
        @DisplayName("resend-verification has its own budget, independent of register's")
        void resendHasAnIndependentBudget() throws Exception {
            String ip = "203.0.113.9";
            String code = "rl-resend-store";
            // One register call from this IP -- must not count against resend's own budget.
            register(validRegistration(code), ip).andExpect(status().isCreated());

            for (int i = 0; i < 5; i++) {
                resendVerification(resendBody(code, emailFor(code)), ip).andExpect(status().isOk());
            }
            resendVerification(resendBody(code, emailFor(code)), ip).andExpect(status().is(429));
        }
    }

    @Test
    @DisplayName("once verified, a self-registered tenant is an ordinary tenant -- nothing about "
            + "registration special-cases isolation")
    void selfRegisteredTenantIsOrdinaryOnceVerified() throws Exception {
        String code = "ordinary-store";
        register(validRegistration(code)).andExpect(status().isCreated());
        verify(extractToken(emailSender.last().body())).andExpect(status().isOk());

        String response = login(code, "admin", "secret123")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String adminToken = JsonPath.read(response, "$.token");

        mvc.perform(get("/api/products").header("Authorization", "Bearer " + adminToken).param("pageSize", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));
    }

    // --- helpers -----------------------------------------------------------------

    private String emailFor(String code) {
        return "admin@" + code + ".example.com";
    }

    private String validRegistration(String code) {
        return registrationBody(code + " Store", code, "admin", emailFor(code), "secret123", "");
    }

    private String registrationBody(String storeName, String code, String username, String email,
                                    String password, String website) {
        return """
                {"storeName":"%s","tenantCode":"%s","adminUsername":"%s","adminEmail":"%s",\
                "adminPassword":"%s","website":"%s"}
                """.formatted(storeName, code, username, email, password, website);
    }

    private String resendBody(String code, String email) {
        return """
                {"tenantCode":"%s","adminEmail":"%s"}
                """.formatted(code, email);
    }

    private ResultActions register(String body) throws Exception {
        return register(body, freshIp());
    }

    private ResultActions register(String body, String ip) throws Exception {
        return mvc.perform(post("/api/tenants/register")
                .with(remoteAddr(ip))
                .contentType(APPLICATION_JSON)
                .content(body));
    }

    private ResultActions verify(String token) throws Exception {
        return mvc.perform(post("/api/tenants/verify")
                .contentType(APPLICATION_JSON)
                .content("""
                        {"token":"%s"}
                        """.formatted(token)));
    }

    private ResultActions resendVerification(String body) throws Exception {
        return resendVerification(body, freshIp());
    }

    private ResultActions resendVerification(String body, String ip) throws Exception {
        return mvc.perform(post("/api/tenants/resend-verification")
                .with(remoteAddr(ip))
                .contentType(APPLICATION_JSON)
                .content(body));
    }

    private ResultActions login(String tenantCode, String username, String password) throws Exception {
        ResultActions result = mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON).content("""
                {"tenantCode":"%s","username":"%s","password":"%s"}
                """.formatted(tenantCode, username, password)));
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        return result;
    }

    /** A fresh, never-before-used source IP, so this call can never share a rate-limit budget. */
    private String freshIp() {
        return "10.0.0." + (ipSeq.incrementAndGet() % 250 + 1);
    }

    private static RequestPostProcessor remoteAddr(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private String extractToken(String emailBody) {
        Matcher matcher = Pattern.compile("token=(\\S+)").matcher(emailBody);
        assertTrue(matcher.find(), () -> "no token found in email body: " + emailBody);
        return matcher.group(1);
    }

    /**
     * {@code Tenant} carries no {@code @Filter} — it's the discriminator, not a
     * tenant-owned row (C4) — so this plain JPQL lookup needs no {@link TenantContext}.
     */
    private Tenant findByCode(String code) {
        return em.createQuery("SELECT t FROM Tenant t WHERE t.code = :code", Tenant.class)
                .setParameter("code", code)
                .getSingleResult();
    }

    /**
     * Captures what {@code TenantRegistrationService} tried to send, instead of a real
     * SMTP connection or the log-only default — this suite needs to read the
     * verification token back out of the email body, which {@code LoggingEmailSender}
     * only puts in the log, not somewhere a test can assert on.
     */
    static class CapturingEmailSender implements EmailSender {

        record Sent(String to, String subject, String body) {
        }

        private final List<Sent> sent = new ArrayList<>();

        @Override
        public synchronized void send(String to, String subject, String body) {
            sent.add(new Sent(to, subject, body));
        }

        synchronized List<Sent> sent() {
            return List.copyOf(sent);
        }

        synchronized Sent last() {
            return sent.get(sent.size() - 1);
        }

        synchronized void clear() {
            sent.clear();
        }
    }

    /**
     * Supplies {@link CapturingEmailSender} in place of {@code MailConfig} — see the
     * class Javadoc. The {@code @Bean} method's return type is the concrete class, not
     * {@code EmailSender} — Spring registers a bean under both, and the concrete type
     * is what lets this class {@code @Autowired CapturingEmailSender} directly rather
     * than only the interface a factory method declared.
     */
    @Configuration
    static class TestMailConfig {

        @Bean
        public CapturingEmailSender emailSender() {
            return new CapturingEmailSender();
        }
    }
}

package com.pos.service.tenantregistration;

import com.pos.config.ImagesConfig;
import com.pos.config.PersistenceConfig;
import com.pos.config.RecaptchaConfig;
import com.pos.config.RootConfig;
import com.pos.config.SecurityConfig;
import com.pos.dao.TenantDao;
import com.pos.model.TenantRegistrationData;
import com.pos.model.TenantRegistrationForm;
import com.pos.util.email.EmailSender;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>The commit-then-email ordering</b> ({@code tenant-registration-plan.md} §4) —
 * flagged as deferred back at C9(d), landing now that C9(e) gave the rest of the
 * feature something to be tested through. Two claims, proven rather than reasoned
 * about — same discipline {@code StockRaceIT}/{@code LastAdminRaceIT} apply to their
 * own easy-to-silently-break invariants:
 *
 * <ol>
 *   <li>the tenant/admin row survives even when the email send throws;</li>
 *   <li>by the time the email is sent, the row is <i>already</i> visible from a
 *       completely separate, freshly-opened transaction — not merely from the same
 *       Hibernate session, which would be true even if the ordering were wrong.</li>
 * </ol>
 *
 * <h2>Not {@code @Transactional}, and it cannot be — same reasoning as {@code
 * StockRaceIT}</h2>
 * A wrapping test transaction would fold {@code TenantRegistrationWriter}'s own
 * transaction into it (Spring's default {@code REQUIRED} propagation), so nothing
 * would actually commit until the test method itself returned — which would make
 * both claims above trivially true regardless of whether {@link
 * TenantRegistrationService} ever crosses a real bean boundary at all. The fixture
 * cleanup uses a real, committed {@link TransactionTemplate}, exactly like {@code
 * StockRaceIT}'s.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {
        RootConfig.class, PersistenceConfig.class, SecurityConfig.class, RecaptchaConfig.class, ImagesConfig.class,
        TenantRegistrationCommitOrderingIT.TestMailConfig.class })
@TestPropertySource("classpath:application-test.properties")
@DisplayName("TenantRegistrationService -- the write commits independently of the email send")
class TenantRegistrationCommitOrderingIT {

    @Autowired
    private TenantRegistrationService tenantRegistrationService;

    @Autowired
    private ProbeEmailSender emailSender;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager em;

    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() {
        transactions = new TransactionTemplate(transactionManager);
        emailSender.reset();
    }

    @AfterEach
    void tearDown() {
        transactions.executeWithoutResult(status -> {
            em.createNativeQuery("DELETE FROM app_user").executeUpdate();
            em.createNativeQuery("DELETE FROM tenant").executeUpdate();
        });
    }

    @Test
    @DisplayName("the tenant/admin row survives even when the email send throws")
    void writeSurvivesAFailingEmailSend() {
        emailSender.throwOnSend = true;
        String code = "commit-order-fail";

        TenantRegistrationData result = tenantRegistrationService.register(form(code));

        assertEquals(code, result.getTenantCode(), "register() must return normally -- "
                + "the failing send is caught and logged, never rethrown");

        Boolean committed = transactions.execute(status -> em.createQuery(
                        "SELECT COUNT(t) FROM TenantPojo t WHERE t.code = :code", Long.class)
                .setParameter("code", code)
                .getSingleResult() == 1L);
        assertTrue(Boolean.TRUE.equals(committed),
                "the row must be committed for real, in its own transaction, despite the failing send");
    }

    @Test
    @DisplayName("the write is already visible from a separate transaction by the time the email is sent")
    void emailIsSentOnlyAfterTheWriteCommits() {
        String code = "commit-order-visible";
        emailSender.expectedCode = code;

        tenantRegistrationService.register(form(code));

        assertTrue(emailSender.wasVisibleAtSendTime,
                "the row must already be visible from a fresh transaction by the time send() runs -- "
                        + "if TenantRegistrationService.register ever regains its own @Transactional, "
                        + "this fails because nothing has committed yet at send time");
    }

    private TenantRegistrationForm form(String code) {
        TenantRegistrationForm form = new TenantRegistrationForm();
        form.setStoreName(code + " Store");
        form.setTenantCode(code);
        form.setAdminUsername("admin");
        form.setAdminEmail("admin@" + code + ".example.com");
        form.setAdminPassword("secret123");
        return form;
    }

    /**
     * Real collaborators (constructor-injected by Spring, not poked in after the
     * fact), behavior toggled per test via its public fields. {@code send()} runs
     * inside {@code TenantRegistrationService}'s call, so whatever it observes there
     * is exactly what a real {@code EmailSender} would.
     */
    static class ProbeEmailSender implements EmailSender {

        private final TenantDao tenantDao;
        private final PlatformTransactionManager transactionManager;

        volatile boolean throwOnSend;
        volatile String expectedCode;
        volatile boolean wasVisibleAtSendTime;

        ProbeEmailSender(TenantDao tenantDao, PlatformTransactionManager transactionManager) {
            this.tenantDao = tenantDao;
            this.transactionManager = transactionManager;
        }

        @Override
        public void send(String to, String subject, String body) {
            if (expectedCode != null) {
                // REQUIRES_NEW, not the default REQUIRED -- send() runs on the same thread
                // as the caller, so a REQUIRED TransactionTemplate would just JOIN
                // whatever transaction is already active there (including one it should
                // not be able to see into) rather than open a genuinely separate one.
                // Found by this test failing to catch its own mutation on the first
                // attempt: with @Transactional wrongly restored on register(), a REQUIRED
                // probe silently joined that transaction and "saw" its own uncommitted
                // write, so the test passed even though the ordering was broken.
                TransactionTemplate probe = new TransactionTemplate(transactionManager);
                probe.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
                wasVisibleAtSendTime = Boolean.TRUE.equals(
                        probe.execute(status -> tenantDao.findByCode(expectedCode) != null));
            }
            if (throwOnSend) {
                throw new RuntimeException("simulated SMTP failure");
            }
        }

        void reset() {
            throwOnSend = false;
            expectedCode = null;
            wasVisibleAtSendTime = false;
        }
    }

    @Configuration
    static class TestMailConfig {

        @Bean
        public ProbeEmailSender emailSender(TenantDao tenantDao, PlatformTransactionManager transactionManager) {
            return new ProbeEmailSender(tenantDao, transactionManager);
        }
    }
}

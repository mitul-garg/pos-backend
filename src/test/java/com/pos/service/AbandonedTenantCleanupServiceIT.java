package com.pos.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import com.pos.config.MailConfig;
import com.pos.config.PersistenceConfig;
import com.pos.config.RecaptchaConfig;
import com.pos.config.RootConfig;
import com.pos.config.SecurityConfig;
import com.pos.pojo.AppUserPojo;
import com.pos.pojo.enums.Role;
import com.pos.pojo.TenantPojo;
import com.pos.pojo.enums.TenantStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link AbandonedTenantCleanupService#cleanUp()} — peer-review Phase 1, net-new.
 *
 * <p>Exercised directly, not through {@code AbandonedTenantCleanupJob}: a scheduled job
 * has no HTTP request to attach a test to, the same reason {@code DevSeeder} is tested
 * by calling {@code seed()} rather than by waiting for a startup event. {@code
 * pos.job.abandonedTenant.enabled=false} in {@code application-test.properties} keeps
 * the real scheduler from also firing a redundant run of its own against whatever this
 * suite's fixtures look like at any given moment in a shared-context test run.
 *
 * <p>Fixtures are persisted directly via the {@code EntityManager}, backdating {@code
 * verificationExpiresAt} the way no HTTP endpoint can — {@code
 * TenantRegistrationWriter.mintToken} only ever sets it to {@code now + 24h}.
 *
 * <p>Same {@code @ContextConfiguration} as {@code SecurityConfigIT} (root context only,
 * no {@code WebConfig}/{@code OpenApiConfig} — this suite never goes through HTTP),
 * deliberately, so the two share one cached Spring context instead of standing up a new
 * one — see {@code TenantRegistrationIT}'s class Javadoc for what happens when a second
 * file introduces an almost-but-not-quite-identical combination instead.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {
        RootConfig.class, PersistenceConfig.class, SecurityConfig.class, MailConfig.class,
        RecaptchaConfig.class })
@TestPropertySource("classpath:application-test.properties")
@Transactional
@DisplayName("AbandonedTenantCleanupService.cleanUp()")
class AbandonedTenantCleanupServiceIT {

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private AbandonedTenantCleanupService cleanupService;

    @Test
    @DisplayName("deletes a PENDING_VERIFICATION tenant whose token expired, and its admin")
    void deletesAnExpiredAbandonedRegistration() {
        TenantPojo tenant = pendingTenant("cleanup-expired", Instant.now().minus(1, ChronoUnit.HOURS));
        AppUserPojo admin = adminFor(tenant, "cleanup-expired-admin@example.com");
        em.flush();
        Long tenantId = tenant.getId();
        Long adminId = admin.getId();
        // Detach the fixtures before calling the service under test -- a bulk DELETE
        // (AppUserDao.deleteByTenant) doesn't sync the persistence context, and this
        // test's own `admin`/`tenant` objects staying managed alongside a bulk delete of
        // the same rows is a fixture artifact, not something production ever does
        // (AbandonedTenantCleanupJob always runs cleanUp() in a fresh transaction with
        // nothing pre-loaded). Clearing here makes the service's own queries the only
        // source of managed entities, same as a real run.
        em.clear();

        int removed = cleanupService.cleanUp();
        em.flush();
        em.clear();

        assertEquals(1, removed);
        assertNull(em.find(TenantPojo.class, tenantId), "the abandoned tenant should be gone");
        assertNull(em.find(AppUserPojo.class, adminId), "its admin should be gone too");
    }

    @Test
    @DisplayName("leaves a PENDING_VERIFICATION tenant alone while its token is still valid")
    void leavesAStillPendingRegistrationAlone() {
        TenantPojo tenant = pendingTenant("cleanup-still-pending", Instant.now().plus(23, ChronoUnit.HOURS));
        adminFor(tenant, "cleanup-still-pending-admin@example.com");
        em.flush();
        Long tenantId = tenant.getId();
        em.clear(); // see deletesAnExpiredAbandonedRegistration for why

        int removed = cleanupService.cleanUp();
        em.flush();
        em.clear();

        assertEquals(0, removed);
        assertNotNull(em.find(TenantPojo.class, tenantId), "a still-valid registration must survive");
    }

    @Test
    @DisplayName("leaves an ACTIVE tenant alone even with a stale expiry timestamp")
    void leavesAnActiveTenantAlone() {
        // Real ACTIVE tenants never carry a past verificationExpiresAt --
        // TenantRegistrationService.verify() nulls it out on success -- but this proves
        // the status check itself is load-bearing, not merely a timestamp comparison
        // that happens to work only because active rows never have a stale one in
        // practice.
        TenantPojo tenant = new TenantPojo();
        tenant.setName("Cleanup Test: Active, Stale Timestamp");
        tenant.setCode("cleanup-active-stale");
        tenant.setStatus(TenantStatus.ACTIVE);
        tenant.setPlatform(false);
        tenant.setVerificationExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS));
        em.persist(tenant);
        adminFor(tenant, "cleanup-active-stale-admin@example.com");
        em.flush();
        Long tenantId = tenant.getId();
        em.clear(); // see deletesAnExpiredAbandonedRegistration for why

        int removed = cleanupService.cleanUp();
        em.flush();
        em.clear();

        assertEquals(0, removed);
        assertNotNull(em.find(TenantPojo.class, tenantId), "an ACTIVE tenant must never be swept");
    }

    @Test
    @DisplayName("removes every abandoned tenant in one run, not just the first")
    void removesMultipleAbandonedTenantsInOneRun() {
        TenantPojo first = pendingTenant("cleanup-batch-1", Instant.now().minus(2, ChronoUnit.HOURS));
        adminFor(first, "cleanup-batch-1-admin@example.com");
        TenantPojo second = pendingTenant("cleanup-batch-2", Instant.now().minus(30, ChronoUnit.MINUTES));
        adminFor(second, "cleanup-batch-2-admin@example.com");
        em.flush();
        Long firstId = first.getId();
        Long secondId = second.getId();
        em.clear(); // see deletesAnExpiredAbandonedRegistration for why

        int removed = cleanupService.cleanUp();
        em.flush();
        em.clear();

        assertEquals(2, removed);
        assertNull(em.find(TenantPojo.class, firstId));
        assertNull(em.find(TenantPojo.class, secondId));
    }

    // --- fixtures -----------------------------------------------------------------

    private TenantPojo pendingTenant(String code, Instant verificationExpiresAt) {
        TenantPojo tenant = new TenantPojo();
        tenant.setName("Cleanup Test Store " + code);
        tenant.setCode(code);
        tenant.setStatus(TenantStatus.PENDING_VERIFICATION);
        tenant.setPlatform(false);
        tenant.setVerificationToken("token-" + code);
        tenant.setVerificationExpiresAt(verificationExpiresAt);
        em.persist(tenant);
        return tenant;
    }

    private AppUserPojo adminFor(TenantPojo tenant, String email) {
        AppUserPojo admin = new AppUserPojo();
        admin.setTenantId(tenant.getId());
        admin.setUsername("admin");
        admin.setPasswordHash("not-a-real-hash");
        admin.setDisplayName("Cleanup Test Admin");
        admin.setEmail(email);
        admin.setRole(Role.ADMIN);
        admin.setActive(true);
        em.persist(admin);
        return admin;
    }
}

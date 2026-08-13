package com.pos.service;

import java.time.Instant;
import java.util.List;

import com.pos.dao.AppUserDao;
import com.pos.dao.TenantDao;
import com.pos.pojo.TenantPojo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Peer-review Phase 1, net-new. Reclaims what an abandoned self-registration would
 * otherwise squat forever: {@code uk_tenant_code} is global and nothing previously
 * cleared an expired, never-verified {@code PENDING_VERIFICATION} row, so a code once
 * claimed stayed claimed — permanently blocking a real business with the same name —
 * and the row kept counting against {@code TenantRegistrationWriter}'s lifetime
 * tenants-per-email guardrail besides.
 *
 * <p><b>No new time constant.</b> "Abandoned" means exactly what {@code
 * TenantRegistrationService.verify} already means by "expired": {@code
 * verificationExpiresAt} in the past. That field is set to {@code now + 24h} on both
 * registration and every resend ({@code TenantRegistrationWriter.mintToken}), so a
 * registration that's still being actively pursued — including one resent minutes
 * before this job runs — is never a candidate.
 *
 * <p>{@code AbandonedTenantCleanupJob} (in the new {@code com.pos.job} package) is the
 * only caller in production; a scheduled job has no HTTP request to attach a manual
 * test to, so this is exercised directly the same way {@code DevSeeder} is at startup.
 */
@Service
public class AbandonedTenantCleanupService {

    private final TenantDao tenantDao;
    private final AppUserDao appUserDao;

    @Autowired
    public AbandonedTenantCleanupService(TenantDao tenantDao, AppUserDao appUserDao) {
        this.tenantDao = tenantDao;
        this.appUserDao = appUserDao;
    }

    /**
     * Deletes every abandoned {@code PENDING_VERIFICATION} tenant and its one admin
     * row, admin first — see {@code TenantDao.delete}'s Javadoc for why that order is
     * load-bearing, not tidiness.
     *
     * <p>One transaction for the whole batch: {@code TenantDao.findAbandonedPending
     * Verification} takes a row lock on every candidate as it reads them, so nothing
     * here needs a second re-check before deleting — the lock is the referee, the
     * same shape {@code AppUserDao.lockTenant} and {@code OrderDao.findForUpdate}
     * already use elsewhere in this codebase for a check an atomic statement can't
     * express.
     *
     * @return how many tenants were removed, for the caller to log
     */
    @Transactional
    public int cleanUp() {
        List<TenantPojo> abandoned = tenantDao.findAbandonedPendingVerification(Instant.now());
        for (TenantPojo tenant : abandoned) {
            appUserDao.deleteByTenant(tenant.getId());
            tenantDao.delete(tenant);
        }
        return abandoned.size();
    }
}

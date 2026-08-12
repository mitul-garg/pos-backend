package com.pos.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import com.pos.config.AppProperties;
import com.pos.dao.AppUserDao;
import com.pos.dao.TenantDao;
import com.pos.exception.ValidationException;
import com.pos.model.TenantRegistrationForm;
import com.pos.pojo.AppUser;
import com.pos.pojo.Role;
import com.pos.pojo.Tenant;
import com.pos.pojo.TenantStatus;
import com.pos.util.EmailFormat;
import com.pos.util.VerificationTokens;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional half of self-registration (C9) — split out from
 * {@link TenantRegistrationService} for one specific, structural reason:
 * {@code register}/{@code resendVerification} must send their email <b>after</b>
 * their write has actually committed, never inside the same transaction
 * (`tenant-registration-plan.md` §4 — a slow/failed SMTP send must never roll back
 * the tenant/admin row, and holding a database transaction open across a network
 * round trip to Gmail's SMTP server is its own problem regardless, on a connection
 * pool this project already sizes tightly for free-tier MySQL).
 *
 * <p><b>Why this needs to be a second bean, not a second method on the same
 * class.</b> Spring's {@code @Transactional} is proxy-based: it only takes effect on
 * a call that arrives through the bean's proxy, and a method calling another method
 * of the <i>same</i> class via {@code this.} bypasses that proxy entirely — so one
 * class cannot have a non-transactional method that calls a transactional one and
 * observe a real commit in between; both would share whatever transaction (if any)
 * was already open, or run with none at all. Crossing an actual bean boundary is the
 * standard fix, not a workaround: {@code TenantRegistrationService.register} calls
 * this bean's {@code @Transactional} method through its Spring proxy, which returns
 * only once that transaction has actually committed — and only <i>then</i> does the
 * (deliberately non-transactional) caller send the email.
 *
 * <p>Returns a small handoff record ({@link RegisteredTenant}), not the JPA entities
 * themselves — the persistence context this method's transaction owns closes the
 * moment it returns, and {@code AppUser.tenant} is {@code LAZY} (CONVENTIONS.md), so
 * handing back an entity would invite exactly the post-transaction lazy access the
 * "entities never leave the service layer" rule exists to avoid, one layer removed.
 *
 * <p><b>{@code public}, not package-private,</b> despite being an internal
 * implementation detail with exactly one real caller — {@code StubServiceConfig}
 * (test sources) has to name this type from {@code com.pos.config} to stub {@link
 * TenantRegistrationService} for the servlet-context-only suites, the same reason
 * every other service class in this codebase is {@code public}.
 */
@Service
public class TenantRegistrationWriter {

    static final String STORE_NAME_REQUIRED = "Store name is required";
    static final String ADMIN_USERNAME_REQUIRED = "The admin's username is required";
    static final String ADMIN_EMAIL_REQUIRED = "Email is required";
    static final String ADMIN_EMAIL_FORMAT = "Enter a valid email address";
    static final String ADMIN_PASSWORD_REQUIRED = "The admin's password is required";

    /**
     * Peer-review Phase 0 resource-creation guardrail: a durable lifetime cap on tenants
     * per admin email, not a time-windowed rate limit — see {@link #register}. Merges the
     * original review's "tenants a user can own" and "pending/unverified registrations
     * per email" concerns into one check: counting every status, not just pending, is
     * simpler than a separate racy concurrent-pending check and catches the same abuse.
     */
    static final String TOO_MANY_TENANTS_FOR_EMAIL =
            "This email address has reached its store registration limit";

    private static final long TOKEN_TTL_HOURS = 24;

    private final TenantDao tenantDao;
    private final AppUserDao appUserDao;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties appProperties;

    @Autowired
    public TenantRegistrationWriter(TenantDao tenantDao, AppUserDao appUserDao,
                                    PasswordEncoder passwordEncoder, AppProperties appProperties) {
        this.tenantDao = tenantDao;
        this.appUserDao = appUserDao;
        this.passwordEncoder = passwordEncoder;
        this.appProperties = appProperties;
    }

    /**
     * Validates, then creates the tenant ({@link TenantStatus#PENDING_VERIFICATION})
     * and its first {@code ADMIN} ({@code active = true} — the <i>tenant's</i> status
     * is what blocks login, not the user's) atomically, mints a token, and returns
     * what the caller needs to send the verification email.
     *
     * <p>Never reached for a honeypot-tripped request —
     * {@code TenantRegistrationService.register} short-circuits before this is
     * called, so a trip never touches the database at all.
     *
     * <p>No duplicate-username check against other tenants, same reasoning
     * {@code TenantService.create} already documents: {@code (tenant_id, username)}
     * is the constraint, and this tenant is brand new, so its namespace starts
     * empty.
     */
    @Transactional
    RegisteredTenant register(TenantRegistrationForm form) {
        Map<String, String> errors = new LinkedHashMap<>();

        String storeName = trimToNull(form.getStoreName());
        if (storeName == null) {
            errors.put("storeName", STORE_NAME_REQUIRED);
        }
        String tenantCode = TenantCodeRule.validate(tenantDao, form.getTenantCode(), "tenantCode", errors);

        String adminUsername = trimToNull(form.getAdminUsername());
        if (adminUsername == null) {
            errors.put("adminUsername", ADMIN_USERNAME_REQUIRED);
        }
        String adminEmail = trimToNull(form.getAdminEmail());
        if (adminEmail == null) {
            errors.put("adminEmail", ADMIN_EMAIL_REQUIRED);
        } else if (!EmailFormat.isValid(adminEmail)) {
            errors.put("adminEmail", ADMIN_EMAIL_FORMAT);
        }
        if (form.getAdminPassword() == null || form.getAdminPassword().isEmpty()) {
            errors.put("adminPassword", ADMIN_PASSWORD_REQUIRED);
        }
        if (!errors.isEmpty()) {
            throw new ValidationException(errors.values().iterator().next(), errors);
        }
        // Peer-review Phase 0 resource-creation guardrail -- unlike the bare
        // ValidationExceptions in UserService/ProductService/VariantService, this one DOES
        // trace to a single submitted field, so it's field-level like the checks above it
        // rather than bare.
        if (appUserDao.countByEmail(adminEmail.toLowerCase(Locale.ROOT))
                >= appProperties.getTenantMaxPerEmail()) {
            throw ValidationException.field("adminEmail", TOO_MANY_TENANTS_FOR_EMAIL);
        }

        String adminDisplayName = trimToNull(form.getAdminDisplayName());
        if (adminDisplayName == null) {
            adminDisplayName = storeName + " Admin";
        }

        Tenant tenant = new Tenant();
        tenant.setName(storeName);
        tenant.setCode(tenantCode);
        tenant.setStatus(TenantStatus.PENDING_VERIFICATION);
        tenant.setPlatform(false);
        String token = mintToken(tenant);
        tenantDao.insert(tenant);

        AppUser admin = new AppUser();
        admin.setTenant(tenant);
        admin.setUsername(adminUsername);
        admin.setPasswordHash(passwordEncoder.encode(form.getAdminPassword()));
        admin.setDisplayName(adminDisplayName);
        admin.setEmail(adminEmail);
        admin.setRole(Role.ADMIN);
        admin.setActive(true);
        appUserDao.insert(admin);

        return new RegisteredTenant(tenant.getName(), tenant.getCode(), adminEmail, token);
    }

    /**
     * Re-validates {@code tenantCode}/{@code adminEmail} against a
     * {@code PENDING_VERIFICATION} tenant and, only on a real match, regenerates the
     * token — invalidating whatever was live before, so an old copy of the email
     * can't be used after a resend. Returns {@code null} otherwise (unknown code,
     * wrong status, or no admin with that email in that tenant), so {@code
     * TenantRegistrationService.resendVerification} can send the identical
     * acknowledgement either way without this method ever having to say which case
     * it was — the same no-enumeration shape {@code AuthService.login} already
     * gives a wrong username vs a wrong password.
     */
    @Transactional
    RegisteredTenant resendVerification(String tenantCode, String adminEmail) {
        String code = tenantCode == null ? "" : tenantCode.trim().toLowerCase(Locale.ROOT);
        String email = adminEmail == null ? "" : adminEmail.trim().toLowerCase(Locale.ROOT);

        Tenant tenant = tenantDao.findByCode(code);
        if (tenant == null || tenant.getStatus() != TenantStatus.PENDING_VERIFICATION) {
            return null;
        }
        AppUser admin = appUserDao.findByTenantAndEmail(tenant.getId(), email);
        if (admin == null) {
            return null;
        }

        String token = mintToken(tenant);
        return new RegisteredTenant(tenant.getName(), tenant.getCode(), admin.getEmail(), token);
    }

    private String mintToken(Tenant tenant) {
        String token = VerificationTokens.generate();
        tenant.setVerificationToken(token);
        tenant.setVerificationExpiresAt(Instant.now().plus(TOKEN_TTL_HOURS, ChronoUnit.HOURS));
        return token;
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}

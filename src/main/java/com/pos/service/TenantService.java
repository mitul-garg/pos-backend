package com.pos.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.pos.dao.AppUserDao;
import com.pos.dao.TenantDao;
import com.pos.exception.NotFoundException;
import com.pos.exception.ValidationException;
import com.pos.model.TenantData;
import com.pos.model.TenantForm;
import com.pos.model.TenantStatusForm;
import com.pos.pojo.AppUser;
import com.pos.pojo.Role;
import com.pos.pojo.Tenant;
import com.pos.pojo.TenantStatus;
import com.pos.util.PlatformOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The platform surface (C8) — the port of {@code tenantService.js}: list every tenant,
 * create one atomically with its first {@code ADMIN}, suspend/reactivate.
 *
 * <p><b>Gated entirely by {@code SecurityConfig}, not by a check in here.</b> The mock's
 * {@code requireSuperAdmin()} has no equivalent in this class — every method below is
 * reachable only through a URL {@code SecurityConfig.platformMatchers()} restricts to
 * {@code ROLE_SUPER_ADMIN}, matching how {@code ProductController}'s writes rely on
 * {@code adminMatchers()} rather than a role check inside {@code ProductService}. One
 * file answers "who can reach the platform surface?"; this one does not repeat it.
 *
 * <p><b>Every public method here is {@link PlatformOperation}</b>, not because each one
 * disables the Hibernate filter itself (only {@code TenantDao.productCount}/
 * {@code orderCount} do that), but because all four read or write outside the caller's
 * own tenant — the caller has none. See {@link PlatformOperation}'s Javadoc for the two
 * distinct reasons a method carries the marker.
 */
@Service
public class TenantService {

    /** Matches the frontend's {@code 'Tenant not found'} exactly. */
    static final String NOT_FOUND = "Tenant not found";

    /** Verbatim from {@code domain/validators.js} / {@code tenantService.js}. */
    static final String NAME_REQUIRED = "Tenant name is required";
    static final String ADMIN_USERNAME_REQUIRED = "The first admin's username is required";
    static final String ADMIN_PASSWORD_REQUIRED = "The first admin's password is required";

    private final TenantDao tenantDao;
    private final AppUserDao appUserDao;
    private final PasswordEncoder passwordEncoder;

    @Autowired
    public TenantService(TenantDao tenantDao, AppUserDao appUserDao,
                         PasswordEncoder passwordEncoder) {
        this.tenantDao = tenantDao;
        this.appUserDao = appUserDao;
        this.passwordEncoder = passwordEncoder;
    }

    /** {@code GET /api/tenants} — every tenant but the reserved platform row, newest first. */
    @Transactional(readOnly = true)
    @PlatformOperation
    public List<TenantData> list() {
        return tenantDao.list().stream().map(this::toData).toList();
    }

    /**
     * {@code GET /api/tenants/{id}}.
     *
     * <p>The platform row itself answers 404 here, same as an id that was never issued —
     * {@code Tenant.isPlatform()}'s Javadoc says it "is excluded from GET /api/tenants and
     * cannot be suspended", and a direct-by-id fetch is the other half of "excluded".
     */
    @Transactional(readOnly = true)
    @PlatformOperation
    public TenantData get(Long id) {
        Tenant tenant = requireManaged(id);
        return toData(tenant);
    }

    /**
     * {@code POST /api/tenants} — creates the tenant <b>and</b> its first {@code ADMIN} in
     * one call, deliberately atomic: a tenant with no admin is unusable and nobody could
     * log in to fix it, so there is no path that creates one without the other (matching
     * {@code tenantService.create}'s own comment).
     *
     * <p>Reports every broken field in one 400, {@code ProductService.validate}'s
     * reasoning rather than the mock's sequential single-field throws.
     */
    @Transactional
    @PlatformOperation
    public TenantData create(TenantForm form) {
        Map<String, String> errors = new LinkedHashMap<>();

        String name = trimToNull(form.getName());
        if (name == null) {
            errors.put("name", NAME_REQUIRED);
        }
        String code = TenantCodeRule.validate(tenantDao, form.getCode(), "code", errors);
        String adminUsername = trimToNull(form.getAdminUsername());
        if (adminUsername == null) {
            errors.put("adminUsername", ADMIN_USERNAME_REQUIRED);
        }
        if (form.getAdminPassword() == null || form.getAdminPassword().isEmpty()) {
            errors.put("adminPassword", ADMIN_PASSWORD_REQUIRED);
        }
        if (!errors.isEmpty()) {
            throw new ValidationException(errors.values().iterator().next(), errors);
        }

        Tenant tenant = new Tenant();
        tenant.setName(name);
        tenant.setCode(code);
        tenant.setStatus(TenantStatus.ACTIVE);
        tenant.setPlatform(false);
        tenantDao.insert(tenant);

        // No uniqueness check against other tenants' usernames: (tenant_id, username) is
        // the constraint, and this tenant is brand new, so its namespace starts empty.
        AppUser admin = new AppUser();
        admin.setTenant(tenant);
        admin.setUsername(adminUsername);
        admin.setPasswordHash(passwordEncoder.encode(form.getAdminPassword()));
        admin.setDisplayName(name + " Admin");
        admin.setRole(Role.ADMIN);
        admin.setActive(true);
        appUserDao.insert(admin);

        return toData(tenant);
    }

    /**
     * {@code PATCH /api/tenants/{id}} — suspend or reactivate. {@code status} absent is a
     * no-op, matching the merge-patch rule every other write form in this application
     * follows, even though {@code status} is the only field this one has.
     */
    @Transactional
    @PlatformOperation
    public TenantData updateStatus(Long id, TenantStatusForm form) {
        Tenant tenant = requireManaged(id);
        if (form.getStatus() != null) {
            tenant.setStatus(form.getStatus());
        }
        return toData(tenant);
    }

    /** {@link Tenant#find} by id, excluding the reserved platform row either way. */
    private Tenant requireManaged(Long id) {
        Tenant tenant = tenantDao.find(id);
        if (tenant == null || tenant.isPlatform()) {
            throw new NotFoundException(NOT_FOUND);
        }
        return tenant;
    }

    /**
     * Mapped inside the transaction. The three counts are each their own query
     * ({@code AppUserDao.countByTenant}, {@code TenantDao.productCount}/{@code orderCount})
     * rather than one join, matching the mock's three separate {@code .filter().length}
     * calls in {@code withCounts} — a tenant with no products or orders yet is the common
     * case for a freshly created store, and three simple counts are cheaper to get right
     * than one query spanning three unrelated tables.
     */
    private TenantData toData(Tenant tenant) {
        Long id = tenant.getId();
        return new TenantData(
                id,
                tenant.getName(),
                tenant.getCode(),
                tenant.getStatus(),
                tenant.getCreatedAt(),
                appUserDao.countByTenant(id),
                tenantDao.productCount(id),
                tenantDao.orderCount(id));
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}

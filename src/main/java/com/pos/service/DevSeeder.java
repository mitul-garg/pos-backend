package com.pos.service;

import com.pos.config.AppProperties;
import com.pos.dao.AppUserDao;
import com.pos.dao.TenantDao;
import com.pos.pojo.AppUser;
import com.pos.pojo.Role;
import com.pos.pojo.Tenant;
import com.pos.pojo.TenantStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads the frontend's demo tenants and users, so the same logins work against real
 * persistence and the B6 isolation checklist can be re-run end to end (backend-plan.md
 * section 9).
 *
 * <p><b>Gated on {@code pos.seed.dev}, which defaults to false.</b> Unlike the other
 * local-flavoured defaults in {@code application.properties}, this one fails closed: it
 * creates admin accounts whose passwords are published in the README, so an environment
 * that forgets to set it must end up with no accounts rather than with those. Turn it on
 * with {@code mvn jetty:run -DPOS_SEED_DEV=true}.
 *
 * <p><b>Seeds users and tenants only.</b> Products, variants and orders arrive with the
 * steps that own them (C5-C7) — seeding an order here would mean duplicating the pricing
 * and sequence logic those steps are about to write, and the duplicate would be the one
 * nobody updates.
 *
 * <p>The deliberate collisions are the point and must survive: {@code admin} and
 * {@code cashier} exist in <i>both</i> stores with the same passwords. A schema that
 * rejects that has a global constraint where a per-tenant one belongs, and the isolation
 * suite loses its fixtures.
 */
@Service
public class DevSeeder {

    private static final Logger log = LoggerFactory.getLogger(DevSeeder.class);

    @Autowired
    private AppProperties props;

    @Autowired
    private TenantDao tenantDao;

    @Autowired
    private AppUserDao appUserDao;

    @Autowired
    private PasswordEncoder passwordEncoder;

    /**
     * Runs once the context is fully refreshed, rather than from {@code @PostConstruct} —
     * seeding needs the entity manager factory and the transaction manager to be live,
     * and construction order does not guarantee that.
     *
     * <p>Fires more than once in the deployed layout: the servlet context is a child, and
     * publishing an event propagates it to the parent, so the root's listener sees both
     * refreshes. {@link #seed()} being idempotent is what makes that a non-event — and it
     * has to be idempotent regardless, because local dev runs {@code hbm2ddl.auto=update}
     * and the rows survive a restart.
     */
    @EventListener(ContextRefreshedEvent.class)
    @Transactional
    public void seedOnStartup() {
        if (!props.isSeedDev()) {
            return;
        }
        seed();
    }

    /**
     * Idempotent: a tenant that already exists is left exactly as it is, including any
     * status change made through the API. Re-seeding must not quietly reactivate a store
     * someone suspended to test the 403.
     */
    @Transactional
    public void seed() {
        Tenant platform = seedTenant("Platform", Tenant.PLATFORM_CODE, true);
        Tenant mgRoad = seedTenant("MG Road Store", "mg-road", false);
        Tenant airport = seedTenant("Airport Store", "airport", false);

        seedUser(platform, "superadmin", "super123", "Platform Admin", Role.SUPER_ADMIN);

        // Usernames repeat across the two stores on purpose -- see the class comment.
        seedUser(mgRoad, "admin", "admin123", "MG Road Admin", Role.ADMIN);
        seedUser(mgRoad, "cashier", "cashier123", "MG Road Cashier", Role.CASHIER);
        seedUser(airport, "admin", "admin123", "Airport Admin", Role.ADMIN);
        seedUser(airport, "cashier", "cashier123", "Airport Cashier", Role.CASHIER);
    }

    private Tenant seedTenant(String name, String code, boolean platform) {
        Tenant existing = tenantDao.findByCode(code);
        if (existing != null) {
            return existing;
        }
        Tenant tenant = new Tenant();
        tenant.setName(name);
        tenant.setCode(code);
        tenant.setStatus(TenantStatus.ACTIVE);
        tenant.setPlatform(platform);
        tenantDao.insert(tenant);
        log.info("Seeded tenant {} ({})", name, code);
        return tenant;
    }

    private void seedUser(Tenant tenant, String username, String password,
                          String displayName, Role role) {
        if (appUserDao.findByTenantAndUsername(tenant.getId(), username) != null) {
            return;
        }
        AppUser user = new AppUser();
        user.setTenant(tenant);
        user.setUsername(username);
        // Hashed on insert, never stored in plaintext -- backend-plan.md section 1,
        // deferred obligation 4. The frontend mock keeps plaintext; that stops here.
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setDisplayName(displayName);
        user.setRole(role);
        user.setActive(true);
        appUserDao.insert(user);
        log.info("Seeded user {}/{} as {}", tenant.getCode(), username, role);
    }
}

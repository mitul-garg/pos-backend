package com.pos.service;

import java.util.List;

import com.pos.config.ImagesConfig;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The seeder, run for real at context startup (C3).
 *
 * <p>The rows under test were <b>committed at startup by the real listener</b>, not
 * arranged by a fixture method — so what is asserted here is the same code path
 * {@code mvn jetty:run} takes. {@code @Transactional} covers only the tests' own writes
 * (suspending a store, re-running the seeder), which roll back so no case can leave the
 * next one a different database.
 *
 * <p>Ports {@code seeds.test.js}'s invariants: every user belongs to a tenant, nothing
 * straddles a boundary, and the <b>deliberate collisions</b> are accepted rather than
 * rejected.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {
        RootConfig.class, PersistenceConfig.class, SecurityConfig.class, MailConfig.class,
        RecaptchaConfig.class, ImagesConfig.class })
@TestPropertySource(
        locations = "classpath:application-test.properties",
        // The gate itself. Everywhere else it is false, which is what keeps an IT's own
        // fixtures the only rows in the database.
        properties = "pos.seed.dev=true")
@Transactional
@DisplayName("DevSeeder, with pos.seed.dev=true")
class DevSeederIT {

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private DevSeeder seeder;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("seeds the three tenants, one of them the reserved platform row")
    void seedsTheTenants() {
        assertEquals(TenantStatus.ACTIVE, tenant("mg-road").getStatus());
        assertEquals("MG Road Store", tenant("mg-road").getName());
        assertEquals("Airport Store", tenant("airport").getName());

        // The reserved row is flagged, and the two real stores are not. That flag is what
        // keeps a SUPER_ADMIN's tenantId null on the wire, and what C8 excludes from
        // GET /api/tenants.
        assertTrue(tenant("platform").isPlatform());
        assertFalse(tenant("mg-road").isPlatform());
        assertFalse(tenant("airport").isPlatform());
    }

    @Test
    @DisplayName("gives both stores an admin and a cashier with the SAME usernames")
    void keepsTheDeliberateCollisions() {
        // Not a quirk of the fixtures -- the reason login takes a tenant code at all, and
        // the fixture the isolation suite is built on. A schema that rejected this would
        // have a global unique key where uk_user_tenant_username belongs.
        List<AppUserPojo> admins = em.createQuery(
                        "SELECT u FROM AppUserPojo u WHERE u.username = 'admin'", AppUserPojo.class)
                .getResultList();

        assertEquals(2, admins.size(), "both stores should have an `admin`");
        assertNotEquals(admins.get(0).getTenantId(), admins.get(1).getTenantId());
    }

    @Test
    @DisplayName("hashes every password with BCrypt and stores no plaintext")
    void hashesEveryPassword() {
        // backend-plan.md section 1, deferred obligation 4. The mock stores plaintext;
        // this is where that stops.
        for (AppUserPojo user : allUsers()) {
            String hash = user.getPasswordHash();
            assertTrue(hash.startsWith("$2a$") || hash.startsWith("$2b$") || hash.startsWith("$2y$"),
                    () -> user.getUsername() + " has a password that is not a BCrypt hash: " + hash);
            assertFalse(hash.contains("admin123") || hash.contains("cashier123")
                            || hash.contains("super123"),
                    () -> user.getUsername() + " stored its password in plaintext");
        }
    }

    @Test
    @DisplayName("hashes the passwords the README advertises, so the documented logins work")
    void hashesTheDocumentedPasswords() {
        // Ties the seeder to backend/README.md's credentials table. If someone changes one
        // without the other, the manual-testing instructions silently stop working.
        assertTrue(passwordEncoder.matches("admin123", user("mg-road", "admin").getPasswordHash()));
        assertTrue(passwordEncoder.matches("cashier123", user("airport", "cashier").getPasswordHash()));
        assertTrue(passwordEncoder.matches("super123", user("platform", "superadmin").getPasswordHash()));
    }

    @Test
    @DisplayName("gives the platform admin the SUPER_ADMIN role and no tenant role")
    void seedsThePlatformAdmin() {
        assertEquals(Role.SUPER_ADMIN, user("platform", "superadmin").getRole());
        assertEquals(Role.ADMIN, user("mg-road", "admin").getRole());
        assertEquals(Role.CASHIER, user("mg-road", "cashier").getRole());
    }

    @Test
    @DisplayName("is idempotent, because update-mode dev databases keep their rows across restarts")
    void isIdempotent() {
        long before = userCount();

        // The second run has already happened once in production terms: the servlet
        // context is a child, so publishing ContextRefreshedEvent propagates to the root
        // and the listener fires twice per boot. Calling it again here makes that
        // explicit rather than incidental.
        seeder.seed();
        em.flush();

        assertEquals(before, userCount(),
                "re-seeding inserted duplicates; uk_user_tenant_username would reject them "
                        + "on a real restart");
    }

    @Test
    @DisplayName("leaves an existing tenant's status alone, so a suspension survives a restart")
    void doesNotResurrectASuspendedTenant() {
        TenantPojo airport = tenant("airport");
        airport.setStatus(TenantStatus.SUSPENDED);
        em.flush();

        seeder.seed();
        em.flush();
        em.clear();

        assertEquals(TenantStatus.SUSPENDED, tenant("airport").getStatus(),
                "re-seeding reactivated a store someone had suspended");
    }

    @Test
    @DisplayName("gives every seeded user a tenant")
    void everyUserBelongsToATenant() {
        // seeds.test.js's headline invariant. tenant_id is NOT NULL in the schema, so this
        // is really asserting that the platform user points at the reserved row rather
        // than at nothing -- the decision that lets uk_user_tenant_username constrain
        // platform usernames at all (MySQL treats NULLs as distinct).
        for (AppUserPojo user : allUsers()) {
            assertNotNull(user.getTenantId(), () -> user.getUsername() + " has no tenant");
        }
    }

    // --- helpers -----------------------------------------------------------------

    private TenantPojo tenant(String code) {
        TenantPojo tenant = em.createQuery("SELECT t FROM TenantPojo t WHERE t.code = :code", TenantPojo.class)
                .setParameter("code", code)
                .getResultStream()
                .findFirst()
                .orElse(null);
        assertNotNull(tenant, () -> "tenant '" + code + "' was not seeded");
        return tenant;
    }

    private AppUserPojo user(String tenantCode, String username) {
        AppUserPojo user = em.createQuery(
                        "SELECT u FROM AppUserPojo u JOIN TenantPojo t ON t.id = u.tenantId "
                                + "WHERE t.code = :code AND u.username = :username", AppUserPojo.class)
                .setParameter("code", tenantCode)
                .setParameter("username", username)
                .getResultStream()
                .findFirst()
                .orElse(null);
        assertNotNull(user, () -> tenantCode + "/" + username + " was not seeded");
        return user;
    }

    private List<AppUserPojo> allUsers() {
        return em.createQuery("SELECT u FROM AppUserPojo u", AppUserPojo.class).getResultList();
    }

    private long userCount() {
        return em.createQuery("SELECT count(u) FROM AppUserPojo u", Long.class).getSingleResult();
    }
}

package com.pos.service;

import java.math.BigDecimal;
import java.util.List;

import com.pos.config.AppProperties;
import com.pos.dao.AppUserDao;
import com.pos.dao.ProductDao;
import com.pos.dao.TenantDao;
import com.pos.pojo.AppUser;
import com.pos.pojo.Product;
import com.pos.pojo.Role;
import com.pos.pojo.Tenant;
import com.pos.pojo.TenantStatus;
import com.pos.util.TenantContext;
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
 * <p><b>Tenants, users and the two catalogues.</b> Products joined in C4 out of necessity
 * rather than plan: there is no {@code POST /api/products} until C5, so without seeded
 * rows the tenant filter cannot be exercised by hand at all, and CONVENTIONS.md puts
 * manual testing before automated tests. Variants and orders still wait for C5-C7 —
 * seeding an order here would duplicate the pricing and sequence logic those steps are
 * about to write, and the duplicate would be the one nobody updates.
 *
 * <p>The deliberate collisions are the point and must survive: {@code admin} and
 * {@code cashier} exist in <i>both</i> stores with the same passwords, and both stores
 * stock Bisleri. A schema that rejects either has a global constraint where a per-tenant
 * one belongs, and the isolation suite loses its fixtures.
 */
@Service
public class DevSeeder {

    private static final Logger log = LoggerFactory.getLogger(DevSeeder.class);

    /** One catalogue row, in the order requirements.md section 3 lists the fields. */
    private record CatalogueEntry(String name, String brand, String category,
                                  String hsnCode, int taxRatePercent) {
    }

    /**
     * MG Road's 18 products, copied from {@code frontend/src/mocks/products.js} so the
     * same store looks the same against real persistence and the B6 checklist can be
     * re-run. Variants (34 of them) belong to C5.
     */
    private static final List<CatalogueEntry> MG_ROAD_CATALOGUE = List.of(
            new CatalogueEntry("Amul Taaza Toned Milk", "Amul", "Dairy", "0401", 5),
            new CatalogueEntry("Amul Butter", "Amul", "Dairy", "0405", 12),
            new CatalogueEntry("Lay's Classic Salted", "Lay's", "Snacks", "2005", 12),
            new CatalogueEntry("Coca-Cola", "Coca-Cola", "Beverages", "2202", 28),
            new CatalogueEntry("Maggi 2-Minute Noodles", "Nestlé", "Instant Food", "1902", 12),
            new CatalogueEntry("Tata Salt", "Tata", "Staples", "2501", 0),
            new CatalogueEntry("Colgate MaxFresh Toothpaste", "Colgate", "Personal Care", "3306", 18),
            new CatalogueEntry("Surf Excel Easy Wash Detergent", "Surf Excel", "Household", "3402", 18),
            new CatalogueEntry("Britannia Good Day Cashew Cookies", "Britannia", "Snacks", "1905", 18),
            new CatalogueEntry("Parle-G Biscuits", "Parle", "Snacks", "1905", 18),
            new CatalogueEntry("Nescafé Classic Instant Coffee", "Nestlé", "Beverages", "2101", 18),
            new CatalogueEntry("Red Bull Energy Drink", "Red Bull", "Beverages", "2202", 28),
            new CatalogueEntry("Dettol Original Handwash", "Dettol", "Personal Care", "3401", 18),
            new CatalogueEntry("Kissan Mixed Fruit Jam", "Kissan", "Bakery & Spreads", "2007", 12),
            new CatalogueEntry("Fortune Sunlite Sunflower Oil", "Fortune", "Staples", "1512", 5),
            new CatalogueEntry("Cadbury Dairy Milk", "Cadbury", "Snacks", "1806", 18),
            new CatalogueEntry("Aashirvaad Whole Wheat Atta", "Aashirvaad", "Staples", "1101", 5),
            new CatalogueEntry("Bisleri Packaged Drinking Water", "Bisleri", "Beverages", "2201", 18));

    /**
     * Airport's five, travel-convenience flavoured and deliberately distinct — the
     * difference is what makes a cross-tenant list obvious at a glance rather than
     * something you have to compare ids to notice.
     *
     * <p>Two overlaps are load-bearing rather than sloppy copying. <b>Bisleri appears in
     * both stores</b>, so a search that crossed the boundary would return two rows instead
     * of one; and <b>Travel Essentials exists only here</b>, so a categories list that
     * crossed would contain it. Both are cases in the frontend's {@code isolation.test.js}.
     */
    private static final List<CatalogueEntry> AIRPORT_CATALOGUE = List.of(
            new CatalogueEntry("Bisleri Packaged Drinking Water", "Bisleri", "Beverages", "2201", 18),
            new CatalogueEntry("Haldiram's Aloo Bhujia", "Haldiram's", "Snacks", "2106", 12),
            new CatalogueEntry("Travel Neck Pillow", "Wildcraft", "Travel Essentials", "9404", 18),
            new CatalogueEntry("Starbucks Bottled Frappuccino", "Starbucks", "Beverages", "2202", 18),
            new CatalogueEntry("Kurkure Masala Munch", "Kurkure", "Snacks", "2005", 12));

    @Autowired
    private AppProperties props;

    @Autowired
    private TenantDao tenantDao;

    @Autowired
    private AppUserDao appUserDao;

    @Autowired
    private ProductDao productDao;

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

        seedCatalogue(mgRoad, MG_ROAD_CATALOGUE);
        seedCatalogue(airport, AIRPORT_CATALOGUE);
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

    /**
     * The store's opening catalogue, skipped if it already has one.
     *
     * <p><b>This is the one place outside a request that sets {@link TenantContext}, and it
     * has to.</b> The seeder runs at startup, so there is no request and no tenant on the
     * thread — which means the "does this store already have products?" check would be
     * evaluated against {@code NO_TENANT}, answer "none" every time, and re-insert all 23
     * rows on every boot under {@code hbm2ddl.auto=update}. Setting the context makes the
     * check ask the question it looks like it is asking. {@code seedUser} needs none of
     * this because {@code AppUser} is unfiltered.
     *
     * <p>Cleared in a {@code finally} for the same reason every other caller does, even
     * though the startup thread serves nothing afterwards. The habit is the safeguard; an
     * exception here must not leave a tenant on a thread the container may reuse.
     */
    private void seedCatalogue(Tenant tenant, List<CatalogueEntry> catalogue) {
        TenantContext.set(tenant.getId());
        try {
            if (productDao.count(null, null, true) > 0) {
                return;
            }
            for (CatalogueEntry entry : catalogue) {
                Product product = new Product();
                // Stamped from the tenant being seeded, never from the row's own data --
                // the same rule the API follows, where it comes from the session. The
                // filter does not police inserts; only whoever builds the entity does.
                product.setTenant(tenant);
                product.setName(entry.name());
                product.setBrand(entry.brand());
                product.setCategory(entry.category());
                product.setHsnCode(entry.hsnCode());
                product.setTaxRatePercent(BigDecimal.valueOf(entry.taxRatePercent()));
                product.setActive(true);
                productDao.insert(product);
            }
            log.info("Seeded {} products for tenant {}", catalogue.size(), tenant.getCode());
        } finally {
            TenantContext.clear();
        }
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

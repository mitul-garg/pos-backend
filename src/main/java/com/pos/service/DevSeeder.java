package com.pos.service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.pos.config.AppProperties;
import com.pos.dao.AppUserDao;
import com.pos.dao.OrderDao;
import com.pos.dao.ProductDao;
import com.pos.dao.TenantDao;
import com.pos.dao.VariantDao;
import com.pos.model.OrderData;
import com.pos.model.OrderForm;
import com.pos.model.OrderLineForm;
import com.pos.model.PaymentForm;
import com.pos.model.SessionUserData;
import com.pos.model.VariantForm;
import com.pos.pojo.AppUserPojo;
import com.pos.pojo.enums.PaymentMethod;
import com.pos.pojo.ProductPojo;
import com.pos.pojo.enums.Role;
import com.pos.pojo.TenantPojo;
import com.pos.pojo.enums.TenantStatus;
import com.pos.pojo.VariantPojo;
import com.pos.util.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
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

    /**
     * A page size for the name-to-id lookup below, comfortably above the largest seeded
     * catalogue (18). Not a limit on what a store may hold — nothing else reads it.
     */
    private static final int MAX_SEEDED_PRODUCTS = 500;

    /** One catalogue row, in the order requirements.md section 3 lists the fields. */
    private record CatalogueEntry(String name, String brand, String category,
                                  String hsnCode, int taxRatePercent) {
    }

    /**
     * One variant, keyed to its parent by <b>product name</b> rather than by position.
     * Names are unique within a store's catalogue, and an index would silently re-point
     * every row below it the day a product is inserted in the middle.
     *
     * <p>No {@code qrCode} field: the seeder creates these through {@link VariantService},
     * so the codes come from the store's real sequence rather than from a literal that
     * would drift out of step with it the first time anyone adds a variant by hand.
     */
    private record VariantEntry(String productName, String variantLabel,
                                Map<String, String> attributes, String sku,
                                int mrp, int sellingPrice, int stockQuantity) {
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

    /**
     * MG Road's 34 variants, copied from {@code frontend/src/mocks/products.js}.
     *
     * <p>The coverage is deliberate and worth keeping (requirements.md section 7): products
     * with <b>several variants at different MRPs</b> — the primary case the whole
     * product/variant split exists for — a couple of <b>single-variant</b> products, and an
     * <b>out-of-stock</b> one ({@code LAYS-SALT-90}) so the stock-0 path has a fixture. Two
     * variants of Dettol differ only by {@code attributes}, which is what that column is
     * for.
     */
    private static final List<VariantEntry> MG_ROAD_VARIANTS = List.of(
            v("Amul Taaza Toned Milk", "500 ml", Map.of("size", "500ml"), "AMUL-MILK-500", 30, 29, 40),
            v("Amul Taaza Toned Milk", "1 L", Map.of("size", "1L"), "AMUL-MILK-1000", 58, 56, 25),
            v("Amul Butter", "100 g", Map.of("size", "100g"), "AMUL-BTR-100", 56, 55, 30),
            v("Amul Butter", "500 g", Map.of("size", "500g"), "AMUL-BTR-500", 265, 258, 12),
            v("Lay's Classic Salted", "52 g", Map.of("size", "52g"), "LAYS-SALT-52", 20, 20, 60),
            // Out of stock on purpose: still findable and still scannable, not sellable.
            v("Lay's Classic Salted", "90 g", Map.of("size", "90g"), "LAYS-SALT-90", 33, 32, 0),
            v("Coca-Cola", "750 ml", Map.of("size", "750ml"), "COKE-750", 40, 40, 50),
            v("Coca-Cola", "1.25 L", Map.of("size", "1.25L"), "COKE-1250", 65, 63, 20),
            v("Coca-Cola", "2 L", Map.of("size", "2L"), "COKE-2000", 95, 92, 15),
            v("Maggi 2-Minute Noodles", "70 g (single)", Map.of("pack", "single"), "MAGGI-70", 14, 14, 100),
            v("Maggi 2-Minute Noodles", "280 g (4-pack)", Map.of("pack", "4-pack"), "MAGGI-280", 56, 55, 40),
            v("Tata Salt", "1 kg", Map.of("size", "1kg"), "TATA-SALT-1KG", 28, 27, 35),
            v("Colgate MaxFresh Toothpaste", "100 g", Map.of("size", "100g"), "COLG-MAXF-100", 95, 90, 22),
            v("Colgate MaxFresh Toothpaste", "150 g", Map.of("size", "150g"), "COLG-MAXF-150", 135, 128, 18),
            v("Surf Excel Easy Wash Detergent", "500 g", Map.of("size", "500g"), "SURF-EW-500", 70, 68, 28),
            v("Surf Excel Easy Wash Detergent", "1 kg", Map.of("size", "1kg"), "SURF-EW-1KG", 135, 130, 14),
            v("Britannia Good Day Cashew Cookies", "100 g", Map.of("size", "100g"), "GOODDAY-CSHW-100", 30, 29, 45),
            v("Parle-G Biscuits", "70 g", Map.of("size", "70g"), "PARLEG-70", 10, 10, 120),
            v("Parle-G Biscuits", "250 g", Map.of("size", "250g"), "PARLEG-250", 30, 29, 50),
            v("Nescafé Classic Instant Coffee", "50 g jar", Map.of("size", "50g"), "NESC-CLSC-50", 175, 168, 16),
            v("Nescafé Classic Instant Coffee", "100 g jar", Map.of("size", "100g"), "NESC-CLSC-100", 320, 305, 10),
            v("Red Bull Energy Drink", "250 ml", Map.of("size", "250ml"), "REDBULL-250", 125, 125, 24),
            v("Dettol Original Handwash", "200 ml refill", Map.of("size", "200ml", "pack", "refill"), "DETTOL-HW-RF200", 99, 95, 30),
            v("Dettol Original Handwash", "200 ml pump", Map.of("size", "200ml", "pack", "pump"), "DETTOL-HW-PM200", 120, 115, 20),
            v("Kissan Mixed Fruit Jam", "200 g", Map.of("size", "200g"), "KISSAN-JAM-200", 85, 82, 18),
            v("Kissan Mixed Fruit Jam", "500 g", Map.of("size", "500g"), "KISSAN-JAM-500", 190, 182, 9),
            v("Fortune Sunlite Sunflower Oil", "1 L pouch", Map.of("size", "1L", "pack", "pouch"), "FORT-SFO-1L", 145, 140, 26),
            v("Fortune Sunlite Sunflower Oil", "5 L can", Map.of("size", "5L", "pack", "can"), "FORT-SFO-5L", 700, 685, 8),
            v("Cadbury Dairy Milk", "13.2 g", Map.of("size", "13.2g"), "CDM-13", 10, 10, 150),
            v("Cadbury Dairy Milk", "55 g", Map.of("size", "55g"), "CDM-55", 45, 44, 40),
            v("Cadbury Dairy Milk", "110 g", Map.of("size", "110g"), "CDM-110", 95, 92, 25),
            v("Aashirvaad Whole Wheat Atta", "5 kg", Map.of("size", "5kg"), "AASH-ATTA-5KG", 265, 258, 20),
            v("Bisleri Packaged Drinking Water", "1 L", Map.of("size", "1L"), "BISLERI-1L", 20, 20, 80),
            v("Bisleri Packaged Drinking Water", "500 ml", Map.of("size", "500ml"), "BISLERI-500", 10, 10, 100));

    /**
     * Airport's six.
     *
     * <p><b>{@code BISLERI-1L} is deliberately the same SKU MG Road uses</b>, and it has to
     * keep working: uniqueness is {@code (tenant_id, sku)}, so a schema that rejects this
     * has a global constraint where a per-tenant one belongs. It is a fixture for the
     * isolation suite, not a copy-paste slip.
     */
    private static final List<VariantEntry> AIRPORT_VARIANTS = List.of(
            v("Bisleri Packaged Drinking Water", "1 L", Map.of("size", "1L"), "BISLERI-1L", 20, 20, 60),
            v("Haldiram's Aloo Bhujia", "150 g", Map.of("size", "150g"), "HALD-BHUJIA-150", 55, 55, 30),
            v("Haldiram's Aloo Bhujia", "400 g", Map.of("size", "400g"), "HALD-BHUJIA-400", 135, 132, 12),
            v("Travel Neck Pillow", "One size", Map.of("size", "standard"), "TRVL-PILLOW-STD", 599, 549, 15),
            v("Starbucks Bottled Frappuccino", "281 ml", Map.of("size", "281ml"), "SBUX-FRAP-281", 250, 250, 18),
            v("Kurkure Masala Munch", "90 g", Map.of("size", "90g"), "KURKURE-90", 20, 20, 0));

    private static VariantEntry v(String productName, String variantLabel,
                                  Map<String, String> attributes, String sku,
                                  int mrp, int sellingPrice, int stockQuantity) {
        return new VariantEntry(productName, variantLabel, attributes, sku,
                mrp, sellingPrice, stockQuantity);
    }

    /** One requested line of a seeded order, keyed by SKU rather than a not-yet-issued id. */
    private record OrderLineSeed(String sku, int quantity) {
    }

    /**
     * One completed sale, run through {@link OrderService#create} and
     * {@link PaymentService#pay} exactly as a real checkout would (C6) — see
     * {@link #seedOrders} for why. {@code createdAt} is not here: {@code PosOrderPojo} stamps
     * it with {@code @CreationTimestamp}, so a seeded order is dated the moment the app
     * booted rather than the mock's back-dated July timestamps. Nothing downstream reads
     * seeded orders for their age, so that is a difference worth knowing rather than
     * worth fixing.
     */
    private record OrderSeed(PaymentMethod method, BigDecimal amountTendered, String reference,
                             List<OrderLineSeed> lines) {
    }

    private static OrderLineSeed line(String sku, int quantity) {
        return new OrderLineSeed(sku, quantity);
    }

    private static OrderSeed cashOrder(BigDecimal amountTendered, OrderLineSeed... lines) {
        return new OrderSeed(PaymentMethod.CASH, amountTendered, null, List.of(lines));
    }

    private static OrderSeed cardOrUpiOrder(PaymentMethod method, String reference, OrderLineSeed... lines) {
        return new OrderSeed(method, null, reference, List.of(lines));
    }

    /**
     * MG Road's two, copied from {@code frontend/src/mocks/orders.js} (same SKUs, same
     * quantities, same payment) so the same two sales exist against real persistence.
     */
    private static final List<OrderSeed> MG_ROAD_ORDERS = List.of(
            cashOrder(BigDecimal.valueOf(200),
                    line("AMUL-MILK-500", 2), line("LAYS-SALT-52", 1), line("PARLEG-70", 3)),
            cardOrUpiOrder(PaymentMethod.CARD, "TXN-CARD-88213",
                    line("COKE-750", 2), line("COLG-MAXF-100", 1), line("CDM-55", 2)));

    /**
     * Airport's one. Reuses {@code ORD-2026-0001} against MG Road's own first order number
     * purely by both stores starting their sequence at 1 — the collision is legal
     * ({@code UNIQUE(tenant_id, order_number)}) and is the isolation suite's fixture, not
     * an accident to fix.
     */
    private static final List<OrderSeed> AIRPORT_ORDERS = List.of(
            cardOrUpiOrder(PaymentMethod.UPI, "UPI-4471029",
                    line("BISLERI-1L", 2), line("SBUX-FRAP-281", 1)));

    @Autowired
    private AppProperties props;

    @Autowired
    private TenantDao tenantDao;

    @Autowired
    private AppUserDao appUserDao;

    @Autowired
    private ProductDao productDao;

    @Autowired
    private VariantDao variantDao;

    @Autowired
    private VariantService variantService;

    @Autowired
    private OrderDao orderDao;

    @Autowired
    private OrderService orderService;

    @Autowired
    private PaymentService paymentService;

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
        TenantPojo platform = seedTenant("Platform", TenantPojo.PLATFORM_CODE, true);
        TenantPojo mgRoad = seedTenant("MG Road Store", "mg-road", false);
        TenantPojo airport = seedTenant("Airport Store", "airport", false);

        seedUser(platform, "superadmin", "super123", "Platform Admin", Role.SUPER_ADMIN);

        // Usernames repeat across the two stores on purpose -- see the class comment.
        seedUser(mgRoad, "admin", "admin123", "MG Road Admin", Role.ADMIN);
        seedUser(mgRoad, "cashier", "cashier123", "MG Road Cashier", Role.CASHIER);
        seedUser(airport, "admin", "admin123", "Airport Admin", Role.ADMIN);
        seedUser(airport, "cashier", "cashier123", "Airport Cashier", Role.CASHIER);

        seedCatalogue(mgRoad, MG_ROAD_CATALOGUE, MG_ROAD_VARIANTS);
        seedCatalogue(airport, AIRPORT_CATALOGUE, AIRPORT_VARIANTS);

        // Both stores' variants have to exist before an order can reference one by SKU,
        // hence a separate pass after both catalogues rather than folded into
        // seedCatalogue.
        seedOrders(mgRoad, appUserDao.findByTenantAndUsername(mgRoad.getId(), "cashier"),
                MG_ROAD_ORDERS);
        seedOrders(airport, appUserDao.findByTenantAndUsername(airport.getId(), "cashier"),
                AIRPORT_ORDERS);
    }

    private TenantPojo seedTenant(String name, String code, boolean platform) {
        TenantPojo existing = tenantDao.findByCode(code);
        if (existing != null) {
            return existing;
        }
        TenantPojo tenant = new TenantPojo();
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
     * this because {@code AppUserPojo} is unfiltered.
     *
     * <p>Cleared in a {@code finally} for the same reason every other caller does, even
     * though the startup thread serves nothing afterwards. The habit is the safeguard; an
     * exception here must not leave a tenant on a thread the container may reuse.
     */
    private void seedCatalogue(TenantPojo tenant, List<CatalogueEntry> catalogue,
                               List<VariantEntry> variants) {
        TenantContext.set(tenant.getId());
        try {
            if (productDao.count(null, null, true) == 0) {
                for (CatalogueEntry entry : catalogue) {
                    ProductPojo product = new ProductPojo();
                    // Stamped from the tenant being seeded, never from the row's own data
                    // -- the same rule the API follows, where it comes from the session.
                    // The filter does not police inserts; only whoever builds the entity
                    // does.
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
            }

            seedVariants(tenant, variants);
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * <b>Gated per variant rather than on "does this store have products?"</b>, and that
     * is not fussiness. C4 seeded the two catalogues and C5 added the variants, so every
     * database that already ran the previous version has products and no variants — under
     * a single products-shaped guard those would never appear, and the fix would be
     * "drop your dev database", which is a bad answer to give someone mid-feature.
     *
     * <p>The check is the SKU, because that is what the schema makes unique within a
     * store. Idempotent at the row level: a variant deleted by hand comes back on the next
     * boot, and one that is merely edited is left exactly as it is.
     */
    private void seedVariants(TenantPojo tenant, List<VariantEntry> variants) {
        Map<String, Long> productIds = new HashMap<>();
        for (ProductPojo product : productDao.list(null, null, true, 0, MAX_SEEDED_PRODUCTS)) {
            productIds.put(product.getName(), product.getId());
        }

        int created = 0;
        for (VariantEntry entry : variants) {
            if (variantDao.skuExists(entry.sku(), null)) {
                continue;
            }
            Long productId = productIds.get(entry.productName());
            if (productId == null) {
                // A typo in the seed data, caught here rather than as a puzzling missing
                // row later. DevSeederIT counts both lists, so this should never ship --
                // the message is what makes it a two-second fix if it does.
                throw new IllegalStateException(
                        "Seed variant names an unknown product: " + entry.productName());
            }
            variantService.create(productId, toForm(entry));
            created++;
        }

        if (created > 0) {
            log.info("Seeded {} variants for tenant {}", created, tenant.getCode());
        }
    }

    /**
     * <b>Seeded through {@link VariantService} rather than through a DAO, deliberately.</b>
     *
     * <p>Everything that makes a variant a variant is generated: the QR payload comes from
     * the store's own sequence and the row is validated on the way in. Inserting these by
     * hand would mean either duplicating that logic — where the duplicate is the copy
     * nobody updates — or writing literal codes that drift out of step with the sequence
     * the moment anyone adds a variant through the API.
     *
     * <p>It also means the seeded rows are, by construction, exactly what the endpoint
     * produces. A bug in {@code create} shows up as broken seed data at startup, which is
     * a good place to find one.
     */
    private VariantForm toForm(VariantEntry entry) {
        VariantForm form = new VariantForm();
        form.setVariantLabel(entry.variantLabel());
        form.setAttributes(entry.attributes());
        form.setSku(entry.sku());
        form.setMrp(BigDecimal.valueOf(entry.mrp()));
        form.setSellingPrice(BigDecimal.valueOf(entry.sellingPrice()));
        form.setStockQuantity(entry.stockQuantity());
        return form;
    }

    /**
     * The store's opening sales, run through {@link OrderService#create} and
     * {@link PaymentService#pay} rather than inserted directly (C6) — the same choice
     * {@link #seedVariants} made and for the same reason: an order's number comes from
     * the tenant's own sequence and its totals from {@link com.pos.util.Pricing}, and
     * seeding through the real endpoints means a bug in either shows up as broken seed
     * data at startup rather than as a silently-diverging fixture. It also means the stock
     * these sales ring up is genuinely decremented, exactly as a real till would leave it.
     *
     * <p><b>Both services read the caller from {@code AuthService.currentSession()}</b>,
     * which reads {@code SecurityContextHolder}, not a parameter — there being no request
     * to derive it from is exactly the problem {@link TenantContext#set} solves for
     * tenancy, and this is the identical problem one layer up. The fix is the identical
     * shape: build the {@code Authentication} {@link JwtAuthenticationFilter} would have
     * built for this cashier, install it for the duration of this method, and clear it in
     * a {@code finally} — {@link SecurityContextHolder} is a {@code ThreadLocal} exactly
     * like {@link TenantContext}, so the same leak concern applies even though this
     * startup thread will not go on to serve a pooled request.
     *
     * <p>Gated on {@link OrderDao#count}, tenant-scoped and therefore already inside the
     * {@link TenantContext} this method sets — idempotent the same way
     * {@link #seedCatalogue}'s product check is, and for the same reason: re-running
     * {@code seed()} against a database that already has this store's orders must not
     * mint a second set with new, higher numbers.
     */
    private void seedOrders(TenantPojo tenant, AppUserPojo cashier, List<OrderSeed> orders) {
        TenantContext.set(tenant.getId());
        SecurityContextHolder.getContext().setAuthentication(authenticationFor(cashier, tenant));
        try {
            if (orderDao.count(null, null) > 0) {
                return;
            }
            for (OrderSeed seed : orders) {
                List<OrderLineForm> items = seed.lines().stream()
                        .map(this::orderLineForm)
                        .toList();

                OrderForm createForm = new OrderForm();
                createForm.setItems(items);
                OrderData order = orderService.create(createForm);

                PaymentForm paymentForm = new PaymentForm();
                paymentForm.setMethod(seed.method());
                paymentForm.setAmountTendered(seed.amountTendered());
                paymentForm.setReference(seed.reference());
                paymentService.pay(order.getId(), paymentForm);
            }
            log.info("Seeded {} orders for tenant {}", orders.size(), tenant.getCode());
        } finally {
            SecurityContextHolder.clearContext();
            TenantContext.clear();
        }
    }

    private OrderLineForm orderLineForm(OrderLineSeed seedLine) {
        VariantPojo variant = variantDao.findBySku(seedLine.sku());
        if (variant == null) {
            // A typo in the seed data -- see seedVariants' identical guard for why this
            // is the right place to fail rather than leaving a puzzling gap in the order.
            throw new IllegalStateException(
                    "Seed order names an unknown SKU: " + seedLine.sku());
        }
        OrderLineForm form = new OrderLineForm();
        form.setVariantId(variant.getId());
        form.setQuantity(seedLine.quantity());
        return form;
    }

    /**
     * The {@code Authentication} {@code JwtAuthenticationFilter} would have built for a
     * request bearing this cashier's token — same principal shape
     * ({@code SessionUserData}), same {@code ROLE_*} authority Spring Security's
     * {@code hasRole} looks for. Built here rather than reused from {@code AuthService}
     * because there is no token to resolve; the {@code AppUserPojo} row (already in hand from
     * seeding) is the input instead.
     */
    private Authentication authenticationFor(AppUserPojo cashier, TenantPojo tenant) {
        SessionUserData session = new SessionUserData(
                cashier.getId(), tenant.getId(), cashier.getUsername(), cashier.getDisplayName(),
                cashier.getRole(), cashier.isActive(), tenant.getCode(), tenant.getName());
        List<GrantedAuthority> authorities =
                List.of(new SimpleGrantedAuthority("ROLE_" + cashier.getRole().name()));
        return UsernamePasswordAuthenticationToken.authenticated(session, null, authorities);
    }

    private void seedUser(TenantPojo tenant, String username, String password,
                          String displayName, Role role) {
        if (appUserDao.findByTenantAndUsername(tenant.getId(), username) != null) {
            return;
        }
        AppUserPojo user = new AppUserPojo();
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

package com.pos.pojo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import javax.sql.DataSource;

import com.pos.config.PersistenceConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Asserts, against a real MySQL, that the constraints isolation depends on actually
 * materialised.
 *
 * <p><b>Why this test exists at all.</b> {@code hbm2ddl.auto=validate} checks tables and
 * columns only — never indexes or unique keys — so nothing in the stack will ever tell
 * you a unique key failed to be created. There is no {@code .sql} file a reviewer would
 * notice it missing from either, because the schema comes from annotations. Without
 * these keys, isolation still <i>appears</i> to work in every manual check and only
 * breaks under concurrency or on the first duplicate, long after anyone would connect
 * the symptom to a schema problem.
 *
 * <p>It is deliberately a statement about the <b>database</b>, not about the entities —
 * {@code SchemaSqlTest} already covers the entities, offline. This one runs the DDL
 * through MySQL and reads back what MySQL actually built.
 *
 * <p>The schema is guaranteed to exist before any test below runs, and nothing here has
 * to arrange that: refreshing the Spring context eagerly instantiates every singleton,
 * including the entity manager factory, and starting that is what executes the
 * {@code create-drop} DDL.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = PersistenceConfig.class)
@TestPropertySource("classpath:application-test.properties")
@DisplayName("The schema MySQL actually built")
class SchemaConstraintsIT {

    /** Every table the entities should have produced. */
    private static final List<String> EXPECTED_TABLES = List.of(
            "app_user", "order_line", "pos_order", "product", "return_line",
            "sales_return", "tenant", "tenant_sequence", "variant");

    /** Queried directly rather than through JPA — the subject here is the schema, not the mapping. */
    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("has every table, so a missed entity cannot go unnoticed")
    void createsEveryTable() throws Exception {
        List<String> actual = readTableNames();

        // Distinguishes the two ways the assertions below can fail. Without this, an
        // entity that Hibernate never picked up -- one placed outside com.pos.pojo, say,
        // which PersistenceConfig scans -- shows up as "uk_variant_tenant_sku does not
        // exist", pointing at the constraint rather than at the missing table.
        for (String table : EXPECTED_TABLES) {
            assertTrue(actual.contains(table),
                    () -> table + " was never created. Tables found: " + actual
                            + ". An entity outside com.pos.pojo is not scanned.");
        }
    }

    /**
     * The six keys from prompts/database/constraints-and-indexes.md, with the columns
     * each must cover <b>in order</b> — a composite key's column order decides which
     * prefix lookups it can serve, so {@code (username, tenant_id)} would be a different
     * key that happens to have the same name.
     */
    static Stream<Arguments> expectedUniqueKeys() {
        return Stream.of(
                // The one globally-unique field in the model: the login discriminator.
                Arguments.of("uk_tenant_code", "tenant", List.of("code")),
                // Two stores can each have an `admin`. Global uniqueness here is wrong.
                Arguments.of("uk_user_tenant_username", "app_user", List.of("tenant_id", "username")),
                // The seeds use BISLERI-1L in both tenants deliberately.
                Arguments.of("uk_variant_tenant_sku", "variant", List.of("tenant_id", "sku")),
                Arguments.of("uk_variant_tenant_qrcode", "variant", List.of("tenant_id", "qr_code")),
                // Both stores have an ORD-2026-0001.
                Arguments.of("uk_order_tenant_number", "pos_order", List.of("tenant_id", "order_number")),
                Arguments.of("uk_return_tenant_number", "sales_return", List.of("tenant_id", "return_number"))
        );
    }

    @ParameterizedTest(name = "{0} on {1} covers {2}")
    @MethodSource("expectedUniqueKeys")
    @DisplayName("has the unique key")
    void hasUniqueKey(String name, String table, List<String> columns) throws Exception {
        Map<String, KeyDefinition> actual = readUniqueKeys();

        KeyDefinition key = actual.get(name);
        assertNotNull(key, () -> name + " does not exist. Unique keys found: " + actual.keySet()
                + ". Declare it in the entity's @Table(uniqueConstraints = ...) -- nothing "
                + "else creates it, and validate mode will not notice it is missing.");
        assertEquals(table, key.table(), () -> name + " is on the wrong table");
        assertEquals(columns, key.columns(), () -> name + " covers the wrong columns, or "
                + "covers them in the wrong order");
    }

    @Test
    @DisplayName("enforces uniqueness per tenant, never globally")
    void scopesUniquenessToTheTenant() throws Exception {
        Map<String, KeyDefinition> keys = readUniqueKeys();

        // The inverse of the cases above, stated once as a rule rather than six times as
        // examples: every unique key except the tenant code must lead with tenant_id.
        // A global constraint where a per-tenant one belongs is exactly the mistake that
        // makes the seed data fail to load -- two stores legitimately share usernames,
        // SKUs and order numbers.
        keys.forEach((name, key) -> {
            if (name.equals("uk_tenant_code")) {
                return;
            }
            assertEquals("tenant_id", key.columns().get(0),
                    () -> name + " does not lead with tenant_id, so it constrains across "
                            + "tenants rather than within one");
        });
    }

    @Test
    @DisplayName("has the check constraints, which need MySQL 8.0.16+ to exist at all")
    void hasCheckConstraints() throws Exception {
        List<String> names = readCheckConstraintNames();

        // Below 8.0.16 MySQL parses CHECK and silently ignores it, so this doubles as a
        // statement about the server the suite is running against. It is also why the
        // application-level validation of these same rules is not redundant.
        assertTrue(names.contains("ck_variant_price_within_mrp"),
                () -> "the MRP ceiling check is missing. Found: " + names);
        assertTrue(names.contains("ck_variant_stock_not_negative"),
                () -> "the non-negative stock check is missing. Found: " + names);
        assertTrue(names.contains("ck_order_line_quantity_positive"),
                () -> "the positive line quantity check is missing. Found: " + names);
    }

    /** A unique key as MySQL reports it: which table, and which columns in which order. */
    private record KeyDefinition(String table, List<String> columns) { }

    private Map<String, KeyDefinition> readUniqueKeys() throws Exception {
        String sql = """
                SELECT tc.CONSTRAINT_NAME, tc.TABLE_NAME, kcu.COLUMN_NAME
                  FROM information_schema.TABLE_CONSTRAINTS tc
                  JOIN information_schema.KEY_COLUMN_USAGE kcu
                    ON kcu.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA
                   AND kcu.CONSTRAINT_NAME   = tc.CONSTRAINT_NAME
                   AND kcu.TABLE_NAME        = tc.TABLE_NAME
                 WHERE tc.CONSTRAINT_SCHEMA = DATABASE()
                   AND tc.CONSTRAINT_TYPE   = 'UNIQUE'
                 ORDER BY tc.CONSTRAINT_NAME, kcu.ORDINAL_POSITION
                """;

        Map<String, KeyDefinition> keys = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {

            while (rows.next()) {
                String name = rows.getString("CONSTRAINT_NAME");
                String table = rows.getString("TABLE_NAME");
                String column = rows.getString("COLUMN_NAME");

                KeyDefinition existing = keys.get(name);
                if (existing == null) {
                    List<String> columns = new ArrayList<>();
                    columns.add(column);
                    keys.put(name, new KeyDefinition(table, columns));
                } else {
                    existing.columns().add(column);
                }
            }
        }
        return keys;
    }

    private List<String> readTableNames() throws Exception {
        String sql = """
                SELECT TABLE_NAME
                  FROM information_schema.TABLES
                 WHERE TABLE_SCHEMA = DATABASE()
                   AND TABLE_TYPE   = 'BASE TABLE'
                """;

        List<String> names = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {

            while (rows.next()) {
                names.add(rows.getString("TABLE_NAME"));
            }
        }
        return names;
    }

    private List<String> readCheckConstraintNames() throws Exception {
        String sql = """
                SELECT CONSTRAINT_NAME
                  FROM information_schema.CHECK_CONSTRAINTS
                 WHERE CONSTRAINT_SCHEMA = DATABASE()
                """;

        List<String> names = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {

            while (rows.next()) {
                names.add(rows.getString("CONSTRAINT_NAME"));
            }
        }
        return names;
    }
}

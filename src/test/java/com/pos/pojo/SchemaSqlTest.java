package com.pos.pojo;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.persistence.Entity;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.MappingSettings;
import org.hibernate.dialect.DatabaseVersion;
import org.hibernate.dialect.MySQLDialect;
import org.hibernate.tool.schema.spi.SchemaManagementToolCoordinator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regenerates the DDL from the entities and asserts the committed {@code schema.sql}
 * still matches it.
 *
 * <p>The schema is generated from annotations and there is no migration tool, so
 * {@code schema.sql} is the only place a DDL change shows up in a diff and gets
 * reviewed. A committed file nobody regenerates is worse than no file at all — it
 * describes a schema that has not existed for weeks — so this test makes drift a build
 * failure rather than a habit.
 *
 * <p><b>To update it after changing an entity:</b>
 * {@code mvn test -Dpos.schema.write=true}, then commit the result alongside the entity
 * and the {@code prompts/database/} change.
 *
 * <p>Needs no database: Hibernate generates DDL offline from a pinned dialect. That
 * matters for more than speed — it means the committed file is a property of the
 * entities, not of whichever MySQL the developer happened to have running.
 */
@DisplayName("The committed schema.sql")
class SchemaSqlTest {

    /**
     * Relative to the Maven basedir ({@code backend/}). Kept out of
     * {@code src/main/resources} deliberately: hbm2ddl creates the schema at runtime, so
     * shipping this inside the war would imply it is executed, which it never is.
     */
    private static final Path SCHEMA_SQL = Path.of("schema.sql");

    /** Set {@code -Dpos.schema.write=true} to rewrite the file instead of asserting. */
    private static final String WRITE_PROPERTY = "pos.schema.write";

    /**
     * The floor the database documentation states, not the version any one machine runs.
     *
     * <p>Pinning matters: {@code MySQLDialect} emits {@code CHECK} constraints only from
     * 8.0.16, and a dialect constructed without a version falls back to Hibernate's
     * minimum supported MySQL (5.7) — which would silently drop every check constraint
     * from this file. {@code emitsCheckConstraints()} below is the guard that the pin
     * is doing its job.
     */
    private static final DatabaseVersion MINIMUM_MYSQL = DatabaseVersion.make(8, 0, 16);

    @Test
    @DisplayName("matches the entities -- regenerate with -Dpos.schema.write=true")
    void matchesTheEntities() throws Exception {
        String generated = generateDdl();

        if (Boolean.getBoolean(WRITE_PROPERTY)) {
            Files.writeString(SCHEMA_SQL, generated);
            return;
        }

        assertTrue(Files.exists(SCHEMA_SQL), SCHEMA_SQL + " is missing -- regenerate it");
        assertEquals(
                Files.readString(SCHEMA_SQL),
                generated,
                "schema.sql is out of date with the entities. Regenerate it with "
                        + "`mvn test -D" + WRITE_PROPERTY + "=true` and commit the result "
                        + "together with the entity change and the prompts/database/ update."
        );
    }

    @Test
    @DisplayName("contains the check constraints, proving the dialect version pin works")
    void emitsCheckConstraints() throws Exception {
        String ddl = generateDdl();

        // If the version pin ever breaks, the dialect silently reverts to MySQL 5.7 and
        // these vanish without any error. That failure is invisible in every other test,
        // because the application-level validation still rejects the same values -- it
        // only shows up as a missing backstop years later.
        assertTrue(ddl.contains("selling_price<=mrp") || ddl.contains("selling_price <= mrp"),
                "variant's MRP ceiling check is missing from the generated DDL");
        assertTrue(ddl.contains("stock_quantity>=0") || ddl.contains("stock_quantity >= 0"),
                "variant's non-negative stock check is missing from the generated DDL");
        assertTrue(ddl.contains("quantity>0") || ddl.contains("quantity > 0"),
                "order_line's positive quantity check is missing from the generated DDL");
    }

    @Test
    @DisplayName("stores enums as varchar, never as MySQL's native ENUM")
    void storesEnumsAsVarchar() throws Exception {
        String ddl = generateDdl();

        // Hibernate 6 emits MySQL's native ENUM for @Enumerated(STRING) unless every
        // field says otherwise, and the first generated schema here did exactly that.
        // It matters because adding a value to a MySQL ENUM is a table alter, and
        // hbm2ddl.auto=update does not perform alters -- so the day a fourth payment
        // method is added, the column silently keeps rejecting it.
        assertFalse(ddl.contains("enum ("),
                "an enum field is mapped to MySQL's native ENUM type. Add "
                        + "@JdbcTypeCode(SqlTypes.VARCHAR) to it -- see "
                        + "prompts/database/README.md.");
        assertTrue(ddl.contains("role varchar(16) not null"), "role should be varchar(16)");
        assertTrue(ddl.contains("status varchar(16) not null"), "status should be varchar(16)");
    }

    @Test
    @DisplayName("stores booleans as tinyint, which is what MySQL's BOOLEAN means")
    void storesBooleansAsTinyint() throws Exception {
        String ddl = generateDdl();

        // Hibernate defaults to `bit` on MySQL. Functionally close, but BOOLEAN in
        // MySQL is an alias for TINYINT(1), and that is what the database docs describe.
        assertFalse(ddl.contains(" bit "), "a boolean column was emitted as `bit`");
        assertTrue(ddl.contains("is_active tinyint default true not null"),
                "is_active should be a tinyint with an explicit default");
    }

    @Test
    @DisplayName("names every column in snake_case, catching a forgotten @Column")
    void usesSnakeCaseColumnNames() throws Exception {
        String ddl = generateDdl();

        // Hibernate leaves camelCase alone unless a naming strategy says otherwise, and
        // without Boot nothing installs one by default. This catches the case where both
        // the strategy AND an explicit @Column go missing at once.
        assertFalse(ddl.contains("variantLabel"), "variant_label leaked as camelCase");
        assertFalse(ddl.contains("taxRatePercent"), "tax_rate_percent leaked as camelCase");
        assertFalse(ddl.contains("passwordHash"), "password_hash leaked as camelCase");
    }

    private String generateDdl() throws Exception {
        File target = File.createTempFile("pos-schema", ".sql");
        target.deleteOnExit();

        Map<String, Object> settings = new HashMap<>();

        // A Dialect INSTANCE, not a class name: naming the class would let Hibernate
        // default the version, which is the trap described above.
        settings.put(AvailableSettings.DIALECT, new MySQLDialect(MINIMUM_MYSQL));

        // Must mirror PersistenceConfig, or the generated file would describe a schema
        // the running application never creates.
        settings.put(AvailableSettings.PHYSICAL_NAMING_STRATEGY,
                "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy");
        settings.put(MappingSettings.PREFERRED_BOOLEAN_JDBC_TYPE, "TINYINT");

        // No DataSource is configured. Without this Hibernate tries to read JDBC
        // metadata and warns at length about being unable to.
        settings.put("hibernate.boot.allow_jdbc_metadata_access", "false");

        // Write the CREATE script to a file and execute nothing. These are the Jakarta
        // Persistence script-generation settings -- the supported route in Hibernate 6,
        // which dropped the SchemaExport class that did this in 5.x.
        settings.put("jakarta.persistence.schema-generation.scripts.action", "create");
        settings.put("jakarta.persistence.schema-generation.scripts.create-target",
                target.getAbsolutePath());
        settings.put("hibernate.hbm2ddl.delimiter", ";");
        settings.put(AvailableSettings.FORMAT_SQL, "true");

        StandardServiceRegistry registry =
                new StandardServiceRegistryBuilder().applySettings(settings).build();
        try {
            MetadataSources sources = new MetadataSources(registry);
            for (Class<?> entity : entityClasses()) {
                sources.addAnnotatedClass(entity);
            }
            Metadata metadata = sources.buildMetadata();

            // The last argument registers a drop action to run at shutdown. Nothing is
            // executed here, so it is deliberately a no-op.
            SchemaManagementToolCoordinator.process(metadata, registry, settings, action -> { });

            return Files.readString(target.toPath());
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    /**
     * Scans for entities rather than listing them, so a new entity cannot be added
     * without appearing in {@code schema.sql}. Sorted, because the export writes tables
     * in registration order and an unstable order would make the file churn between runs.
     */
    private List<Class<?>> entityClasses() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));

        List<Class<?>> classes = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents("com.pos.pojo")) {
            classes.add(Class.forName(definition.getBeanClassName()));
        }
        classes.sort(Comparator.comparing(Class::getName));
        return classes;
    }
}

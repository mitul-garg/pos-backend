package com.pos.config;

import java.sql.Connection;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The first test that needs a database (C2). It proves the persistence wiring end to
 * end: properties resolve, Hikari builds a pool, the driver reaches MySQL, and the
 * entity manager factory starts.
 *
 * <p>Worth having as its own suite because when it fails, the cause is environmental —
 * MySQL down, wrong credentials — and every other IT will fail alongside it for reasons
 * that look like application bugs. One small test that says "the database is the
 * problem" saves reading the other stack traces.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = PersistenceConfig.class)
@TestPropertySource("classpath:application-test.properties")
@DisplayName("PersistenceConfig against a real MySQL")
class PersistenceConfigIT {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    @DisplayName("connects to pos_test, creating it if the machine has never run this before")
    void connectsToTheTestDatabase() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertTrue(connection.isValid(2), "connection should be usable");
            // getCatalog() is the MySQL database name. Asserting it is pos_test is the
            // guard that matters: create-drop drops every table on each run, so a
            // misconfigured URL pointing at pos_dev would silently destroy the seed
            // data instead of failing.
            assertEquals("pos_test", connection.getCatalog(),
                    "tests must never run against the dev database");
        }
    }

    @Test
    @DisplayName("pool size comes from configuration, not from Hikari's default of 10")
    void poolSizeIsSetExplicitly() {
        HikariDataSource hikari = (HikariDataSource) dataSource;
        // application-test.properties asks for 2. The value matters less than the fact
        // that it is ours: inheriting Hikari's default is what exhausts a free-tier
        // MySQL's connection cap (backend-plan.md section 3).
        assertEquals(2, hikari.getMaximumPoolSize());
    }

    @Test
    @DisplayName("the entity manager factory starts")
    void entityManagerFactoryStarts() {
        assertNotNull(entityManagerFactory);
        // No entities exist yet -- they land in the next commits of C2. This asserts
        // the factory itself is live, which is the part this commit is responsible for.
        assertTrue(entityManagerFactory.isOpen());
    }
}

package com.pos.config;

import java.util.Properties;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.MappingSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.PropertySource;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Persistence wiring: the connection pool, the JPA provider and the transaction
 * manager. Everything Spring Boot would auto-configure, written out (C2).
 *
 * <p>A <b>root</b>-context config — see {@code WebAppInitializer}. Services own
 * {@code @Transactional} and live in the root context, so the transaction manager has
 * to be visible there; a transaction manager in the servlet context would leave every
 * service method silently non-transactional.
 */
@Configuration
@EnableTransactionManagement
// Two sources, and the ORDER is the point: a later @PropertySource wins, so the
// gitignored application-local.properties overrides the committed defaults. That is
// how a machine-specific credential stays out of the repository (CONVENTIONS.md:
// never commit a secret) without anyone needing to remember to set an env var.
// ignoreResourceNotFound, because CI and deployed environments have no local file --
// they set POS_DB_* instead, which the placeholders already read.
@PropertySource("classpath:application.properties")
@PropertySource(value = "classpath:application-local.properties", ignoreResourceNotFound = true)
@Import(AppProperties.class)
public class PersistenceConfig {

    /** Only {@code com.pos.pojo} holds entities — CONVENTIONS.md, one package per layer. */
    private static final String ENTITY_PACKAGE = "com.pos.pojo";

    /**
     * Resolves the {@code ${...}} placeholders in {@link AppProperties}.
     *
     * <p>{@code static} is required, not stylistic: a {@code BeanFactoryPostProcessor}
     * has to be instantiated before the configuration class itself, and a non-static
     * {@code @Bean} method would force early instantiation of the enclosing
     * {@code @Configuration} — at which point its own {@code @Value}s are unresolvable.
     */
    @Bean
    public static PropertySourcesPlaceholderConfigurer propertySourcesPlaceholderConfigurer() {
        return new PropertySourcesPlaceholderConfigurer();
    }

    /**
     * HikariCP, with the pool size set <b>explicitly</b>.
     *
     * <p>That is the one line in this class that is about deployment rather than
     * correctness: free-tier MySQL commonly caps connections at 5-20 and Hikari's
     * default {@code maximumPoolSize} of 10 will exhaust it under any concurrency
     * (backend-plan.md section 3).
     */
    @Bean(destroyMethod = "close")
    public DataSource dataSource(AppProperties props) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(props.getDbUrl());
        config.setUsername(props.getDbUsername());
        config.setPassword(props.getDbPassword());
        config.setMaximumPoolSize(props.getDbPoolMaxSize());
        // Named so it is identifiable in a thread dump -- the pool's threads carry it.
        config.setPoolName("pos-hikari");
        return new HikariDataSource(config);
    }

    @Bean
    public LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource,
                                                                       AppProperties props) {
        LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
        factory.setPersistenceUnitName("pos");
        factory.setDataSource(dataSource);
        factory.setPackagesToScan(ENTITY_PACKAGE);
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaProperties(hibernateProperties(props));
        return factory;
    }

    @Bean
    public PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
        return new JpaTransactionManager(entityManagerFactory);
    }

    private Properties hibernateProperties(AppProperties props) {
        Properties properties = new Properties();

        // The schema is generated from the entities; there is no migration tool.
        // create-drop in tests, update locally, create-then-validate deployed.
        properties.put(AvailableSettings.HBM2DDL_AUTO, props.getHibernateDdlAuto());

        // NOTE: hibernate.dialect is deliberately NOT set.
        //
        // Hibernate resolves both the dialect AND the server version from JDBC
        // metadata. Naming the dialect class without also naming a version pins it to
        // MySQLDialect's MINIMUM supported version (5.7), and MySQLDialect only emits
        // CHECK constraints from 8.0.16 -- so an "explicit, safer-looking" dialect
        // line would silently drop every check constraint in
        // prompts/database/constraints-and-indexes.md. Letting it autodetect is the
        // correct choice here, not the lazy one. (SchemaSqlTest, which generates DDL
        // offline with no connection to read, has to pin the version by hand.)

        // Without this, Hibernate leaves camelCase field names as camelCase column
        // names -- Boot's starter installs this strategy, and nothing else does. It is
        // a safety net: every column is also named explicitly on its entity, so the
        // schema docs match the code literally rather than via a derivation rule.
        properties.put(AvailableSettings.PHYSICAL_NAMING_STRATEGY,
                "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy");

        // Timestamps are stored and read as UTC regardless of the JVM's or the
        // server's zone. Omit this and a machine in IST writes local time into a
        // column documented as UTC, which is invisible until two machines disagree.
        properties.put(AvailableSettings.JDBC_TIME_ZONE, "UTC");

        // Booleans as tinyint rather than Hibernate's default `bit` on MySQL, which is
        // what BOOLEAN is an alias for there and what prompts/database/README.md
        // documents. There is no equivalent global setting for enums -- those carry
        // @JdbcTypeCode(VARCHAR) per field, because Hibernate 6 would otherwise emit
        // MySQL's native ENUM type, and adding a value to one is a table alter that
        // hbm2ddl.auto=update will not perform.
        properties.put(MappingSettings.PREFERRED_BOOLEAN_JDBC_TYPE, "TINYINT");

        properties.put(AvailableSettings.SHOW_SQL, props.isHibernateShowSql());
        properties.put(AvailableSettings.FORMAT_SQL, props.isHibernateShowSql());

        return properties;
    }
}

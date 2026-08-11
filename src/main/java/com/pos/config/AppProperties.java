package com.pos.config;

import org.springframework.beans.factory.annotation.Value;

/**
 * Typed access to everything in {@code application.properties}.
 *
 * <p>This class exists so that <b>nothing outside {@code com.pos.config} reads raw
 * configuration</b> (CONVENTIONS.md). A {@code @Value} in a service couples business
 * logic to a property name and cannot be constructed in a unit test without a Spring
 * context; a getter here can.
 *
 * <p>Not a {@code @Component}: no context component-scans {@code com.pos.config}, so it
 * is registered by {@link PersistenceConfig}'s {@code @Import}. The {@code @Value}
 * defaults are deliberately absent — a missing property should fail at startup with the
 * property name in the message, not silently fall back. Defaults belong in
 * {@code application.properties}, where they are visible and overridable.
 */
public class AppProperties {

    @Value("${pos.db.url}")
    private String dbUrl;

    @Value("${pos.db.username}")
    private String dbUsername;

    @Value("${pos.db.password}")
    private String dbPassword;

    @Value("${pos.db.pool.maxSize}")
    private int dbPoolMaxSize;

    @Value("${pos.hibernate.ddlAuto}")
    private String hibernateDdlAuto;

    @Value("${pos.hibernate.showSql}")
    private boolean hibernateShowSql;

    @Value("${pos.jwt.secret}")
    private String jwtSecret;

    @Value("${pos.jwt.ttlMinutes}")
    private long jwtTtlMinutes;

    @Value("${pos.seed.dev}")
    private boolean seedDev;

    // --- Mail (C9) ---------------------------------------------------------------

    @Value("${pos.mail.enabled}")
    private boolean mailEnabled;

    @Value("${pos.mail.host}")
    private String mailHost;

    @Value("${pos.mail.port}")
    private int mailPort;

    @Value("${pos.mail.username}")
    private String mailUsername;

    @Value("${pos.mail.appPassword}")
    private String mailAppPassword;

    @Value("${pos.mail.fromAddress}")
    private String mailFromAddress;

    public String getDbUrl() {
        return dbUrl;
    }

    public String getDbUsername() {
        return dbUsername;
    }

    public String getDbPassword() {
        return dbPassword;
    }

    public int getDbPoolMaxSize() {
        return dbPoolMaxSize;
    }

    public String getHibernateDdlAuto() {
        return hibernateDdlAuto;
    }

    public boolean isHibernateShowSql() {
        return hibernateShowSql;
    }

    public String getJwtSecret() {
        return jwtSecret;
    }

    public long getJwtTtlMinutes() {
        return jwtTtlMinutes;
    }

    public boolean isSeedDev() {
        return seedDev;
    }

    public boolean isMailEnabled() {
        return mailEnabled;
    }

    public String getMailHost() {
        return mailHost;
    }

    public int getMailPort() {
        return mailPort;
    }

    public String getMailUsername() {
        return mailUsername;
    }

    public String getMailAppPassword() {
        return mailAppPassword;
    }

    public String getMailFromAddress() {
        return mailFromAddress;
    }
}

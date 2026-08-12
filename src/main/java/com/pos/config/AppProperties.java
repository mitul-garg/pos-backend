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

    // --- Login lockout (peer-review Phase 0) --------------------------------------

    @Value("${pos.login.lockout.maxFailures}")
    private int loginLockoutMaxFailures;

    @Value("${pos.login.lockout.minutes}")
    private int loginLockoutMinutes;

    // --- General API rate limiting (peer-review Phase 0) --------------------------

    @Value("${pos.api.rateLimit.maxRequests}")
    private int apiRateLimitMaxRequests;

    @Value("${pos.api.rateLimit.windowSeconds}")
    private int apiRateLimitWindowSeconds;

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

    // --- App-level (C9) -----------------------------------------------------------

    @Value("${pos.app.frontendBaseUrl}")
    private String frontendBaseUrl;

    // --- reCAPTCHA (peer-review Phase 0) -------------------------------------------

    @Value("${pos.recaptcha.enabled}")
    private boolean recaptchaEnabled;

    @Value("${pos.recaptcha.secret}")
    private String recaptchaSecret;

    // --- Resource-creation guardrails (peer-review Phase 0) ------------------------
    // Durable DB-count ceilings, not time-windowed rate limits -- see each check
    // site for the DAO query it compares against. Landing one guardrail per commit;
    // this is the first (order line items, the only one needing no new DAO/schema
    // change) -- the rest join here as they land.

    @Value("${pos.order.maxLineItems}")
    private int orderMaxLineItems;

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

    public int getLoginLockoutMaxFailures() {
        return loginLockoutMaxFailures;
    }

    public int getLoginLockoutMinutes() {
        return loginLockoutMinutes;
    }

    public int getApiRateLimitMaxRequests() {
        return apiRateLimitMaxRequests;
    }

    public int getApiRateLimitWindowSeconds() {
        return apiRateLimitWindowSeconds;
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

    public String getFrontendBaseUrl() {
        return frontendBaseUrl;
    }

    public boolean isRecaptchaEnabled() {
        return recaptchaEnabled;
    }

    public String getRecaptchaSecret() {
        return recaptchaSecret;
    }

    public int getOrderMaxLineItems() {
        return orderMaxLineItems;
    }
}

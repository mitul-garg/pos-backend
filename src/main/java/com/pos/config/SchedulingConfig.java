package com.pos.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on Spring's {@code @Scheduled} support — one class, one concern, the same
 * reason {@link PersistenceConfig}/{@link SecurityConfig} are split out rather than
 * folded into {@link RootConfig} (CONVENTIONS.md). Registered in {@link
 * WebAppInitializer} alongside the other root-context config classes: a scheduled job
 * calls into the service layer the way a controller does, so it belongs in the root
 * context, not the servlet one.
 *
 * <p>Peer-review Phase 1, net-new — enables {@code com.pos.job}'s first (and, as of
 * this change, only) job, {@code AbandonedTenantCleanupJob}.
 *
 * <p>No custom {@code TaskScheduler} bean. Spring's default single-threaded one is
 * enough for the one job this enables, and this project's e2-micro sizing has nothing
 * to spare on a thread pool for jobs that don't exist yet. Revisit if a second job
 * ever makes them contend.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}

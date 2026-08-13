package com.pos.job;

import java.util.concurrent.TimeUnit;

import com.pos.config.AppProperties;
import com.pos.service.AbandonedTenantCleanupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The scheduled trigger for {@link AbandonedTenantCleanupService} — peer-review Phase
 * 1, net-new, and <b>the first scheduled job in this codebase.</b> {@code com.pos.job}
 * is a new top-level package, added to {@code RootConfig}'s component scan alongside
 * {@code service}/{@code dao}/{@code util}: a deliberate, dedicated home for this and
 * any job that follows, closing the Phase 2 "give background jobs a clear home before
 * more get added" note.
 *
 * <p>Deliberately thin — the same {@code Controller}/{@code Service} split drawn
 * everywhere else (CONVENTIONS.md): this class owns <i>when</i>, the service owns
 * <i>what</i> and <i>why</i>. All of the actual query/delete logic lives there, where
 * a plain {@code @Transactional} method can be tested without a running scheduler.
 *
 * <p>Registered via {@link com.pos.config.SchedulingConfig}'s {@code @EnableScheduling}
 * in the root context — a scheduled job calls into the service layer the way a
 * controller does, so it belongs where services live, not in the servlet context.
 */
@Component
public class AbandonedTenantCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(AbandonedTenantCleanupJob.class);

    private final AbandonedTenantCleanupService cleanupService;
    private final AppProperties appProperties;

    @Autowired
    public AbandonedTenantCleanupJob(AbandonedTenantCleanupService cleanupService,
                                     AppProperties appProperties) {
        this.cleanupService = cleanupService;
        this.appProperties = appProperties;
    }

    /**
     * {@code fixedDelayString}, not {@code fixedRateString} — waits for one run to
     * finish before starting the countdown to the next, which only matters if a run
     * is ever slow, but costs nothing to prefer now.
     *
     * <p>Runs once immediately at context startup (Spring's default with no {@code
     * initialDelay}) and every {@code pos.job.abandonedTenant.intervalMinutes} after.
     * Harmless: a fresh boot's first run almost always finds nothing, and {@code
     * pos.job.abandonedTenant.enabled} is off in {@code application-test.properties}
     * specifically so this doesn't run a real query on every test-context boot — see
     * that property's comment for why the cleanup logic doesn't need it to.
     */
    @Scheduled(fixedDelayString = "${pos.job.abandonedTenant.intervalMinutes}",
            timeUnit = TimeUnit.MINUTES)
    public void run() {
        if (!appProperties.isAbandonedTenantCleanupEnabled()) {
            return;
        }
        int removed = cleanupService.cleanUp();
        if (removed > 0) {
            log.info("Abandoned-tenant cleanup removed {} tenant(s) past their "
                    + "verification deadline", removed);
        }
    }
}

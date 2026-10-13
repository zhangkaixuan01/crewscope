package io.crewscope.server.config.application;

import io.crewscope.application.organization.governance.OrganizationAuditRetentionSweeper;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Non-overlapping wake-up loop for the audit retention sweep (M12-A01). Windowed organizations
 * shed at most one bounded batch per tick; organizations without a materialized window — the
 * retain-forever default — cost nothing but the read of their setting row.
 */
final class OrganizationRetentionSweepScheduler {

    private final OrganizationAuditRetentionSweeper sweeper;
    private final AtomicBoolean sweeping = new AtomicBoolean();

    OrganizationRetentionSweepScheduler(OrganizationAuditRetentionSweeper sweeper) {
        this.sweeper = Objects.requireNonNull(sweeper, "sweeper");
    }

    @Scheduled(fixedDelayString = "${crewscope.organization.audit-retention.sweep-interval:1h}")
    void sweep() {
        if (!sweeping.compareAndSet(false, true)) {
            return;
        }
        try {
            sweeper.runOnce();
        } finally {
            sweeping.set(false);
        }
    }
}

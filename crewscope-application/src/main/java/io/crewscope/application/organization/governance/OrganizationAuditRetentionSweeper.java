package io.crewscope.application.organization.governance;

import io.crewscope.application.organization.OrganizationSettingRepository;
import io.crewscope.domain.organization.setting.OrganizationSetting;
import io.crewscope.domain.shared.time.TimeProvider;
import java.time.Duration;
import java.util.Objects;

/**
 * One audit retention sweep pass (M12-A01, S01 freeze 3.4): organizations without a materialized
 * window (the default) are skipped — the default is retain forever — and every windowed
 * organization sheds at most one bounded batch per pass, SECURITY facts excepted. The scheduler's
 * fixed delay paces the rest; the gateway appends the sweep's own audit fact atomically.
 */
public final class OrganizationAuditRetentionSweeper {

    /** Bounded batch per organization per pass. */
    public static final int SWEEP_BATCH = 500;

    private final OrganizationSettingRepository settings;
    private final AuditRetentionGateway gateway;
    private final TimeProvider timeProvider;

    public OrganizationAuditRetentionSweeper(
            OrganizationSettingRepository settings,
            AuditRetentionGateway gateway,
            TimeProvider timeProvider) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    /** Sweeps every windowed organization once and reports the total number of deleted rows. */
    public long runOnce() {
        long deleted = 0;
        for (OrganizationSetting setting : settings.findMaterialized()) {
            int retentionDays = setting.auditEventRetentionDays().orElse(0);
            if (retentionDays <= 0) {
                // No materialized window on this row: retain forever.
                continue;
            }
            deleted += gateway.deleteExpiredBefore(
                    setting.organizationId(),
                    AuditRetentionGateway.cutoffAt(timeProvider.now(), Duration.ofDays(retentionDays)),
                    SWEEP_BATCH);
        }
        return deleted;
    }
}

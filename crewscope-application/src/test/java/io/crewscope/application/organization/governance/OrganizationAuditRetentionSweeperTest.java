package io.crewscope.application.organization.governance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.organization.OrganizationSettingRepository;
import io.crewscope.domain.organization.setting.OrganizationSetting;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The retention sweep is strictly window-gated: organizations without a materialized window
 * — the retain-forever default — never reach the gateway, and windowed organizations shed at
 * most one bounded batch whose cutoff is exactly now minus the window.
 */
class OrganizationAuditRetentionSweeperTest {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-10-12T08:00:00Z");
    private static final UtcTimestamp NINETY_DAYS_EARLIER =
            UtcTimestamp.parse("2026-07-14T08:00:00Z");

    private final OrganizationId foreverOrg = OrganizationId.generate();
    private final OrganizationId windowedOrg = OrganizationId.generate();
    private final OrganizationSettingRepository settings =
            mock(OrganizationSettingRepository.class);
    private final AuditRetentionGateway gateway = mock(AuditRetentionGateway.class);

    private final OrganizationAuditRetentionSweeper sweeper =
            new OrganizationAuditRetentionSweeper(settings, gateway, () -> NOW);

    @Test
    void windowlessOrganizationsNeverReachTheGateway() {
        when(settings.findMaterialized()).thenReturn(List.of(
                OrganizationSetting.initial(foreverOrg, null, PrincipalId.generate(), NOW)));

        long deleted = sweeper.runOnce();

        assertEquals(0L, deleted);
        verify(gateway, never()).deleteExpiredBefore(any(), any(), anyInt());
    }

    @Test
    void windowedOrganizationsShedOneBoundedBatchAtTheWindowCutoff() {
        when(settings.findMaterialized()).thenReturn(List.of(
                OrganizationSetting.initial(foreverOrg, null, PrincipalId.generate(), NOW),
                OrganizationSetting.initial(windowedOrg, 90, PrincipalId.generate(), NOW)));
        when(gateway.deleteExpiredBefore(
                eq(windowedOrg), eq(NINETY_DAYS_EARLIER), eq(OrganizationAuditRetentionSweeper.SWEEP_BATCH)))
                .thenReturn(500L);

        long deleted = sweeper.runOnce();

        assertEquals(500L, deleted);
        verify(gateway).deleteExpiredBefore(
                eq(windowedOrg), eq(NINETY_DAYS_EARLIER),
                eq(OrganizationAuditRetentionSweeper.SWEEP_BATCH));
        verify(gateway, never()).deleteExpiredBefore(eq(foreverOrg), any(), anyInt());
    }
}

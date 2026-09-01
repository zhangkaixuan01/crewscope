package io.crewscope.application.setup;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.List;
import java.util.Objects;

/** Immutable, versioned Readiness snapshot derived from current Team facts. */
public record TeamSetupReadinessView(
        OrganizationId organizationId,
        TeamId teamId,
        String snapshotVersion,
        UtcTimestamp observedAt,
        List<TeamSetupReadinessItem> capabilities,
        boolean requiredReady) {

    public TeamSetupReadinessView {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        teamId = Objects.requireNonNull(teamId, "teamId");
        if (snapshotVersion == null || snapshotVersion.isBlank()) {
            throw new IllegalArgumentException("snapshotVersion must not be blank");
        }
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
        capabilities = List.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
        if (capabilities.isEmpty()) {
            throw new IllegalArgumentException("capabilities must not be empty");
        }
        boolean derivedRequiredReady = capabilities.stream()
                .filter(TeamSetupReadinessItem::required)
                .allMatch(TeamSetupReadinessItem::ready);
        if (requiredReady != derivedRequiredReady) {
            throw new IllegalArgumentException("requiredReady must match required capability states");
        }
    }
}

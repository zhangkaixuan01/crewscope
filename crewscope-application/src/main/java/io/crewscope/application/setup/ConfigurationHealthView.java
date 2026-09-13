package io.crewscope.application.setup;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.List;
import java.util.Objects;

/** Read-only configuration health snapshot derived from existing Team facts. */
public record ConfigurationHealthView(
        OrganizationId organizationId,
        TeamId teamId,
        UtcTimestamp observedAt,
        ConfigurationHealthStatus overallStatus,
        List<ConfigurationHealthItem> items) {

    public ConfigurationHealthView {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        teamId = Objects.requireNonNull(teamId, "teamId");
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
        overallStatus = Objects.requireNonNull(overallStatus, "overallStatus");
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        if (items.isEmpty()) {
            throw new IllegalArgumentException("items must not be empty");
        }
    }
}

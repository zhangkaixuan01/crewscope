package io.crewscope.domain.organization.quota.event;

import io.crewscope.domain.shared.DomainEvent;
import java.util.Objects;
import java.util.UUID;

/**
 * Fact emitted after the organization quota configuration changed. Null limit fields mean the
 * respective dimension is (back to) unconfigured.
 */
public record OrganizationQuotaConfigurationChanged(
        UUID organizationId,
        Integer monthlyTaskIntakeLimitAfter,
        Long monthlyTokenBudgetAfter,
        long versionAfter)
        implements DomainEvent {

    public OrganizationQuotaConfigurationChanged {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
    }
}

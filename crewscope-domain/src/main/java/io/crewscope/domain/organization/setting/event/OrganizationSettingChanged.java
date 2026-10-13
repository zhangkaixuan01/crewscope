package io.crewscope.domain.organization.setting.event;

import io.crewscope.domain.shared.DomainEvent;
import java.util.Objects;
import java.util.UUID;

/**
 * Fact emitted after organization settings changed. {@code auditEventRetentionDaysAfter} is null
 * when retention is (back to) forever.
 */
public record OrganizationSettingChanged(
        UUID organizationId, Integer auditEventRetentionDaysAfter, long versionAfter)
        implements DomainEvent {

    public OrganizationSettingChanged {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
    }
}

package io.crewscope.domain.organization.governance.event;

import io.crewscope.domain.shared.DomainEvent;
import java.util.Objects;
import java.util.UUID;

/** Fact emitted after every export batch reached a terminal state and the manifest froze. */
public record OrganizationDataExportCompleted(
        UUID organizationId, UUID jobId, String manifestChecksum, int itemCount)
        implements DomainEvent {

    public OrganizationDataExportCompleted {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        jobId = Objects.requireNonNull(jobId, "jobId");
        manifestChecksum = Objects.requireNonNull(manifestChecksum, "manifestChecksum");
    }
}

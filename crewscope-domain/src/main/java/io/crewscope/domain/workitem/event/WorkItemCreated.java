package io.crewscope.domain.workitem.event;

import io.crewscope.domain.responsibility.ResponsibilityAssignment;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.AggregateId;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemKey;
import io.crewscope.domain.workitem.WorkItemStatus;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Version 1 business payload emitted after a WorkItem and its initial Owner are created. */
public record WorkItemCreated(
        UUID projectId,
        String itemKey,
        String title,
        WorkItemStatus status,
        Optional<UUID> initialOwnerAssignmentId,
        Optional<UUID> initialOwnerPrincipalId)
        implements DomainEvent {

    public WorkItemCreated {
        projectId = AggregateId.requireValue(projectId, "WorkItemCreated.projectId");
        itemKey = new WorkItemKey(itemKey).value();
        if (title == null || title.isBlank()) {
            throw new DomainValidationException("workItemCreated.title", "must not be blank");
        }
        title = title.strip();
        if (title.length() > WorkItem.MAX_TITLE_LENGTH) {
            throw new DomainValidationException(
                    "workItemCreated.title",
                    "must contain at most " + WorkItem.MAX_TITLE_LENGTH + " characters");
        }
        status = Objects.requireNonNull(status, "status");
        initialOwnerAssignmentId =
                Objects.requireNonNull(initialOwnerAssignmentId, "initialOwnerAssignmentId");
        initialOwnerPrincipalId =
                Objects.requireNonNull(initialOwnerPrincipalId, "initialOwnerPrincipalId");
        if (initialOwnerAssignmentId.isPresent() != initialOwnerPrincipalId.isPresent()) {
            throw new DomainValidationException(
                    "workItemCreated.initialOwner",
                    "assignment and Principal identities must both be present or absent");
        }
    }

    /**
     * Creates the legacy payload used by the pre-M1 compatibility service. Production M1
     * composition uses {@link #from(WorkItem, ResponsibilityAssignment)}.
     */
    public static WorkItemCreated from(WorkItem workItem) {
        WorkItem source = Objects.requireNonNull(workItem, "workItem");
        return new WorkItemCreated(
                source.scope().projectId().value(),
                source.key().value(),
                source.title(),
                source.status(),
                Optional.empty(),
                Optional.empty());
    }

    /** Creates the complete M1 creation fact from the committed WorkItem and initial Owner. */
    public static WorkItemCreated from(
            WorkItem workItem, ResponsibilityAssignment initialOwner) {
        WorkItem source = Objects.requireNonNull(workItem, "workItem");
        ResponsibilityAssignment owner =
                Objects.requireNonNull(initialOwner, "initialOwner");
        if (owner.role() != ResponsibilityRole.OWNER
                || !owner.isActive()
                || !owner.workItemId().equals(source.id())
                || !owner.scope().equals(source.scope())) {
            throw new DomainValidationException(
                    "workItemCreated.initialOwner",
                    "must reference the active Owner of the created WorkItem");
        }
        return new WorkItemCreated(
                source.scope().projectId().value(),
                source.key().value(),
                source.title(),
                source.status(),
                Optional.of(owner.id().value()),
                Optional.of(owner.actorPrincipalId().value()));
    }
}

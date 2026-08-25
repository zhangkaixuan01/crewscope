package io.crewscope.domain.activity;

import io.crewscope.domain.projection.ProjectionGeneration;
import io.crewscope.domain.projection.ProjectionName;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.id.AggregateId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkItemId;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Immutable, generation-aware and publicly safe Team Activity projection fact. */
public record ActivityEvent(
        ActivityEventId id,
        UUID domainEventId,
        OrganizationId organizationId,
        TeamId teamId,
        ProjectionName projectionName,
        ProjectionGeneration projectionGeneration,
        SchemaVersion projectionSchemaVersion,
        TeamSequence teamSequence,
        EventType eventType,
        ActivityCategory category,
        ActivityVisibility visibility,
        ActivitySubject subject,
        ActivityActor actor,
        List<ActivityReference> references,
        UtcTimestamp occurredAt,
        ActivityPublicPayload payload) {

    public ActivityEvent {
        domainEventId = AggregateId.requireValue(domainEventId, "ActivityEvent.domainEventId");
        id = Objects.requireNonNull(id, "id").requireDomainEvent(domainEventId);
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        teamId = Objects.requireNonNull(teamId, "teamId");
        projectionName = Objects.requireNonNull(projectionName, "projectionName");
        projectionGeneration =
                Objects.requireNonNull(projectionGeneration, "projectionGeneration");
        projectionSchemaVersion =
                Objects.requireNonNull(projectionSchemaVersion, "projectionSchemaVersion");
        teamSequence = Objects.requireNonNull(teamSequence, "teamSequence");
        eventType = Objects.requireNonNull(eventType, "eventType");
        category = Objects.requireNonNull(category, "category");
        visibility = Objects.requireNonNull(visibility, "visibility");
        subject = Objects.requireNonNull(subject, "subject");
        actor = Objects.requireNonNull(actor, "actor");
        references = List.copyOf(Objects.requireNonNull(references, "references"));
        if (new HashSet<>(references).size() != references.size()) {
            throw new IllegalArgumentException("ActivityEvent.references must not contain duplicates");
        }
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        payload = Objects.requireNonNull(payload, "payload");
        requireTeamSubjectMatchesScope(subject, teamId);
        requireTeamReferencesMatchScope(references, teamId);
        if (visibility == ActivityVisibility.WORK_ITEM_PARTICIPANTS
                && referencedWorkItems(subject, references).size() != 1) {
            throw new IllegalArgumentException(
                    "WORK_ITEM_PARTICIPANTS Activity must identify exactly one WorkItem");
        }
    }

    public static ActivityEvent project(
            UUID domainEventId,
            OrganizationId organizationId,
            TeamId teamId,
            ProjectionName projectionName,
            ProjectionGeneration projectionGeneration,
            SchemaVersion projectionSchemaVersion,
            TeamSequence teamSequence,
            EventType eventType,
            ActivityCategory category,
            ActivityVisibility visibility,
            ActivitySubject subject,
            ActivityActor actor,
            List<ActivityReference> references,
            UtcTimestamp occurredAt,
            ActivityPublicPayload payload) {
        return new ActivityEvent(
                ActivityEventId.fromDomainEvent(domainEventId),
                domainEventId,
                organizationId,
                teamId,
                projectionName,
                projectionGeneration,
                projectionSchemaVersion,
                teamSequence,
                eventType,
                category,
                visibility,
                subject,
                actor,
                references,
                occurredAt,
                payload);
    }

    /** Returns the single WorkItem used by a participant-only visibility rule. */
    public Optional<WorkItemId> restrictedWorkItemId() {
        Set<WorkItemId> workItems = referencedWorkItems(subject, references);
        return workItems.size() == 1 ? Optional.of(workItems.iterator().next()) : Optional.empty();
    }

    public boolean referencesWorkItem(WorkItemId workItemId) {
        return referencedWorkItems(subject, references)
                .contains(Objects.requireNonNull(workItemId, "workItemId"));
    }

    private static Set<WorkItemId> referencedWorkItems(
            ActivitySubject subject, List<ActivityReference> references) {
        HashSet<WorkItemId> workItems = new HashSet<>();
        if (subject.type() == ActivitySubjectType.WORK_ITEM) {
            workItems.add(new WorkItemId(subject.id()));
        }
        references.stream()
                .filter(reference -> reference.type() == ActivityReferenceType.WORK_ITEM)
                .map(reference -> new WorkItemId(reference.id()))
                .forEach(workItems::add);
        return Set.copyOf(workItems);
    }

    private static void requireTeamSubjectMatchesScope(ActivitySubject subject, TeamId teamId) {
        if (subject.type() == ActivitySubjectType.TEAM && !subject.id().equals(teamId.value())) {
            throw new IllegalArgumentException("TEAM Activity subject must match Activity Team scope");
        }
    }

    private static void requireTeamReferencesMatchScope(
            List<ActivityReference> references, TeamId teamId) {
        boolean foreignTeam = references.stream()
                .filter(reference -> reference.type() == ActivityReferenceType.TEAM)
                .anyMatch(reference -> !reference.id().equals(teamId.value()));
        if (foreignTeam) {
            throw new IllegalArgumentException("TEAM Activity reference must match Activity Team scope");
        }
    }
}

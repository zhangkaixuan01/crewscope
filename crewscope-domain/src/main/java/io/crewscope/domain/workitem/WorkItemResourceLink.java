package io.crewscope.domain.workitem;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.Optional;

/** Immutable relation from a WorkItem to a Task, code object, Artifact or external URL. */
public final class WorkItemResourceLink {

    public static final int MAX_REFERENCE_LENGTH = 2_000;
    public static final int MAX_LABEL_LENGTH = 200;

    private final WorkItemResourceLinkId id;
    private final WorkItemScope scope;
    private final WorkItemId workItemId;
    private final WorkItemResourceType resourceType;
    private final String resourceReference;
    private final Optional<String> label;
    private final AuditMetadata audit;

    private WorkItemResourceLink(
            WorkItemResourceLinkId id,
            WorkItemScope scope,
            WorkItemId workItemId,
            WorkItemResourceType resourceType,
            String resourceReference,
            Optional<String> label,
            AuditMetadata audit) {
        this.id = Objects.requireNonNull(id, "id");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.workItemId = Objects.requireNonNull(workItemId, "workItemId");
        this.resourceType = Objects.requireNonNull(resourceType, "resourceType");
        this.resourceReference = requireReference(resourceReference);
        this.label = normalizeLabel(label);
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    /** Links a resource after validating WorkItem lifecycle and creator scope. */
    public static WorkItemResourceLink link(
            WorkItemResourceLinkId id,
            WorkItem workItem,
            WorkItemResourceType resourceType,
            String resourceReference,
            Optional<String> label,
            Principal creator,
            UtcTimestamp occurredAt) {
        WorkItem requiredWorkItem = Objects.requireNonNull(workItem, "workItem");
        if (!requiredWorkItem.acceptsCollaboration()) {
            throw new DomainValidationException(
                    "workItemResourceLink.workItemId",
                    "must not reference an archived WorkItem");
        }
        PrincipalId actorId = WorkItemActorPolicy.requireActiveInScope(
                creator,
                requiredWorkItem.scope().organizationId(),
                requiredWorkItem.scope().teamId(),
                "workItemResourceLink.createdByPrincipalId");
        return new WorkItemResourceLink(
                id,
                requiredWorkItem.scope(),
                requiredWorkItem.id(),
                resourceType,
                resourceReference,
                label,
                AuditMetadata.createdBy(actorId, occurredAt));
    }

    /** Reconstitutes an immutable committed resource relation. */
    public static WorkItemResourceLink reconstitute(
            WorkItemResourceLinkId id,
            WorkItemScope scope,
            WorkItemId workItemId,
            WorkItemResourceType resourceType,
            String resourceReference,
            Optional<String> label,
            AuditMetadata audit) {
        return new WorkItemResourceLink(
                id,
                scope,
                workItemId,
                resourceType,
                resourceReference,
                label,
                audit);
    }

    public WorkItemResourceLinkId id() {
        return id;
    }

    public WorkItemScope scope() {
        return scope;
    }

    public WorkItemId workItemId() {
        return workItemId;
    }

    public WorkItemResourceType resourceType() {
        return resourceType;
    }

    public String resourceReference() {
        return resourceReference;
    }

    public Optional<String> label() {
        return label;
    }

    public AuditMetadata audit() {
        return audit;
    }

    private static String requireReference(String value) {
        if (value == null || value.isBlank()) {
            throw new DomainValidationException(
                    "workItemResourceLink.resourceReference", "must not be blank");
        }
        String normalized = value.strip();
        if (normalized.length() > MAX_REFERENCE_LENGTH) {
            throw new DomainValidationException(
                    "workItemResourceLink.resourceReference",
                    "must contain at most " + MAX_REFERENCE_LENGTH + " characters");
        }
        return normalized;
    }

    private static Optional<String> normalizeLabel(Optional<String> value) {
        Optional<String> normalized = Objects.requireNonNull(value, "label")
                .map(String::strip)
                .filter(text -> !text.isEmpty());
        if (normalized.filter(text -> text.length() > MAX_LABEL_LENGTH).isPresent()) {
            throw new DomainValidationException(
                    "workItemResourceLink.label",
                    "must contain at most " + MAX_LABEL_LENGTH + " characters");
        }
        return normalized;
    }
}

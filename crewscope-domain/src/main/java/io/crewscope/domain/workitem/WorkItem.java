package io.crewscope.domain.workitem;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class WorkItem {

    /** PostgreSQL {@code work_item.title} and external Command maximum length. */
    public static final int MAX_TITLE_LENGTH = 500;
    public static final int MAX_DESCRIPTION_LENGTH = 100_000;
    public static final int MAX_SOURCE_REFERENCE_LENGTH = 500;
    public static final int MAX_LABELS = 20;

    private static final Map<WorkItemStatus, Set<WorkItemStatus>> ALLOWED_TRANSITIONS = Map.of(
            WorkItemStatus.BACKLOG,
            EnumSet.of(WorkItemStatus.READY, WorkItemStatus.CANCELLED),
            WorkItemStatus.READY,
            EnumSet.of(WorkItemStatus.IN_PROGRESS, WorkItemStatus.CANCELLED),
            WorkItemStatus.IN_PROGRESS,
            EnumSet.of(WorkItemStatus.IN_REVIEW, WorkItemStatus.BLOCKED, WorkItemStatus.CANCELLED),
            WorkItemStatus.IN_REVIEW,
            EnumSet.of(
                    WorkItemStatus.IN_PROGRESS,
                    WorkItemStatus.BLOCKED,
                    WorkItemStatus.DONE,
                    WorkItemStatus.CANCELLED),
            WorkItemStatus.BLOCKED,
            EnumSet.of(
                    WorkItemStatus.READY,
                    WorkItemStatus.IN_PROGRESS,
                    WorkItemStatus.IN_REVIEW,
                    WorkItemStatus.CANCELLED),
            WorkItemStatus.DONE,
            EnumSet.of(WorkItemStatus.ARCHIVED),
            WorkItemStatus.CANCELLED,
            EnumSet.of(WorkItemStatus.ARCHIVED),
            WorkItemStatus.ARCHIVED,
            EnumSet.noneOf(WorkItemStatus.class));

    private final WorkItemId id;
    private final WorkItemScope scope;
    private final WorkItemKey key;
    private final WorkItemType type;
    private final String title;
    private final Optional<String> description;
    private final WorkItemStatus status;
    private final WorkItemPriority priority;
    private final Set<WorkItemLabel> labels;
    private final Optional<UtcTimestamp> dueAt;
    private final WorkItemSource source;
    private final Optional<String> sourceReference;
    private final long version;
    private final AuditMetadata audit;

    private WorkItem(
            WorkItemId id,
            WorkItemScope scope,
            WorkItemKey key,
            WorkItemType type,
            String title,
            Optional<String> description,
            WorkItemStatus status,
            WorkItemPriority priority,
            Set<WorkItemLabel> labels,
            Optional<UtcTimestamp> dueAt,
            WorkItemSource source,
            Optional<String> sourceReference,
            long version,
            AuditMetadata audit) {
        this.id = Objects.requireNonNull(id, "id");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.key = Objects.requireNonNull(key, "key");
        this.type = Objects.requireNonNull(type, "type");
        this.title = requireTitle(title);
        this.description = normalizeDescription(description);
        this.status = Objects.requireNonNull(status, "status");
        this.priority = Objects.requireNonNull(priority, "priority");
        this.labels = requireLabels(labels);
        this.dueAt = Objects.requireNonNull(dueAt, "dueAt");
        this.source = Objects.requireNonNull(source, "source");
        this.sourceReference = requireSourceReference(this.source, sourceReference);
        this.version = requireVersion(version);
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    /** Creates a new WorkItem owned by the supplied tenant scope and trusted Principal. */
    public static WorkItem create(
            WorkItemId id,
            WorkItemScope scope,
            WorkItemKey key,
            String title,
            PrincipalId actor,
            UtcTimestamp occurredAt) {
        return new WorkItem(
                id,
                scope,
                key,
                WorkItemType.TASK,
                title,
                Optional.empty(),
                WorkItemStatus.BACKLOG,
                WorkItemPriority.MEDIUM,
                Set.of(),
                Optional.empty(),
                WorkItemSource.CREWSCOPE,
                Optional.empty(),
                0,
                AuditMetadata.createdBy(actor, occurredAt));
    }

    /** Creates a fully described native WorkItem inside one active WorkProject. */
    public static WorkItem createNative(
            WorkItemId id,
            WorkProject project,
            WorkItemKey key,
            WorkItemType type,
            String title,
            Optional<String> description,
            WorkItemPriority priority,
            Set<WorkItemLabel> labels,
            Optional<UtcTimestamp> dueAt,
            Principal actor,
            UtcTimestamp occurredAt) {
        return createForProject(
                id,
                project,
                key,
                type,
                title,
                description,
                priority,
                labels,
                dueAt,
                WorkItemSource.CREWSCOPE,
                Optional.empty(),
                actor,
                occurredAt);
    }

    /** Creates a local projection whose authoritative fact remains in an external provider. */
    public static WorkItem createExternalProjection(
            WorkItemId id,
            WorkProject project,
            WorkItemKey key,
            WorkItemType type,
            String title,
            Optional<String> description,
            WorkItemPriority priority,
            Set<WorkItemLabel> labels,
            Optional<UtcTimestamp> dueAt,
            WorkItemSource source,
            String sourceReference,
            Principal actor,
            UtcTimestamp occurredAt) {
        WorkItemSource requiredSource = Objects.requireNonNull(source, "source");
        if (requiredSource.isNative()) {
            throw new DomainValidationException(
                    "workItem.source", "must reference an external WorkItem provider");
        }
        return createForProject(
                id,
                project,
                key,
                type,
                title,
                description,
                priority,
                labels,
                dueAt,
                requiredSource,
                Optional.ofNullable(sourceReference),
                actor,
                occurredAt);
    }

    /** Reconstitutes a committed aggregate without replaying persistence-side mutations. */
    public static WorkItem reconstitute(
            WorkItemId id,
            WorkItemScope scope,
            WorkItemKey key,
            String title,
            WorkItemStatus status,
            long version,
            AuditMetadata audit) {
        return new WorkItem(
                id,
                scope,
                key,
                WorkItemType.TASK,
                title,
                Optional.empty(),
                status,
                WorkItemPriority.MEDIUM,
                Set.of(),
                Optional.empty(),
                WorkItemSource.CREWSCOPE,
                Optional.empty(),
                version,
                audit);
    }

    /** Reconstitutes all M1 product fields from a committed persistence snapshot. */
    public static WorkItem reconstitute(
            WorkItemId id,
            WorkItemScope scope,
            WorkItemKey key,
            WorkItemType type,
            String title,
            Optional<String> description,
            WorkItemStatus status,
            WorkItemPriority priority,
            Set<WorkItemLabel> labels,
            Optional<UtcTimestamp> dueAt,
            WorkItemSource source,
            Optional<String> sourceReference,
            long version,
            AuditMetadata audit) {
        return new WorkItem(
                id,
                scope,
                key,
                type,
                title,
                description,
                status,
                priority,
                labels,
                dueAt,
                source,
                sourceReference,
                version,
                audit);
    }

    /** Applies a valid state transition and advances the aggregate's expected committed version. */
    public WorkItem transitionTo(
            WorkItemStatus target, PrincipalId actor, UtcTimestamp occurredAt) {
        Objects.requireNonNull(target, "target");
        if (!ALLOWED_TRANSITIONS.get(status).contains(target)) {
            throw new InvalidStateTransitionException("WorkItem", id, status, target);
        }
        return new WorkItem(
                id,
                scope,
                key,
                type,
                title,
                description,
                target,
                priority,
                labels,
                dueAt,
                source,
                sourceReference,
                version + 1,
                audit.modifiedBy(actor, occurredAt));
    }

    /** Applies the same state machine after validating the active Principal scope. */
    public WorkItem transitionTo(
            WorkItemStatus target, Principal actor, UtcTimestamp occurredAt) {
        PrincipalId actorId = WorkItemActorPolicy.requireActiveInScope(
                actor,
                scope.organizationId(),
                scope.teamId(),
                "workItem.updatedByPrincipalId");
        return transitionTo(target, actorId, occurredAt);
    }

    /** Replaces user-editable planning fields as one optimistic-lock mutation. */
    public WorkItem revise(
            WorkItemType targetType,
            String targetTitle,
            Optional<String> targetDescription,
            WorkItemPriority targetPriority,
            Set<WorkItemLabel> targetLabels,
            Optional<UtcTimestamp> targetDueAt,
            Principal actor,
            UtcTimestamp occurredAt) {
        if (status == WorkItemStatus.ARCHIVED) {
            throw new InvalidStateTransitionException(
                    "WorkItem", id, WorkItemStatus.ARCHIVED, WorkItemStatus.ARCHIVED);
        }
        PrincipalId actorId = WorkItemActorPolicy.requireActiveInScope(
                actor,
                scope.organizationId(),
                scope.teamId(),
                "workItem.updatedByPrincipalId");
        return new WorkItem(
                id,
                scope,
                key,
                targetType,
                targetTitle,
                targetDescription,
                status,
                targetPriority,
                targetLabels,
                targetDueAt,
                source,
                sourceReference,
                version + 1,
                audit.modifiedBy(actorId, occurredAt));
    }

    public boolean acceptsCollaboration() {
        return status != WorkItemStatus.ARCHIVED;
    }

    public WorkItemId id() {
        return id;
    }

    public WorkItemScope scope() {
        return scope;
    }

    public WorkItemKey key() {
        return key;
    }

    public WorkItemType type() {
        return type;
    }

    public String title() {
        return title;
    }

    public Optional<String> description() {
        return description;
    }

    public WorkItemStatus status() {
        return status;
    }

    public WorkItemPriority priority() {
        return priority;
    }

    public Set<WorkItemLabel> labels() {
        return labels;
    }

    public Optional<UtcTimestamp> dueAt() {
        return dueAt;
    }

    public WorkItemSource source() {
        return source;
    }

    public Optional<String> sourceReference() {
        return sourceReference;
    }

    public long version() {
        return version;
    }

    public AuditMetadata audit() {
        return audit;
    }

    private static WorkItem createForProject(
            WorkItemId id,
            WorkProject project,
            WorkItemKey key,
            WorkItemType type,
            String title,
            Optional<String> description,
            WorkItemPriority priority,
            Set<WorkItemLabel> labels,
            Optional<UtcTimestamp> dueAt,
            WorkItemSource source,
            Optional<String> sourceReference,
            Principal actor,
            UtcTimestamp occurredAt) {
        WorkProject requiredProject = Objects.requireNonNull(project, "project");
        if (!requiredProject.acceptsWork()) {
            throw new DomainValidationException(
                    "workItem.projectId", "must reference an active WorkProject");
        }
        WorkItemKey requiredKey = Objects.requireNonNull(key, "key");
        if (!requiredKey.belongsTo(requiredProject.key())) {
            throw new DomainValidationException(
                    "workItem.key", "must use the owning WorkProject key prefix");
        }
        PrincipalId actorId = WorkItemActorPolicy.requireActiveInScope(
                actor,
                requiredProject.scope().organizationId(),
                requiredProject.scope().teamId(),
                "workItem.createdByPrincipalId");
        return new WorkItem(
                id,
                WorkItemScope.from(requiredProject),
                requiredKey,
                type,
                title,
                description,
                WorkItemStatus.BACKLOG,
                priority,
                labels,
                dueAt,
                source,
                sourceReference,
                0,
                AuditMetadata.createdBy(actorId, occurredAt));
    }

    private static String requireTitle(String value) {
        if (value == null || value.isBlank()) {
            throw new DomainValidationException("workItem.title", "must not be blank");
        }
        String normalized = value.strip();
        if (normalized.length() > MAX_TITLE_LENGTH) {
            throw new DomainValidationException(
                    "workItem.title", "must contain at most " + MAX_TITLE_LENGTH + " characters");
        }
        return normalized;
    }

    private static long requireVersion(long value) {
        if (value < 0) {
            throw new DomainValidationException("workItem.version", "must not be negative");
        }
        return value;
    }

    private static Optional<String> normalizeDescription(Optional<String> value) {
        Optional<String> required = Objects.requireNonNull(value, "description");
        Optional<String> normalized = required.map(String::strip).filter(text -> !text.isEmpty());
        if (normalized.filter(text -> text.length() > MAX_DESCRIPTION_LENGTH).isPresent()) {
            throw new DomainValidationException(
                    "workItem.description",
                    "must contain at most " + MAX_DESCRIPTION_LENGTH + " characters");
        }
        return normalized;
    }

    private static Set<WorkItemLabel> requireLabels(Set<WorkItemLabel> value) {
        Set<WorkItemLabel> required = Set.copyOf(Objects.requireNonNull(value, "labels"));
        if (required.size() > MAX_LABELS) {
            throw new DomainValidationException(
                    "workItem.labels", "must contain at most " + MAX_LABELS + " labels");
        }
        return required;
    }

    private static Optional<String> requireSourceReference(
            WorkItemSource source, Optional<String> value) {
        Optional<String> normalized = Objects.requireNonNull(value, "sourceReference")
                .map(String::strip)
                .filter(text -> !text.isEmpty());
        if (normalized.filter(text -> text.length() > MAX_SOURCE_REFERENCE_LENGTH).isPresent()) {
            throw new DomainValidationException(
                    "workItem.sourceReference",
                    "must contain at most " + MAX_SOURCE_REFERENCE_LENGTH + " characters");
        }
        if (source.isNative() && normalized.isPresent()) {
            throw new DomainValidationException(
                    "workItem.sourceReference", "must be empty for a native WorkItem");
        }
        if (!source.isNative() && normalized.isEmpty()) {
            throw new DomainValidationException(
                    "workItem.sourceReference", "is required for an external WorkItem");
        }
        return normalized;
    }
}

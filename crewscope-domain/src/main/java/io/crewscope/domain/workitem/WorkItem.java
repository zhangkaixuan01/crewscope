package io.crewscope.domain.workitem;

import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class WorkItem {

    /** PostgreSQL {@code work_item.title} and external Command maximum length. */
    public static final int MAX_TITLE_LENGTH = 500;

    private static final Map<WorkItemStatus, Set<WorkItemStatus>> ALLOWED_TRANSITIONS = Map.of(
            WorkItemStatus.BACKLOG,
            EnumSet.of(WorkItemStatus.READY, WorkItemStatus.CANCELLED),
            WorkItemStatus.READY,
            EnumSet.of(WorkItemStatus.IN_PROGRESS, WorkItemStatus.CANCELLED),
            WorkItemStatus.IN_PROGRESS,
            EnumSet.of(WorkItemStatus.IN_REVIEW, WorkItemStatus.BLOCKED, WorkItemStatus.CANCELLED),
            WorkItemStatus.IN_REVIEW,
            EnumSet.of(WorkItemStatus.IN_PROGRESS, WorkItemStatus.BLOCKED, WorkItemStatus.DONE),
            WorkItemStatus.BLOCKED,
            EnumSet.of(WorkItemStatus.READY, WorkItemStatus.IN_PROGRESS, WorkItemStatus.CANCELLED),
            WorkItemStatus.DONE,
            EnumSet.noneOf(WorkItemStatus.class),
            WorkItemStatus.CANCELLED,
            EnumSet.noneOf(WorkItemStatus.class));

    private final WorkItemId id;
    private final WorkItemScope scope;
    private final WorkItemKey key;
    private final String title;
    private final WorkItemStatus status;
    private final long version;
    private final AuditMetadata audit;

    private WorkItem(
            WorkItemId id,
            WorkItemScope scope,
            WorkItemKey key,
            String title,
            WorkItemStatus status,
            long version,
            AuditMetadata audit) {
        this.id = Objects.requireNonNull(id, "id");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.key = Objects.requireNonNull(key, "key");
        this.title = requireTitle(title);
        this.status = Objects.requireNonNull(status, "status");
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
                title,
                WorkItemStatus.BACKLOG,
                0,
                AuditMetadata.createdBy(actor, occurredAt));
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
        return new WorkItem(id, scope, key, title, status, version, audit);
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
                title,
                target,
                version + 1,
                audit.modifiedBy(actor, occurredAt));
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

    public String title() {
        return title;
    }

    public WorkItemStatus status() {
        return status;
    }

    public long version() {
        return version;
    }

    public AuditMetadata audit() {
        return audit;
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
}

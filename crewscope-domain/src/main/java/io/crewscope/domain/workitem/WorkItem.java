package io.crewscope.domain.workitem;

import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class WorkItem {

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
    private final WorkItemKey key;
    private final String title;
    private final WorkItemStatus status;
    private final long version;

    private WorkItem(
            WorkItemId id, WorkItemKey key, String title, WorkItemStatus status, long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.key = Objects.requireNonNull(key, "key");
        this.title = requireTitle(title);
        this.status = Objects.requireNonNull(status, "status");
        this.version = version;
    }

    public static WorkItem create(WorkItemId id, WorkItemKey key, String title) {
        return new WorkItem(id, key, title, WorkItemStatus.BACKLOG, 0);
    }

    public WorkItem transitionTo(WorkItemStatus target) {
        Objects.requireNonNull(target, "target");
        if (!ALLOWED_TRANSITIONS.get(status).contains(target)) {
            throw new IllegalStateException("WorkItem cannot transition from " + status + " to " + target);
        }
        return new WorkItem(id, key, title, target, version + 1);
    }

    public WorkItemId id() {
        return id;
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

    private static String requireTitle(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        return value.strip();
    }
}

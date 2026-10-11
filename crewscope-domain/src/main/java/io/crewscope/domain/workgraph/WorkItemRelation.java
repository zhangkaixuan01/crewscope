package io.crewscope.domain.workgraph;

import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemScope;
import java.util.List;
import java.util.Objects;

/**
 * Immutable directed relation between two WorkItems (ADR-033).
 *
 * <p>Stored kinds are only {@code BLOCKS} and {@code SPLIT_FROM}; a submitted {@code
 * DEPENDS_ON(X, Y)} is normalized at construction into {@code BLOCKS(Y, X)}, so the store never
 * holds both blocking directions of one pair ({@link #views()} restores the API duality). An
 * endpoint carries its full tenant scope because the two WorkItems may live in different
 * WorkProjects of the same Team.
 *
 * <p>Relations are immutable facts: no version, no state transitions, removal deletes the row and
 * emits an event.
 */
public final class WorkItemRelation {

    private final WorkItemRelationId id;
    private final RelationEndpoint src;
    private final RelationEndpoint dst;
    private final WorkItemRelationType kind;
    private final AuditMetadata audit;

    /** One endpoint: a WorkItem id together with its full tenant scope. */
    public record RelationEndpoint(WorkItemId workItemId, WorkItemScope scope) {

        public RelationEndpoint {
            Objects.requireNonNull(workItemId, "workItemId");
            Objects.requireNonNull(scope, "scope");
        }
    }

    /** One API-facing direction of the stored edge (see {@link #views()}). */
    public record RelationView(WorkItemRelationType type, WorkItemId from, WorkItemId to) {

        public RelationView {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
        }
    }

    private WorkItemRelation(
            WorkItemRelationId id,
            RelationEndpoint src,
            RelationEndpoint dst,
            WorkItemRelationType kind,
            AuditMetadata audit) {
        this.id = Objects.requireNonNull(id, "id");
        this.src = Objects.requireNonNull(src, "src");
        this.dst = Objects.requireNonNull(dst, "dst");
        this.kind = Objects.requireNonNull(kind, "kind");
        if (kind == WorkItemRelationType.DEPENDS_ON) {
            throw new DomainValidationException(
                    "workItemRelation.kind", "DEPENDS_ON is a submission view and is never stored");
        }
        if (src.workItemId().equals(dst.workItemId())) {
            throw new DomainValidationException(
                    "workItemRelation.endpoints", "must reference two distinct WorkItems");
        }
        if (!src.scope().organizationId().equals(dst.scope().organizationId())) {
            throw new DomainValidationException(
                    "workItemRelation.endpoints", "must stay inside one organization");
        }
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    /**
     * Relates {@code from} to {@code to} in the submitted direction, normalizing DEPENDS_ON by
     * swapping the endpoints into the canonical blocking edge.
     *
     * @param from submission-side source ({@code X} in {@code X DEPENDS_ON Y} or {@code A} in
     *     {@code A BLOCKS B})
     * @param to submission-side target
     */
    public static WorkItemRelation relate(
            WorkItemRelationId id,
            WorkItemRelationType submittedType,
            WorkItem from,
            WorkItem to,
            PrincipalId actor,
            UtcTimestamp occurredAt) {
        WorkItemRelationId requiredId = Objects.requireNonNull(id, "id");
        WorkItemRelationType type =
                Objects.requireNonNull(submittedType, "submittedType");
        WorkItem fromItem = Objects.requireNonNull(from, "from");
        WorkItem toItem = Objects.requireNonNull(to, "to");
        RelationEndpoint submittedFrom = new RelationEndpoint(fromItem.id(), fromItem.scope());
        RelationEndpoint submittedTo = new RelationEndpoint(toItem.id(), toItem.scope());
        RelationEndpoint src = type.swapsEndpointsOnSubmit() ? submittedTo : submittedFrom;
        RelationEndpoint dst = type.swapsEndpointsOnSubmit() ? submittedFrom : submittedTo;
        return new WorkItemRelation(
                requiredId,
                src,
                dst,
                type.storageKind(),
                AuditMetadata.createdBy(actor, occurredAt));
    }

    /** Restores a committed edge from persistence; same invariants as {@link #relate}. */
    public static WorkItemRelation reconstitute(
            WorkItemRelationId id,
            RelationEndpoint src,
            RelationEndpoint dst,
            WorkItemRelationType kind,
            AuditMetadata audit) {
        return new WorkItemRelation(id, src, dst, kind, audit);
    }

    /**
     * Returns the API-facing directions of this edge: a stored {@code BLOCKS(src, dst)} is
     * presented as {@code src BLOCKS dst} and {@code dst DEPENDS_ON src}; {@code SPLIT_FROM} has
     * a single view.
     */
    public List<RelationView> views() {
        if (kind == WorkItemRelationType.SPLIT_FROM) {
            return List.of(new RelationView(kind, src.workItemId(), dst.workItemId()));
        }
        return List.of(
                new RelationView(WorkItemRelationType.BLOCKS, src.workItemId(), dst.workItemId()),
                new RelationView(
                        WorkItemRelationType.DEPENDS_ON, dst.workItemId(), src.workItemId()));
    }

    public WorkItemRelationId id() {
        return id;
    }

    /** Stored src endpoint: the blocking item, or the split child. */
    public RelationEndpoint src() {
        return src;
    }

    /** Stored dst endpoint: the blocked item, or the split source. */
    public RelationEndpoint dst() {
        return dst;
    }

    public WorkItemRelationType kind() {
        return kind;
    }

    public AuditMetadata audit() {
        return audit;
    }
}

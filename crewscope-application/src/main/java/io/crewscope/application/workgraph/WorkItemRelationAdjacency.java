package io.crewscope.application.workgraph;

import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workgraph.BlockingChain;
import io.crewscope.domain.workgraph.WorkItemRelation;
import io.crewscope.domain.workgraph.WorkItemRelationId;
import io.crewscope.domain.workitem.WorkItemId;
import java.util.List;
import java.util.Objects;

/**
 * Every stored relation touching one anchor WorkItem, bounded at {@link BlockingChain#MAX_ROWS}
 * rows with explicit truncation — the ADR-033 guarantee that no WorkGraph read returns an
 * unbounded result.
 */
public record WorkItemRelationAdjacency(
        WorkItemId anchor, List<Edge> edges, boolean truncatedByRows) {

    public WorkItemRelationAdjacency {
        Objects.requireNonNull(anchor, "anchor");
        edges = List.copyOf(Objects.requireNonNull(edges, "edges"));
        if (edges.size() > BlockingChain.MAX_ROWS) {
            throw new IllegalArgumentException(
                    "edges must not exceed " + BlockingChain.MAX_ROWS + " rows");
        }
    }

    /** Direction of one edge relative to the anchor, in stored {@code (src → dst)} terms. */
    public enum Direction {
        /** The anchor sits on the src side: it blocks the other endpoint, or split from it. */
        OUTGOING,
        /** The anchor sits on the dst side: the other endpoint blocks it, or is its split source. */
        INCOMING
    }

    /** One touching edge, projected for presentation from the stored relation. */
    public record Edge(
            WorkItemRelationId relationId,
            Direction direction,
            WorkItemId otherWorkItemId,
            WorkProjectId otherProjectId,
            boolean crossProject,
            List<WorkItemRelation.RelationView> views,
            UtcTimestamp createdAt) {

        public Edge {
            Objects.requireNonNull(relationId, "relationId");
            Objects.requireNonNull(direction, "direction");
            Objects.requireNonNull(otherWorkItemId, "otherWorkItemId");
            Objects.requireNonNull(otherProjectId, "otherProjectId");
            views = List.copyOf(Objects.requireNonNull(views, "views"));
            Objects.requireNonNull(createdAt, "createdAt");
        }

        /** Maps one stored relation into the anchor-relative projection. */
        public static Edge of(WorkItemId anchor, WorkItemRelation relation) {
            Objects.requireNonNull(anchor, "anchor");
            WorkItemRelation requiredRelation = Objects.requireNonNull(relation, "relation");
            boolean anchorIsSrc = requiredRelation.src().workItemId().equals(anchor);
            WorkItemRelation.RelationEndpoint other =
                    anchorIsSrc ? requiredRelation.dst() : requiredRelation.src();
            boolean crossProject =
                    !requiredRelation.src().scope().projectId()
                            .equals(requiredRelation.dst().scope().projectId());
            return new Edge(
                    requiredRelation.id(),
                    anchorIsSrc ? Direction.OUTGOING : Direction.INCOMING,
                    other.workItemId(),
                    other.scope().projectId(),
                    crossProject,
                    requiredRelation.views(),
                    requiredRelation.audit().createdAt());
        }
    }
}

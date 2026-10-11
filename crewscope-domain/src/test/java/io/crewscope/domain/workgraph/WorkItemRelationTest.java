package io.crewscope.domain.workgraph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.workgraph.event.WorkItemRelationCreated;
import io.crewscope.domain.workgraph.event.WorkItemRelationRemoved;
import io.crewscope.domain.workitem.WorkItem;
import java.util.List;
import org.junit.jupiter.api.Test;

/** ADR-033 normalization and invariants: direction, self edge, org scope, API duality. */
class WorkItemRelationTest {

    @Test
    void normalizesDependsOnIntoTheReverseBlockingEdge() {
        WorkGraphDomainFixture fixture = WorkGraphDomainFixture.create();
        WorkItem itemX = fixture.itemIn(fixture.projectA);
        WorkItem itemY = fixture.itemIn(fixture.projectA);

        WorkItemRelation relation =
                WorkItemRelation.relate(
                        WorkItemRelationId.generate(),
                        WorkItemRelationType.DEPENDS_ON,
                        itemX,
                        itemY,
                        fixture.actorId(),
                        WorkGraphDomainFixture.CREATED_AT);

        assertEquals(WorkItemRelationType.BLOCKS, relation.kind());
        assertEquals(itemY.id(), relation.src().workItemId());
        assertEquals(itemX.id(), relation.dst().workItemId());
    }

    @Test
    void storesBlocksWithoutSwappingAndSplitFromChildOnSrcSide() {
        WorkGraphDomainFixture fixture = WorkGraphDomainFixture.create();
        WorkItem blocker = fixture.itemIn(fixture.projectA);
        WorkItem blocked = fixture.itemIn(fixture.projectA);
        WorkItem source = fixture.itemIn(fixture.projectA);

        WorkItemRelation blocks =
                WorkItemRelation.relate(
                        WorkItemRelationId.generate(),
                        WorkItemRelationType.BLOCKS,
                        blocker,
                        blocked,
                        fixture.actorId(),
                        WorkGraphDomainFixture.CREATED_AT);
        assertEquals(WorkItemRelationType.BLOCKS, blocks.kind());
        assertEquals(blocker.id(), blocks.src().workItemId());
        assertEquals(blocked.id(), blocks.dst().workItemId());

        WorkItemRelation split =
                WorkItemRelation.relate(
                        WorkItemRelationId.generate(),
                        WorkItemRelationType.SPLIT_FROM,
                        blocked,
                        source,
                        fixture.actorId(),
                        WorkGraphDomainFixture.CREATED_AT);
        assertEquals(WorkItemRelationType.SPLIT_FROM, split.kind());
        assertEquals(blocked.id(), split.src().workItemId());
        assertEquals(source.id(), split.dst().workItemId());
    }

    @Test
    void exposesBothApiViewsForBlockingEdgesAndOneForSplitProvenance() {
        WorkGraphDomainFixture fixture = WorkGraphDomainFixture.create();
        WorkItem blocker = fixture.itemIn(fixture.projectA);
        WorkItem blocked = fixture.itemIn(fixture.projectA);

        WorkItemRelation blocks =
                WorkItemRelation.relate(
                        WorkItemRelationId.generate(),
                        WorkItemRelationType.BLOCKS,
                        blocker,
                        blocked,
                        fixture.actorId(),
                        WorkGraphDomainFixture.CREATED_AT);
        assertEquals(
                List.of(
                        new WorkItemRelation.RelationView(
                                WorkItemRelationType.BLOCKS, blocker.id(), blocked.id()),
                        new WorkItemRelation.RelationView(
                                WorkItemRelationType.DEPENDS_ON, blocked.id(), blocker.id())),
                blocks.views());

        WorkItemRelation split =
                WorkItemRelation.relate(
                        WorkItemRelationId.generate(),
                        WorkItemRelationType.SPLIT_FROM,
                        blocked,
                        blocker,
                        fixture.actorId(),
                        WorkGraphDomainFixture.CREATED_AT);
        assertEquals(
                List.of(
                        new WorkItemRelation.RelationView(
                                WorkItemRelationType.SPLIT_FROM, blocked.id(), blocker.id())),
                split.views());
    }

    @Test
    void keepsEndpointsFromTwoWorkProjectsOfOneTeam() {
        WorkGraphDomainFixture fixture = WorkGraphDomainFixture.create();
        WorkItem itemA = fixture.itemIn(fixture.projectA);
        WorkItem itemB = fixture.itemIn(fixture.projectB);

        WorkItemRelation relation =
                WorkItemRelation.relate(
                        WorkItemRelationId.generate(),
                        WorkItemRelationType.BLOCKS,
                        itemA,
                        itemB,
                        fixture.actorId(),
                        WorkGraphDomainFixture.CREATED_AT);

        assertEquals(fixture.projectA.id(), relation.src().scope().projectId());
        assertEquals(fixture.projectB.id(), relation.dst().scope().projectId());
        assertEquals(
                fixture.organizationId, relation.src().scope().organizationId());
        assertEquals(
                fixture.team.team().id(), relation.src().scope().teamId());
    }

    @Test
    void rejectsSelfReference() {
        WorkGraphDomainFixture fixture = WorkGraphDomainFixture.create();
        WorkItem item = fixture.itemIn(fixture.projectA);

        assertThrows(
                DomainValidationException.class,
                () ->
                        WorkItemRelation.relate(
                                WorkItemRelationId.generate(),
                                WorkItemRelationType.BLOCKS,
                                item,
                                item,
                                fixture.actorId(),
                                WorkGraphDomainFixture.CREATED_AT));
    }

    @Test
    void rejectsCrossOrganizationEndpoints() {
        WorkGraphDomainFixture fixture = WorkGraphDomainFixture.create();
        WorkGraphDomainFixture other = WorkGraphDomainFixture.create();
        WorkItem itemA = fixture.itemIn(fixture.projectA);
        WorkItem itemB = other.itemIn(other.projectA);

        assertThrows(
                DomainValidationException.class,
                () ->
                        WorkItemRelation.relate(
                                WorkItemRelationId.generate(),
                                WorkItemRelationType.BLOCKS,
                                itemA,
                                itemB,
                                fixture.actorId(),
                                WorkGraphDomainFixture.CREATED_AT));
    }

    @Test
    void reconstituteRejectsTheSubmissionOnlyKind() {
        WorkGraphDomainFixture fixture = WorkGraphDomainFixture.create();
        WorkItem itemA = fixture.itemIn(fixture.projectA);
        WorkItem itemB = fixture.itemIn(fixture.projectA);
        WorkItemRelation relation =
                WorkItemRelation.relate(
                        WorkItemRelationId.generate(),
                        WorkItemRelationType.BLOCKS,
                        itemA,
                        itemB,
                        fixture.actorId(),
                        WorkGraphDomainFixture.CREATED_AT);

        assertThrows(
                DomainValidationException.class,
                () ->
                        WorkItemRelation.reconstitute(
                                relation.id(),
                                relation.src(),
                                relation.dst(),
                                WorkItemRelationType.DEPENDS_ON,
                                relation.audit()));
    }

    @Test
    void typeHelpersCoverTheThreeKinds() {
        assertEquals(WorkItemRelationType.BLOCKS, WorkItemRelationType.BLOCKS.storageKind());
        assertEquals(WorkItemRelationType.BLOCKS, WorkItemRelationType.DEPENDS_ON.storageKind());
        assertEquals(WorkItemRelationType.SPLIT_FROM, WorkItemRelationType.SPLIT_FROM.storageKind());
        assertFalse(WorkItemRelationType.BLOCKS.swapsEndpointsOnSubmit());
        assertTrue(WorkItemRelationType.DEPENDS_ON.swapsEndpointsOnSubmit());
        assertFalse(WorkItemRelationType.SPLIT_FROM.swapsEndpointsOnSubmit());
        assertTrue(WorkItemRelationType.BLOCKS.participatesInScheduling());
        assertTrue(WorkItemRelationType.DEPENDS_ON.participatesInScheduling());
        assertFalse(WorkItemRelationType.SPLIT_FROM.participatesInScheduling());
    }

    @Test
    void eventsCarryStoredDirectionAndBothProjects() {
        WorkGraphDomainFixture fixture = WorkGraphDomainFixture.create();
        WorkItem itemA = fixture.itemIn(fixture.projectA);
        WorkItem itemB = fixture.itemIn(fixture.projectB);
        WorkItemRelation relation =
                WorkItemRelation.relate(
                        WorkItemRelationId.generate(),
                        WorkItemRelationType.DEPENDS_ON,
                        itemA,
                        itemB,
                        fixture.actorId(),
                        WorkGraphDomainFixture.CREATED_AT);

        WorkItemRelationCreated created = WorkItemRelationCreated.from(relation);
        assertEquals(relation.id().value(), created.relationId());
        assertEquals(itemB.id().value(), created.srcWorkItemId());
        assertEquals(itemA.id().value(), created.dstWorkItemId());
        assertEquals(WorkItemRelationType.BLOCKS, created.kind());
        assertEquals(fixture.projectB.id().value(), created.srcProjectId());
        assertEquals(fixture.projectA.id().value(), created.dstProjectId());

        WorkItemRelationRemoved removed = WorkItemRelationRemoved.from(relation);
        assertEquals(created.relationId(), removed.relationId());
        assertEquals(created.kind(), removed.kind());
        assertEquals(created.srcWorkItemId(), removed.srcWorkItemId());
        assertEquals(created.dstWorkItemId(), removed.dstWorkItemId());
    }
}

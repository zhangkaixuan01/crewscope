package io.crewscope.domain.workitem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WorkItemM1Test {

    @Test
    void createsCompleteNativeWorkItemWithProjectKeyAndPlanningFields() {
        WorkItemDomainFixture fixture = WorkItemDomainFixture.create();

        WorkItem item = fixture.nativeWorkItem();

        assertEquals(WorkItemScope.from(fixture.project), item.scope());
        assertEquals(WorkItemType.TASK, item.type());
        assertEquals("Markdown description", item.description().orElseThrow());
        assertEquals(WorkItemPriority.HIGH, item.priority());
        assertEquals(Set.of(new WorkItemLabel("backend")), item.labels());
        assertTrue(item.dueAt().isPresent());
        assertEquals(WorkItemSource.CREWSCOPE, item.source());
        assertTrue(item.sourceReference().isEmpty());
    }

    @Test
    void rejectsKeyOutsideProjectPrefixAndOverlongKey() {
        WorkItemDomainFixture fixture = WorkItemDomainFixture.create();

        DomainValidationException prefixFailure = assertThrows(
                DomainValidationException.class,
                () -> WorkItem.createNative(
                        WorkItemId.generate(),
                        fixture.project,
                        new WorkItemKey("OPS-1"),
                        WorkItemType.TASK,
                        "Wrong key",
                        Optional.empty(),
                        WorkItemPriority.MEDIUM,
                        Set.of(),
                        Optional.empty(),
                        fixture.owner,
                        WorkItemDomainFixture.CREATED_AT));
        DomainValidationException lengthFailure = assertThrows(
                DomainValidationException.class,
                () -> new WorkItemKey("CRW-" + "9".repeat(WorkItemKey.MAX_LENGTH)));

        assertEquals("workItem.key", prefixFailure.error().details().get("field"));
        assertEquals("workItem.key", lengthFailure.error().details().get("field"));
    }

    @Test
    void archivedProjectRejectsNewWork() {
        WorkItemDomainFixture fixture = WorkItemDomainFixture.create();
        WorkProject archived = fixture.project.archive(
                fixture.owner, UtcTimestamp.parse("2026-08-07T22:01:00Z"));

        DomainValidationException failure = assertThrows(
                DomainValidationException.class,
                () -> WorkItem.createNative(
                        WorkItemId.generate(),
                        archived,
                        new WorkItemKey("CRW-2"),
                        WorkItemType.BUG,
                        "Archived project",
                        Optional.empty(),
                        WorkItemPriority.HIGH,
                        Set.of(),
                        Optional.empty(),
                        fixture.owner,
                        UtcTimestamp.parse("2026-08-07T22:02:00Z")));

        assertEquals("workItem.projectId", failure.error().details().get("field"));
    }

    @Test
    void externalProjectionRequiresNonNativeSourceAndReference() {
        WorkItemDomainFixture fixture = WorkItemDomainFixture.create();
        WorkItem external = WorkItem.createExternalProjection(
                WorkItemId.generate(),
                fixture.project,
                new WorkItemKey("CRW-3"),
                WorkItemType.BUG,
                "Imported bug",
                Optional.empty(),
                WorkItemPriority.URGENT,
                Set.of(new WorkItemLabel("Imported")),
                Optional.empty(),
                WorkItemSource.JIRA,
                "JIRA-998",
                fixture.owner,
                WorkItemDomainFixture.CREATED_AT);

        assertEquals(WorkItemSource.JIRA, external.source());
        assertEquals("JIRA-998", external.sourceReference().orElseThrow());
        assertThrows(
                DomainValidationException.class,
                () -> WorkItem.createExternalProjection(
                        WorkItemId.generate(),
                        fixture.project,
                        new WorkItemKey("CRW-4"),
                        WorkItemType.TASK,
                        "Invalid source",
                        Optional.empty(),
                        WorkItemPriority.MEDIUM,
                        Set.of(),
                        Optional.empty(),
                        WorkItemSource.CREWSCOPE,
                        "native",
                        fixture.owner,
                        WorkItemDomainFixture.CREATED_AT));
    }

    @Test
    void revisesPlanningFieldsAsOneVersionedMutation() {
        WorkItemDomainFixture fixture = WorkItemDomainFixture.create();
        UtcTimestamp changedAt = UtcTimestamp.parse("2026-08-07T22:01:00Z");

        WorkItem revised = fixture.nativeWorkItem().revise(
                WorkItemType.FEATURE,
                "  Collaboration board  ",
                Optional.of("  Updated Markdown  "),
                WorkItemPriority.URGENT,
                Set.of(new WorkItemLabel("Frontend"), new WorkItemLabel("UX")),
                Optional.empty(),
                fixture.owner,
                changedAt);

        assertEquals(WorkItemType.FEATURE, revised.type());
        assertEquals("Collaboration board", revised.title());
        assertEquals("Updated Markdown", revised.description().orElseThrow());
        assertEquals(WorkItemPriority.URGENT, revised.priority());
        assertEquals(Set.of(new WorkItemLabel("frontend"), new WorkItemLabel("ux")), revised.labels());
        assertTrue(revised.dueAt().isEmpty());
        assertEquals(1, revised.version());
        assertEquals(fixture.owner.id(), revised.audit().updatedBy().orElseThrow());
    }

    @Test
    void rejectsMutationByPrincipalOutsideTeamOrganization() {
        WorkItemDomainFixture fixture = WorkItemDomainFixture.create();
        Principal outside = WorkItemDomainFixture.activeUser(
                io.crewscope.domain.shared.id.OrganizationId.generate(), "Outside");

        DomainValidationException failure = assertThrows(
                DomainValidationException.class,
                () -> fixture.nativeWorkItem().revise(
                        WorkItemType.TASK,
                        "Outside mutation",
                        Optional.empty(),
                        WorkItemPriority.LOW,
                        Set.of(),
                        Optional.empty(),
                        outside,
                        UtcTimestamp.parse("2026-08-07T22:01:00Z")));

        assertEquals("workItem.updatedByPrincipalId", failure.error().details().get("field"));
    }

    @Test
    void blockedReviewCanReturnToReview() {
        WorkItemDomainFixture fixture = WorkItemDomainFixture.create();
        WorkItem item = fixture.nativeWorkItem()
                .transitionTo(
                        WorkItemStatus.READY,
                        fixture.owner,
                        UtcTimestamp.parse("2026-08-07T22:01:00Z"))
                .transitionTo(
                        WorkItemStatus.IN_PROGRESS,
                        fixture.owner,
                        UtcTimestamp.parse("2026-08-07T22:02:00Z"))
                .transitionTo(
                        WorkItemStatus.IN_REVIEW,
                        fixture.owner,
                        UtcTimestamp.parse("2026-08-07T22:03:00Z"))
                .transitionTo(
                        WorkItemStatus.BLOCKED,
                        fixture.owner,
                        UtcTimestamp.parse("2026-08-07T22:04:00Z"))
                .transitionTo(
                        WorkItemStatus.IN_REVIEW,
                        fixture.owner,
                        UtcTimestamp.parse("2026-08-07T22:05:00Z"));

        assertEquals(WorkItemStatus.IN_REVIEW, item.status());
        assertEquals(5, item.version());
    }

    @Test
    void onlyTerminalWorkCanBeArchivedAndArchivedWorkIsImmutable() {
        WorkItemDomainFixture fixture = WorkItemDomainFixture.create();
        WorkItem active = fixture.nativeWorkItem();

        assertThrows(
                InvalidStateTransitionException.class,
                () -> active.transitionTo(
                        WorkItemStatus.ARCHIVED,
                        fixture.owner,
                        UtcTimestamp.parse("2026-08-07T22:01:00Z")));

        WorkItem archived = active.transitionTo(
                        WorkItemStatus.CANCELLED,
                        fixture.owner,
                        UtcTimestamp.parse("2026-08-07T22:01:00Z"))
                .transitionTo(
                        WorkItemStatus.ARCHIVED,
                        fixture.owner,
                        UtcTimestamp.parse("2026-08-07T22:02:00Z"));

        assertEquals(WorkItemStatus.ARCHIVED, archived.status());
        assertFalse(archived.acceptsCollaboration());
        assertThrows(
                InvalidStateTransitionException.class,
                () -> archived.revise(
                        WorkItemType.TASK,
                        "Cannot edit",
                        Optional.empty(),
                        WorkItemPriority.MEDIUM,
                        Set.of(),
                        Optional.empty(),
                        fixture.owner,
                        UtcTimestamp.parse("2026-08-07T22:03:00Z")));
    }
}

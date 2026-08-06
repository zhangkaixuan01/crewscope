package io.crewscope.domain.workitem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.domain.shared.error.DomainErrorCode;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import org.junit.jupiter.api.Test;

class WorkItemTest {

    private static final WorkItemScope SCOPE = new WorkItemScope(
            OrganizationId.generate(),
            TeamId.generate(),
            WorkspaceId.generate(),
            WorkProjectId.generate());
    private static final PrincipalId CREATOR = PrincipalId.generate();
    private static final PrincipalId REVIEWER = PrincipalId.generate();
    private static final UtcTimestamp CREATED_AT = UtcTimestamp.parse("2026-08-06T10:00:00Z");

    @Test
    void followsTheDeliveryStateMachine() {
        WorkItem workItem = WorkItem.create(
                        WorkItemId.generate(),
                        SCOPE,
                        new WorkItemKey("CRW-1024"),
                        "Initialize CrewScope",
                        CREATOR,
                        CREATED_AT)
                .transitionTo(
                        WorkItemStatus.READY,
                        CREATOR,
                        UtcTimestamp.parse("2026-08-06T10:01:00Z"))
                .transitionTo(
                        WorkItemStatus.IN_PROGRESS,
                        CREATOR,
                        UtcTimestamp.parse("2026-08-06T10:02:00Z"))
                .transitionTo(
                        WorkItemStatus.IN_REVIEW,
                        CREATOR,
                        UtcTimestamp.parse("2026-08-06T10:03:00Z"))
                .transitionTo(
                        WorkItemStatus.DONE,
                        REVIEWER,
                        UtcTimestamp.parse("2026-08-06T10:04:00Z"));

        assertEquals(WorkItemStatus.DONE, workItem.status());
        assertEquals(4, workItem.version());
        assertEquals(SCOPE, workItem.scope());
        assertEquals(CREATOR, workItem.audit().createdBy().orElseThrow());
        assertEquals(REVIEWER, workItem.audit().updatedBy().orElseThrow());
    }

    @Test
    void rejectsInvalidTransition() {
        WorkItem workItem = WorkItem.create(
                WorkItemId.generate(),
                SCOPE,
                new WorkItemKey("CRW-1025"),
                "Invalid transition",
                CREATOR,
                CREATED_AT);

        InvalidStateTransitionException failure = assertThrows(
                InvalidStateTransitionException.class,
                () -> workItem.transitionTo(
                        WorkItemStatus.DONE,
                        CREATOR,
                        UtcTimestamp.parse("2026-08-06T10:01:00Z")));

        assertEquals(DomainErrorCode.INVALID_STATE_TRANSITION, failure.error().code());
        assertEquals("BACKLOG", failure.error().details().get("currentState"));
        assertEquals("DONE", failure.error().details().get("targetState"));
    }

    @Test
    void normalizesTitleAtTheDomainBoundary() {
        WorkItem workItem = WorkItem.create(
                WorkItemId.generate(),
                SCOPE,
                new WorkItemKey("CRW-1026"),
                "  Normalized title  ",
                CREATOR,
                CREATED_AT);

        assertEquals("Normalized title", workItem.title());
    }

    @Test
    void rejectsBlankTitleWithAStableDomainError() {
        DomainValidationException failure = assertThrows(
                DomainValidationException.class,
                () -> WorkItem.create(
                        WorkItemId.generate(),
                        SCOPE,
                        new WorkItemKey("CRW-1027"),
                        "  ",
                        CREATOR,
                        CREATED_AT));

        assertEquals(DomainErrorCode.INVALID_VALUE, failure.error().code());
        assertEquals("workItem.title", failure.error().details().get("field"));
    }

    @Test
    void rejectsATitleLongerThanThePersistenceContract() {
        DomainValidationException failure = assertThrows(
                DomainValidationException.class,
                () -> WorkItem.create(
                        WorkItemId.generate(),
                        SCOPE,
                        new WorkItemKey("CRW-1029"),
                        "x".repeat(WorkItem.MAX_TITLE_LENGTH + 1),
                        CREATOR,
                        CREATED_AT));

        assertEquals(DomainErrorCode.INVALID_VALUE, failure.error().code());
        assertEquals("workItem.title", failure.error().details().get("field"));
    }

    @Test
    void rejectsInvalidWorkItemKeyWithAStableDomainError() {
        DomainValidationException failure =
                assertThrows(DomainValidationException.class, () -> new WorkItemKey("crew-1"));

        assertEquals(DomainErrorCode.INVALID_VALUE, failure.error().code());
        assertEquals("workItem.key", failure.error().details().get("field"));
    }

    @Test
    void rejectsAnAuditTimestampBeforeTheLastModification() {
        WorkItem workItem = WorkItem.create(
                        WorkItemId.generate(),
                        SCOPE,
                        new WorkItemKey("CRW-1028"),
                        "Audit time",
                        CREATOR,
                        CREATED_AT)
                .transitionTo(
                        WorkItemStatus.READY,
                        CREATOR,
                        UtcTimestamp.parse("2026-08-06T10:01:00Z"));

        DomainValidationException failure = assertThrows(
                DomainValidationException.class,
                () -> workItem.transitionTo(
                        WorkItemStatus.IN_PROGRESS,
                        CREATOR,
                        UtcTimestamp.parse("2026-08-06T10:00:30Z")));

        assertEquals("audit.updatedAt", failure.error().details().get("field"));
    }
}

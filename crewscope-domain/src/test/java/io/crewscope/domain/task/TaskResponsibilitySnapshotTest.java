package io.crewscope.domain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.responsibility.ResponsibilityAssignment;
import io.crewscope.domain.responsibility.ResponsibilityAssignmentStatus;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TaskResponsibilitySnapshotTest {

    @Test
    void capturesCompleteImmutableResponsibilityEvidence() {
        TaskDomainFixture fixture = new TaskDomainFixture();
        List<ResponsibilityAssignment> source = new ArrayList<>(List.of(
                fixture.ownerAssignment,
                fixture.executorAssignment,
                fixture.reviewerAssignment));

        TaskResponsibilitySnapshot snapshot = TaskResponsibilitySnapshot.capture(
                fixture.workItem, source, TaskDomainFixture.CREATED_AT);
        source.clear();

        assertEquals(fixture.scope, snapshot.scope());
        assertEquals(fixture.workItem.id(), snapshot.workItemId());
        assertEquals(3, snapshot.entries().size());
        assertEquals(1, snapshot.byRole(ResponsibilityRole.OWNER).size());
        assertEquals(1, snapshot.byRole(ResponsibilityRole.EXECUTOR).size());
        assertEquals(1, snapshot.byRole(ResponsibilityRole.REVIEWER).size());
        assertThrows(
                UnsupportedOperationException.class,
                () -> snapshot.entries().add(snapshot.entries().get(0)));
    }

    @Test
    void staysUnchangedWhenSourceAssignmentIsLaterReleased() {
        TaskDomainFixture fixture = new TaskDomainFixture();
        TaskResponsibilitySnapshot snapshot = fixture.snapshot();

        ResponsibilityAssignment released = fixture.executorAssignment.release(
                fixture.owner, TaskDomainFixture.LATER);

        assertEquals(ResponsibilityAssignmentStatus.RELEASED, released.status());
        TaskResponsibilitySnapshotEntry executor =
                snapshot.byRole(ResponsibilityRole.EXECUTOR).get(0);
        assertEquals(fixture.executorAssignment.id(), executor.assignmentId());
        assertEquals(0, executor.assignmentVersion());
    }

    @Test
    void requiresExactlyOneOwnerAndAtLeastOneExecutor() {
        TaskDomainFixture fixture = new TaskDomainFixture();

        DomainValidationException missingOwner = assertThrows(
                DomainValidationException.class,
                () -> TaskResponsibilitySnapshot.capture(
                        fixture.workItem,
                        List.of(fixture.executorAssignment),
                        TaskDomainFixture.CREATED_AT));
        DomainValidationException missingExecutor = assertThrows(
                DomainValidationException.class,
                () -> TaskResponsibilitySnapshot.capture(
                        fixture.workItem,
                        List.of(fixture.ownerAssignment),
                        TaskDomainFixture.CREATED_AT));

        assertEquals("taskResponsibilitySnapshot.owner", missingOwner.error().details().get("field"));
        assertEquals(
                "taskResponsibilitySnapshot.executor",
                missingExecutor.error().details().get("field"));
    }

    @Test
    void rejectsReleasedOrCrossWorkItemAssignments() {
        TaskDomainFixture fixture = new TaskDomainFixture();
        ResponsibilityAssignment released = ResponsibilityAssignment.reconstitute(
                fixture.executorAssignment.id(),
                fixture.executorAssignment.scope(),
                fixture.executorAssignment.workItemId(),
                fixture.executorAssignment.role(),
                fixture.executorAssignment.actorPrincipalId(),
                fixture.executorAssignment.actorType(),
                fixture.executorAssignment.actorMemberId(),
                ResponsibilityAssignmentStatus.RELEASED,
                fixture.executorAssignment.assignedByPrincipalId(),
                fixture.executorAssignment.assignedAt(),
                fixture.executorAssignment.acceptedAt(),
                Optional.of(fixture.owner.id()),
                Optional.of(TaskDomainFixture.LATER),
                1,
                new AuditMetadata(
                        fixture.executorAssignment.audit().createdBy(),
                        fixture.executorAssignment.audit().createdAt(),
                        Optional.of(fixture.owner.id()),
                        TaskDomainFixture.LATER));

        DomainValidationException failure = assertThrows(
                DomainValidationException.class,
                () -> TaskResponsibilitySnapshot.capture(
                        fixture.workItem,
                        List.of(fixture.ownerAssignment, released),
                        TaskDomainFixture.LATER));

        assertEquals(
                "taskResponsibilitySnapshot.assignments",
                failure.error().details().get("field"));
        assertTrue(failure.getMessage().contains("active Assignments"));
    }
}

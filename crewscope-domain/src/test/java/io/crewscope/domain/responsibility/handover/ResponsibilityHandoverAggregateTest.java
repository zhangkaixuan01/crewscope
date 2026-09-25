package io.crewscope.domain.responsibility.handover;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.responsibility.ResponsibilityAssignment;
import io.crewscope.domain.responsibility.ResponsibilityAssignmentId;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** State-machine and invariants proof for the ADR-038 handover job/item aggregates. */
class ResponsibilityHandoverAggregateTest {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-09-25T08:00:00Z");
    private static final UtcTimestamp LATER = UtcTimestamp.parse("2026-09-25T08:05:00Z");

    @Test
    void jobAdvancesThroughRunningToTerminalStatesWithVersionBumps() {
        ResponsibilityHandoverJob job = job();

        assertEquals(HandoverJobStatus.PENDING, job.status());
        ResponsibilityHandoverJob running = job.markRunning(actor(), NOW);
        assertEquals(HandoverJobStatus.RUNNING, running.status());
        assertEquals(1, running.version());

        ResponsibilityHandoverJob completed = running.markCompleted(actor(), LATER);
        assertEquals(HandoverJobStatus.COMPLETED, completed.status());
        assertEquals(2, completed.version());
        assertTrue(completed.isTerminal());
        assertTrue(job.audit().updatedAt().compareTo(completed.audit().updatedAt()) <= 0);
    }

    @Test
    void jobRejectsTransitionsOutOfTerminalStates() {
        ResponsibilityHandoverJob cancelled = job().markRunning(actor(), NOW).markCancelled(actor(), LATER);

        assertThrows(
                InvalidStateTransitionException.class,
                () -> cancelled.markRunning(actor(), LATER));
        assertThrows(
                InvalidStateTransitionException.class,
                () -> cancelled.markCompleted(actor(), LATER));
        assertThrows(
                InvalidStateTransitionException.class,
                () -> cancelled.markCancelled(actor(), LATER));
    }

    @Test
    void jobPinsTheSourceAuthorizationSnapshotAndCommandKey() {
        ResponsibilityHandoverJob job = job();

        assertEquals(3, job.sourceAuthorizationVersion());
        assertEquals("handover-key-1", job.commandId());
        assertEquals(ResponsibilityRole.EXECUTOR, job.role());

        assertThrows(
                DomainValidationException.class,
                () ->
                        ResponsibilityHandoverJob.create(
                                ResponsibilityHandoverJobId.generate(),
                                org(),
                                team(),
                                member(),
                                actor(),
                                ResponsibilityRole.OWNER,
                                " ",
                                actor(),
                                1,
                                NOW));
        assertThrows(
                DomainValidationException.class,
                () ->
                        ResponsibilityHandoverJob.create(
                                ResponsibilityHandoverJobId.generate(),
                                org(),
                                team(),
                                member(),
                                actor(),
                                ResponsibilityRole.OWNER,
                                "key",
                                actor(),
                                0,
                                NOW));
    }

    @Test
    void itemRecordsDoneWithTheCreatedTargetAssignment() {
        ResponsibilityHandoverItem item = item();

        assertEquals(HandoverItemState.PENDING, item.state());
        ResponsibilityAssignmentId result = ResponsibilityAssignmentId.generate();
        ResponsibilityHandoverItem done = item.markDone(actor(), result, LATER);

        assertEquals(HandoverItemState.DONE, done.state());
        assertEquals(Optional.of(result), done.resultAssignmentId());
        assertTrue(done.processedAt().isPresent());
        assertTrue(done.errorCode().isEmpty());
        assertEquals(1, done.version());
    }

    @Test
    void itemRequiresAReasonForStoppedStatesAndKeepsPendingClean() {
        ResponsibilityHandoverItem conflict =
                item().markConflict(actor(), "SOURCE_ASSIGNMENT_CHANGED", LATER);
        assertEquals(HandoverItemState.CONFLICT, conflict.state());
        assertEquals(Optional.of("SOURCE_ASSIGNMENT_CHANGED"), conflict.errorCode());

        ResponsibilityHandoverItem denied = item().markDenied(actor(), "TARGET_NOT_ELIGIBLE", LATER);
        assertEquals(HandoverItemState.DENIED, denied.state());

        assertThrows(
                InvalidStateTransitionException.class,
                () -> conflict.markDone(actor(), ResponsibilityAssignmentId.generate(), LATER));
        assertThrows(
                DomainValidationException.class,
                () -> item().markConflict(actor(), " ", LATER));
    }

    @Test
    void itemReconstituteRejectsInconsistentTerminalShapes() {
        ResponsibilityAssignmentId assignment = ResponsibilityAssignmentId.generate();
        assertThrows(
                DomainValidationException.class,
                () ->
                        ResponsibilityHandoverItem.reconstitute(
                                ResponsibilityHandoverItemId.generate(),
                                org(),
                                ResponsibilityHandoverJobId.generate(),
                                assignment,
                                new WorkItemId(UUID.randomUUID()),
                                scope(),
                                0,
                                HandoverItemState.DONE,
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                0,
                                AuditMetadata.createdBy(actor(), NOW)));
        assertThrows(
                DomainValidationException.class,
                () ->
                        ResponsibilityHandoverItem.reconstitute(
                                ResponsibilityHandoverItemId.generate(),
                                org(),
                                ResponsibilityHandoverJobId.generate(),
                                assignment,
                                new WorkItemId(UUID.randomUUID()),
                                scope(),
                                0,
                                HandoverItemState.DONE,
                                Optional.empty(),
                                Optional.of("STALE_REASON"),
                                Optional.of(NOW),
                                0,
                                AuditMetadata.createdBy(actor(), NOW)));
    }

    @Test
    void itemCreatePinsTheSourceAssignmentVersionAndScope() {
        ResponsibilityAssignmentId assignmentId = ResponsibilityAssignmentId.generate();
        WorkItemScope sourceScope = scope();
        WorkItemId workItemId = new WorkItemId(UUID.randomUUID());
        ResponsibilityAssignment source =
                ResponsibilityAssignment.reconstitute(
                        assignmentId,
                        sourceScope,
                        workItemId,
                        ResponsibilityRole.EXECUTOR,
                        actor(),
                        io.crewscope.domain.identity.PrincipalType.USER,
                        Optional.of(member()),
                        io.crewscope.domain.responsibility.ResponsibilityAssignmentStatus.ACTIVE,
                        actor(),
                        NOW,
                        NOW,
                        Optional.empty(),
                        Optional.empty(),
                        4,
                        AuditMetadata.createdBy(actor(), NOW));

        ResponsibilityHandoverItem item =
                ResponsibilityHandoverItem.create(
                        ResponsibilityHandoverItemId.generate(),
                        ResponsibilityHandoverJobId.generate(),
                        source,
                        actor(),
                        NOW);

        assertEquals(assignmentId, item.assignmentId());
        assertEquals(workItemId, item.workItemId());
        assertEquals(sourceScope, item.scope());
        assertEquals(4, item.expectedAssignmentVersion());
        assertTrue(item.isPending());
    }

    private static OrganizationId org() {
        return new OrganizationId(UUID.randomUUID());
    }

    private static TeamId team() {
        return new TeamId(UUID.randomUUID());
    }

    private static TeamMemberId member() {
        return new TeamMemberId(UUID.randomUUID());
    }

    private static PrincipalId actor() {
        return new PrincipalId(UUID.randomUUID());
    }

    private static WorkItemScope scope() {
        return new WorkItemScope(
                org(), team(), new WorkspaceId(UUID.randomUUID()),
                new WorkProjectId(UUID.randomUUID()));
    }

    private static ResponsibilityHandoverJob job() {
        return ResponsibilityHandoverJob.create(
                ResponsibilityHandoverJobId.generate(),
                org(),
                team(),
                member(),
                actor(),
                ResponsibilityRole.EXECUTOR,
                "handover-key-1",
                actor(),
                3,
                NOW);
    }

    private static ResponsibilityHandoverItem item() {
        return ResponsibilityHandoverItem.create(
                ResponsibilityHandoverItemId.generate(),
                ResponsibilityHandoverJobId.generate(),
                ResponsibilityAssignment.reconstitute(
                        ResponsibilityAssignmentId.generate(),
                        scope(),
                        new WorkItemId(UUID.randomUUID()),
                        ResponsibilityRole.EXECUTOR,
                        actor(),
                        io.crewscope.domain.identity.PrincipalType.USER,
                        Optional.of(member()),
                        io.crewscope.domain.responsibility.ResponsibilityAssignmentStatus.ACTIVE,
                        actor(),
                        NOW,
                        NOW,
                        Optional.empty(),
                        Optional.empty(),
                        2,
                        AuditMetadata.createdBy(actor(), NOW)),
                actor(),
                NOW);
    }
}

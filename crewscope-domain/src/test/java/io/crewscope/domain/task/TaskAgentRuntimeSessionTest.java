package io.crewscope.domain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.workspace.AgentProfile;
import io.crewscope.domain.workspace.AgentProfileId;
import io.crewscope.domain.workspace.AgentProfileStatus;
import io.crewscope.domain.workspace.AgentProfileType;
import io.crewscope.domain.workspace.WorkspaceScope;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TaskAgentRuntimeSessionTest {

    @Test
    void createsDeterministicTaskAndStepBindingsClosedOverProfileAndExecution() {
        RuntimeFixture fixture = new RuntimeFixture();

        TaskAgentRuntimeSession taskSession = fixture.taskSession();
        TaskAgentRuntimeSession retried = fixture.taskSession();
        TaskAgentRuntimeSession stepSession = fixture.stepSession();

        assertEquals(taskSession.id(), retried.id());
        assertEquals(taskSession.agentScopeKey(), retried.agentScopeKey());
        assertEquals(TaskAgentSessionPurpose.TASK, taskSession.purpose());
        assertTrue(taskSession.stepExecutionId().isEmpty());
        assertEquals(TaskAgentSessionPurpose.STEP, stepSession.purpose());
        assertEquals(fixture.step.id(), stepSession.stepExecutionId().orElseThrow());
        assertNotEquals(taskSession.id(), stepSession.id());
        assertTrue(stepSession.stateReference().belongsTo(stepSession.id()));
    }

    @Test
    void createsSpecialistBindingOnlyForMatchingSpecialistStepAndProfile() {
        RuntimeFixture fixture = new RuntimeFixture();
        Principal specialist = fixture.specialist();
        StepExecution specialistStep = fixture.stepFor(specialist);
        AgentProfile specialistProfile = fixture.profile(
                specialist, AgentProfileType.SPECIALIST, 2);

        TaskAgentRuntimeSession session = TaskAgentRuntimeSession.initializeSpecialist(
                fixture.planning.task,
                fixture.graph.execution(),
                specialistStep,
                specialistProfile,
                specialist,
                TaskPlanningFixture.STEP_AT);

        assertEquals(TaskAgentSessionPurpose.SPECIALIST, session.purpose());
        assertEquals(specialist.id(), session.agentPrincipalId());
        assertThrows(
                DomainValidationException.class,
                () -> TaskAgentRuntimeSession.initializeStep(
                        fixture.planning.task,
                        fixture.graph.execution(),
                        specialistStep,
                        specialistProfile,
                        specialist,
                        TaskPlanningFixture.STEP_AT));
    }

    @Test
    void createsStepLessReviewSessionOnlyForACompletedAttempt() {
        RuntimeFixture fixture = new RuntimeFixture();
        Principal reviewer = fixture.specialist();
        AgentProfile reviewerProfile = fixture.profile(
                reviewer, AgentProfileType.SPECIALIST, 0);
        TaskExecution running = fixture.graph.execution();

        // The advisory review opens on a finished attempt — the mirror image of execution
        // sessions, which require a non-terminal one.
        assertThrows(
                DomainValidationException.class,
                () -> TaskAgentRuntimeSession.initializeReview(
                        fixture.planning.task, running, reviewerProfile, reviewer,
                        TaskPlanningFixture.STEP_AT));
        TaskExecution completed = TaskExecution.reconstitute(
                running.id(), running.scope(), running.taskId(), running.attempt(),
                running.maxAttempts(), running.parentExecutionId(), running.priority(),
                running.notBefore(), TaskExecutionStatus.COMPLETED, running.waiting(),
                running.controlRequest(),
                Optional.of(new TaskExecutionTerminal(
                        TaskExecutionStatus.COMPLETED, fixture.planning.base.owner.id(),
                        TaskDomainFixture.CREATED_AT, Optional.empty())),
                running.planningContext(), running.lastFencingToken(), running.version(),
                running.audit());

        // Deterministic per (execution, profile) so re-resolution stays idempotent.
        TaskAgentRuntimeSession session = TaskAgentRuntimeSession.initializeReview(
                fixture.planning.task, completed, reviewerProfile, reviewer,
                TaskDomainFixture.CREATED_AT);
        TaskAgentRuntimeSession resolvedAgain = TaskAgentRuntimeSession.initializeReview(
                fixture.planning.task, completed, reviewerProfile, reviewer,
                TaskDomainFixture.CREATED_AT);

        assertEquals(TaskAgentSessionPurpose.REVIEW, session.purpose());
        assertTrue(session.stepExecutionId().isEmpty());
        assertEquals(session.id(), resolvedAgain.id());
        assertEquals(session.agentScopeKey(), resolvedAgain.agentScopeKey());
        assertTrue(session.canInvoke());
    }

    @Test
    void rejectsCrossExecutionStepAndWrongProfileIdentity() {
        RuntimeFixture fixture = new RuntimeFixture();
        AgentProfile forged = AgentProfile.reconstitute(
                fixture.profile.id(),
                fixture.profile.scope(),
                fixture.profile.workspaceId(),
                PrincipalId.generate(),
                Optional.empty(),
                AgentProfileType.TEAM,
                false,
                AgentProfileStatus.ACTIVE,
                fixture.profile.version(),
                fixture.profile.audit());

        assertThrows(
                DomainValidationException.class,
                () -> TaskAgentRuntimeSession.initializeTask(
                        fixture.planning.task,
                        fixture.graph.execution(),
                        forged,
                        fixture.planning.base.executor,
                        TaskPlanningFixture.STEP_AT));
    }

    @Test
    void allowsTheCodingSpecialistIdentityToOrchestrateTheWholeTask() {
        // The Coding specialist executes a delegated coding Task end-to-end with no Step
        // decomposition, so its worker prepare builds a TASK-purpose session; the type gate
        // used to reject SPECIALIST there and the execution died in recovery (M10-Q02).
        RuntimeFixture fixture = new RuntimeFixture();
        Principal codingSpecialist = fixture.specialist();
        AgentProfile codingProfile = fixture.profile(
                codingSpecialist, AgentProfileType.SPECIALIST, 1);

        TaskAgentRuntimeSession session = TaskAgentRuntimeSession.initializeTask(
                fixture.planning.task,
                fixture.graph.execution(),
                codingProfile,
                codingSpecialist,
                TaskPlanningFixture.STEP_AT);

        assertEquals(TaskAgentSessionPurpose.TASK, session.purpose());
        assertEquals(codingSpecialist.id(), session.agentPrincipalId());
        assertTrue(session.stepExecutionId().isEmpty());
        assertEquals(codingProfile.id(), session.agentProfileId());
    }

    @Test
    void allowsAPersonalAgentToOrchestrateAndOwnAnIsolatedSpecialistRole() {
        RuntimeFixture fixture = new RuntimeFixture();
        Principal personalAgent = fixture.personalAgent();
        AgentProfile personalProfile = fixture.profile(
                personalAgent, AgentProfileType.PERSONAL, 5);

        TaskAgentRuntimeSession session = TaskAgentRuntimeSession.initializeTask(
                fixture.planning.task,
                fixture.graph.execution(),
                personalProfile,
                personalAgent,
                TaskPlanningFixture.STEP_AT);

        assertEquals(TaskAgentSessionPurpose.TASK, session.purpose());
        assertEquals(personalAgent.id(), session.agentPrincipalId());
        TaskAgentRuntimeSession specialistSession =
                TaskAgentRuntimeSession.initializeSpecialist(
                        fixture.planning.task,
                        fixture.graph.execution(),
                        fixture.stepFor(personalAgent),
                        personalProfile,
                        personalAgent,
                        TaskPlanningFixture.STEP_AT);
        assertEquals(TaskAgentSessionPurpose.SPECIALIST, specialistSession.purpose());
        assertNotEquals(session.id(), specialistSession.id());
        assertThrows(
                DomainValidationException.class,
                () -> TaskAgentRuntimeSession.initializeStep(
                        fixture.planning.task,
                        fixture.graph.execution(),
                        fixture.step,
                        personalProfile,
                        personalAgent,
                        TaskPlanningFixture.STEP_AT));
    }

    static final class RuntimeFixture {
        final TaskPlanningFixture planning = new TaskPlanningFixture();
        final TaskPlanningFixture.PlanningGraph graph = planning.graph();
        final StepExecution step = StepExecution.create(
                StepExecutionId.generate(),
                planning.task,
                graph.execution(),
                graph.plan(),
                graph.plan().steps().get(0),
                3,
                planning.base.owner,
                TaskPlanningFixture.STEP_AT);
        final AgentProfile profile = profile(
                planning.base.executor, AgentProfileType.TEAM, 4);

        TaskAgentRuntimeSession taskSession() {
            return TaskAgentRuntimeSession.initializeTask(
                    planning.task,
                    graph.execution(),
                    profile,
                    planning.base.executor,
                    TaskPlanningFixture.STEP_AT);
        }

        TaskAgentRuntimeSession stepSession() {
            return TaskAgentRuntimeSession.initializeStep(
                    planning.task,
                    graph.execution(),
                    step,
                    profile,
                    planning.base.executor,
                    TaskPlanningFixture.STEP_AT);
        }

        AgentProfile profile(Principal agent, AgentProfileType type, long version) {
            return AgentProfile.reconstitute(
                    AgentProfileId.generate(),
                    WorkspaceScope.team(
                            planning.task.scope().organizationId(), planning.task.scope().teamId()),
                    planning.task.scope().workspaceId(),
                    agent.id(),
                    type == AgentProfileType.PERSONAL
                            ? Optional.of(io.crewscope.domain.team.TeamMemberId.generate())
                            : Optional.empty(),
                    type,
                    false,
                    AgentProfileStatus.ACTIVE,
                    version,
                    AuditMetadata.createdBy(planning.base.owner.id(), TaskDomainFixture.CREATED_AT));
        }

        Principal specialist() {
            return Principal.create(
                    PrincipalId.generate(),
                    PrincipalScope.team(
                            planning.task.scope().organizationId(), planning.task.scope().teamId()),
                    PrincipalType.SPECIALIST_AGENT,
                    Optional.of(planning.base.owner.id()),
                    "Code specialist",
                    Optional.empty(),
                    PrincipalVisibility.TEAM,
                    TaskDomainFixture.CREATED_AT);
        }

        Principal personalAgent() {
            return Principal.create(
                    PrincipalId.generate(),
                    PrincipalScope.team(
                            planning.task.scope().organizationId(), planning.task.scope().teamId()),
                    PrincipalType.PERSONAL_AGENT,
                    Optional.of(planning.base.owner.id()),
                    "Personal orchestrator",
                    Optional.empty(),
                    PrincipalVisibility.TEAM,
                    TaskDomainFixture.CREATED_AT);
        }

        StepExecution stepFor(Principal agent) {
            ExecutionPrincipalSnapshot executor = new ExecutionPrincipalSnapshot(
                    agent.id(),
                    step.executionPrincipal().assignmentId(),
                    step.executionPrincipal().assignmentVersion(),
                    step.executionPrincipal().responsibilitySnapshotHash());
            return StepExecution.reconstitute(
                    StepExecutionId.generate(),
                    step.scope(),
                    step.taskId(),
                    step.executionId(),
                    step.planVersionId(),
                    step.planVersionHash(),
                    step.planStepKey(),
                    step.sequence(),
                    step.critical(),
                    executor,
                    step.policySnapshotId(),
                    step.policySnapshotHash(),
                    step.safetyOverlay(),
                    step.runAttempt(),
                    step.maxRunAttempts(),
                    step.status(),
                    step.waitReason(),
                    step.checkpoint(),
                    step.failure(),
                    step.version(),
                    step.audit());
        }
    }
}

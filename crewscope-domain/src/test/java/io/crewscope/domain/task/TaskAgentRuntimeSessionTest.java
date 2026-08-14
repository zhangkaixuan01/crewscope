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
                    Optional.empty(),
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

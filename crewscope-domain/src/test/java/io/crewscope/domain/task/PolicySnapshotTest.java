package io.crewscope.domain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.agent.ResolvedAgentExecutionConfiguration;
import io.crewscope.domain.agent.ResolvedAgentExecutionTestFixture;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PolicySnapshotTest {

    @Test
    void pinsExecutorResponsibilityConfigurationBudgetAndCanonicalHash() {
        TaskPlanningFixture fixture = new TaskPlanningFixture();

        PolicySnapshot policy = fixture.policy();

        assertEquals(1, policy.revision());
        assertTrue(policy.parentSnapshotId().isEmpty());
        assertEquals(fixture.base.executor.id(), policy.executionPrincipal().principalId());
        assertEquals(
                fixture.base.executorAssignment.id(),
                policy.executionPrincipal().assignmentId());
        assertEquals(4, policy.agentProfileVersion());
        assertTrue(policy.allows(Set.of(ExecutionCapability.PLAN), Set.of("repository.read")));
        assertEquals(64, policy.snapshotHash().value().length());
        assertEquals(1, policy.schemaVersion());
        assertTrue(policy.agentExecutionConfiguration().isEmpty());
    }

    @Test
    void schemaV2PinsTheCompleteResolvedAgentConfigurationAndRestoresItsHash() {
        TaskPlanningFixture fixture = new TaskPlanningFixture();
        ResolvedAgentExecutionConfiguration resolved =
                ResolvedAgentExecutionTestFixture.create();
        PolicySnapshot policy = PolicySnapshot.initialV2(
                PolicySnapshotId.generate(),
                fixture.task,
                fixture.execution,
                fixture.base.executor,
                resolved,
                Set.of(ExecutionCapability.PLAN, ExecutionCapability.STRUCTURED_OUTPUT),
                Set.of("repository.read", "validation.run"),
                Set.of(),
                new PolicyBudget(20_000, 8, 8, 900),
                fixture.base.owner,
                TaskPlanningFixture.POLICY_AT);

        PolicySnapshot restored = PolicySnapshot.reconstituteV2(
                policy.id(),
                policy.scope(),
                policy.taskId(),
                policy.executionId(),
                policy.revision(),
                policy.parentSnapshotId(),
                policy.changeReason(),
                policy.executionPrincipal(),
                resolved,
                policy.capabilities(),
                policy.allowedTools(),
                policy.providerBindingIds(),
                policy.budget(),
                policy.snapshotHash(),
                policy.createdByPrincipalId(),
                policy.createdAt());

        assertEquals(2, policy.schemaVersion());
        assertEquals(resolved, policy.agentExecutionConfiguration().orElseThrow());
        assertEquals(policy.snapshotHash(), restored.snapshotHash());
        assertEquals(resolved.primary().priceRevision(),
                restored.agentExecutionConfiguration().orElseThrow().primary().priceRevision());
        assertThrows(
                DomainValidationException.class,
                () -> PolicySnapshot.reconstituteV2(
                        policy.id(),
                        policy.scope(),
                        policy.taskId(),
                        policy.executionId(),
                        policy.revision(),
                        policy.parentSnapshotId(),
                        policy.changeReason(),
                        policy.executionPrincipal(),
                        resolved,
                        policy.capabilities(),
                        policy.allowedTools(),
                        policy.providerBindingIds(),
                        policy.budget(),
                        TaskFactHash.sha256("tampered-v2"),
                        policy.createdByPrincipalId(),
                        policy.createdAt()));
    }

    @Test
    void unknownPolicySnapshotSchemaFailsClosed() {
        assertEquals(1, PolicySnapshot.requireSupportedSchemaVersion(1));
        assertEquals(2, PolicySnapshot.requireSupportedSchemaVersion(2));
        assertThrows(
                DomainValidationException.class,
                () -> PolicySnapshot.requireSupportedSchemaVersion(3));
    }

    @Test
    void rejectsExecutorOutsideCapturedResponsibility() {
        TaskPlanningFixture fixture = new TaskPlanningFixture();

        assertThrows(
                DomainValidationException.class,
                () -> ExecutionPrincipalSnapshot.resolve(
                        fixture.task.responsibilitySnapshot(), fixture.base.reviewer));
    }

    @Test
    void pinsAdvisoryReviewerSeatWithoutRequiringTheExecutorSeat() {
        TaskPlanningFixture fixture = new TaskPlanningFixture();
        ResolvedAgentExecutionConfiguration resolved =
                ResolvedAgentExecutionTestFixture.create();

        // An advisory Reviewer snapshot resolves the agent's own REVIEWER seat from the
        // review-time responsibility facts; before the pinned-role factory existed this path
        // reused the EXECUTOR invariant and a reviewer could never pin its model graph.
        PolicySnapshot reviewPolicy = PolicySnapshot.initialV2ForRole(
                PolicySnapshotId.generate(),
                fixture.task,
                fixture.execution,
                fixture.base.reviewer,
                ResponsibilityRole.REVIEWER,
                fixture.task.responsibilitySnapshot(),
                resolved,
                Set.of(ExecutionCapability.STRUCTURED_OUTPUT),
                Set.of("repository.read"),
                Set.of(),
                new PolicyBudget(65_536, 8, 1, 3_600),
                fixture.base.owner,
                TaskPlanningFixture.POLICY_AT);

        assertEquals(2, reviewPolicy.schemaVersion());
        assertEquals(
                fixture.base.reviewer.id(), reviewPolicy.executionPrincipal().principalId());
        assertEquals(
                fixture.base.reviewerAssignment.id(),
                reviewPolicy.executionPrincipal().assignmentId());
        // The Task's creation-time snapshot captures only the delegation seats (OWNER +
        // EXECUTOR): a reviewer is assigned after delegation, so pinning a reviewer policy
        // against creation-time facts can never find the seat.
        TaskResponsibilitySnapshot creationTimeSnapshot = TaskResponsibilitySnapshot.capture(
                fixture.base.workItem,
                java.util.List.of(fixture.base.ownerAssignment, fixture.base.executorAssignment),
                TaskDomainFixture.CREATED_AT);
        assertThrows(
                DomainValidationException.class,
                () -> PolicySnapshot.initialV2ForRole(
                        PolicySnapshotId.generate(),
                        fixture.task,
                        fixture.execution,
                        fixture.base.reviewer,
                        ResponsibilityRole.REVIEWER,
                        creationTimeSnapshot,
                        resolved,
                        Set.of(ExecutionCapability.STRUCTURED_OUTPUT),
                        Set.of("repository.read"),
                        Set.of(),
                        new PolicyBudget(65_536, 8, 1, 3_600),
                        fixture.base.owner,
                        TaskPlanningFixture.POLICY_AT));
        // The single-fact invariant stays role-exact in the other direction too: the coding
        // Executor holds no REVIEWER seat, so it cannot pin a reviewer policy.
        assertThrows(
                DomainValidationException.class,
                () -> ExecutionPrincipalSnapshot.resolve(
                        fixture.task.responsibilitySnapshot(),
                        fixture.base.executor,
                        ResponsibilityRole.REVIEWER));
    }

    @Test
    void createsImmutableParentedRevisionAndDetectsPermissionExpansion() {
        TaskPlanningFixture fixture = new TaskPlanningFixture();
        PolicySnapshot parent = fixture.policy();

        PolicySnapshot expanded = PolicySnapshot.supersede(
                PolicySnapshotId.generate(),
                parent,
                PolicySnapshotChangeReason.PLAN_REQUIREMENTS_CHANGED,
                parent.executionPrincipal(),
                parent.policyPack(),
                parent.agentProfileId(),
                parent.agentProfileVersion(),
                Set.of(
                        ExecutionCapability.PLAN,
                        ExecutionCapability.STRUCTURED_OUTPUT,
                        ExecutionCapability.SANDBOX),
                Set.of("repository.read", "validation.run", "workspace.write"),
                parent.providerBindingIds(),
                parent.budget(),
                fixture.base.owner,
                TaskPlanningFixture.LATER);

        assertEquals(2, expanded.revision());
        assertEquals(parent.id(), expanded.parentSnapshotId().orElseThrow());
        assertTrue(expanded.expands(parent));
        assertNotEquals(parent.snapshotHash(), expanded.snapshotHash());
        assertFalse(parent.capabilities().contains(ExecutionCapability.SANDBOX));
    }

    @Test
    void rejectsNoopSupersedingRevisionAndTamperedHash() {
        TaskPlanningFixture fixture = new TaskPlanningFixture();
        PolicySnapshot parent = fixture.policy();

        assertThrows(
                DomainValidationException.class,
                () -> PolicySnapshot.supersede(
                        PolicySnapshotId.generate(), parent,
                        PolicySnapshotChangeReason.POLICY_PACK_CHANGED,
                        parent.executionPrincipal(), parent.policyPack(), parent.agentProfileId(),
                        parent.agentProfileVersion(), parent.capabilities(), parent.allowedTools(),
                        parent.providerBindingIds(), parent.budget(), fixture.base.owner,
                        TaskPlanningFixture.LATER));
        assertThrows(
                DomainValidationException.class,
                () -> PolicySnapshot.reconstitute(
                        parent.id(), parent.scope(), parent.taskId(), parent.executionId(),
                        parent.revision(), parent.parentSnapshotId(), parent.changeReason(),
                        parent.executionPrincipal(), parent.policyPack(), parent.agentProfileId(),
                        parent.agentProfileVersion(), parent.capabilities(), parent.allowedTools(),
                        parent.providerBindingIds(), parent.budget(),
                        TaskFactHash.sha256("tampered"), parent.createdByPrincipalId(),
                        parent.createdAt()));
    }

    @Test
    void validatesPolicyBudgetAndToolVocabulary() {
        assertThrows(DomainValidationException.class, () -> new PolicyBudget(0, 1, 1, 1));

        TaskPlanningFixture fixture = new TaskPlanningFixture();
        assertThrows(
                DomainValidationException.class,
                () -> PolicySnapshot.initial(
                        PolicySnapshotId.generate(), fixture.task, fixture.execution,
                        fixture.base.executor, fixture.policyPack, fixture.agentProfileId, 1,
                        Set.of(ExecutionCapability.PLAN), Set.of("INVALID TOOL"), Set.of(),
                        new PolicyBudget(1, 1, 1, 1), fixture.base.owner,
                        TaskPlanningFixture.POLICY_AT));
    }
}

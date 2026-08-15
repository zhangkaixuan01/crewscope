package io.crewscope.application.task;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.provider.ProviderCapabilities;
import io.crewscope.domain.provider.ProviderResourceScope;
import io.crewscope.domain.responsibility.ResponsibilityAssignmentId;
import io.crewscope.domain.runtime.ExecutionRuntimeId;
import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.runtime.RuntimeWorkerId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.task.ClaimTokenHash;
import io.crewscope.domain.task.ExecutionLeaseId;
import io.crewscope.domain.task.ExecutionPrincipalSnapshot;
import io.crewscope.domain.task.FencingToken;
import io.crewscope.domain.task.PolicySnapshotId;
import io.crewscope.domain.task.SafetyEnforcementOverlayId;
import io.crewscope.domain.task.SafetyEnforcementOverlayReference;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.task.TaskProviderAuthorization;
import io.crewscope.domain.task.TaskTokenGrantScope;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Canonical Task Token scope commitment tests. */
class TaskTokenScopeFingerprintTest {

    @Test
    void distinguishesCapabilityAndResourceCollectionBoundaries() {
        ProviderBindingId bindingId = ProviderBindingId.generate();
        TaskProviderAuthorization first = new TaskProviderAuthorization(
                bindingId,
                1,
                Optional.empty(),
                Optional.empty(),
                ProviderCapabilities.of("a", "b"),
                ProviderResourceScope.of("c"));
        TaskProviderAuthorization second = new TaskProviderAuthorization(
                bindingId,
                1,
                Optional.empty(),
                Optional.empty(),
                ProviderCapabilities.of("a"),
                ProviderResourceScope.of("b", "c"));
        TaskTokenGrantScope base = scope(first);
        TaskTokenGrantScope regrouped = new TaskTokenGrantScope(
                base.workItemScope(), base.taskId(), base.taskExecutionId(), base.attempt(),
                base.executionLeaseId(), base.environment(), base.runtimeId(), base.workerId(),
                base.claimTokenHash(), base.fencingToken(), base.executionPrincipal(),
                base.policySnapshotId(), base.policySnapshotHash(), base.safetyOverlay(),
                base.allowedTools(), Set.of(second));

        assertNotEquals(
                TaskTokenScopeFingerprint.compute(base),
                TaskTokenScopeFingerprint.compute(regrouped));
    }

    private static TaskTokenGrantScope scope(TaskProviderAuthorization authorization) {
        WorkItemScope workScope = new WorkItemScope(
                OrganizationId.generate(), TeamId.generate(), WorkspaceId.generate(),
                WorkProjectId.generate());
        return new TaskTokenGrantScope(
                workScope,
                TaskId.generate(),
                TaskExecutionId.generate(),
                1,
                ExecutionLeaseId.generate(),
                new RuntimeEnvironment("test"),
                ExecutionRuntimeId.generate(),
                RuntimeWorkerId.generate(),
                new ClaimTokenHash("a".repeat(64)),
                FencingToken.initial(),
                new ExecutionPrincipalSnapshot(
                        PrincipalId.generate(), ResponsibilityAssignmentId.generate(), 1,
                        TaskFactHash.sha256("responsibility")),
                PolicySnapshotId.generate(),
                TaskFactHash.sha256("policy"),
                new SafetyEnforcementOverlayReference(
                        SafetyEnforcementOverlayId.generate(), 1, TaskFactHash.sha256("overlay")),
                Set.of("repository.read"),
                Set.of(authorization));
    }
}

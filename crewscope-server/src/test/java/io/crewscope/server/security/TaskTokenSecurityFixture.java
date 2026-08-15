package io.crewscope.server.security;

import io.crewscope.application.task.TaskTokenExecutionContext;
import io.crewscope.domain.responsibility.ResponsibilityAssignmentId;
import io.crewscope.domain.runtime.ExecutionRuntimeId;
import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.runtime.RuntimeWorkerId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.ClaimTokenHash;
import io.crewscope.domain.task.ExecutionLeaseId;
import io.crewscope.domain.task.ExecutionPrincipalSnapshot;
import io.crewscope.domain.task.FencingToken;
import io.crewscope.domain.task.PolicySnapshotId;
import io.crewscope.domain.task.SafetyEnforcementOverlayId;
import io.crewscope.domain.task.SafetyEnforcementOverlayReference;
import io.crewscope.domain.task.TaskCredentialGrantId;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.task.TaskTokenClaims;
import io.crewscope.domain.task.TaskTokenGrantScope;
import io.crewscope.domain.task.TaskTokenJti;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.Set;

/** Compact real-value fixture shared by JWT and WebFilter security tests. */
final class TaskTokenSecurityFixture {

    final OrganizationId organizationId = OrganizationId.generate();
    final PrincipalId principalId = PrincipalId.generate();
    final RuntimeEnvironment environment = new RuntimeEnvironment("test");
    final UtcTimestamp issuedAt = UtcTimestamp.parse("2026-08-15T02:00:00.123456Z");
    final UtcTimestamp expiresAt = UtcTimestamp.parse("2026-08-15T02:05:00.123456Z");
    final TaskCredentialGrantId grantId = TaskCredentialGrantId.generate();
    final TaskTokenJti jti = new TaskTokenJti(
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ");
    final TaskTokenGrantScope scope = scope();
    final TaskTokenClaims claims = new TaskTokenClaims(
            TaskTokenClaims.AUDIENCE, grantId, jti, scope, issuedAt, expiresAt);
    final TaskTokenExecutionContext context = new TaskTokenExecutionContext(
            grantId, 0, scope, expiresAt);

    private TaskTokenGrantScope scope() {
        WorkItemScope workScope = new WorkItemScope(
                organizationId, TeamId.generate(), WorkspaceId.generate(), WorkProjectId.generate());
        TaskFactHash responsibilityHash = TaskFactHash.sha256("responsibility");
        ExecutionPrincipalSnapshot principal = new ExecutionPrincipalSnapshot(
                principalId, ResponsibilityAssignmentId.generate(), 2, responsibilityHash);
        return new TaskTokenGrantScope(
                workScope,
                TaskId.generate(),
                TaskExecutionId.generate(),
                1,
                ExecutionLeaseId.generate(),
                environment,
                ExecutionRuntimeId.generate(),
                RuntimeWorkerId.generate(),
                new ClaimTokenHash("a".repeat(64)),
                FencingToken.initial(),
                principal,
                PolicySnapshotId.generate(),
                TaskFactHash.sha256("policy"),
                new SafetyEnforcementOverlayReference(
                        SafetyEnforcementOverlayId.generate(),
                        1,
                        TaskFactHash.sha256("overlay")),
                Set.of("repository.read"),
                Set.of());
    }
}

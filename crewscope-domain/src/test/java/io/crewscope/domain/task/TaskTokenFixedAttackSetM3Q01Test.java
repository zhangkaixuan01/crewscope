package io.crewscope.domain.task;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.provider.ProviderCapability;
import io.crewscope.domain.runtime.ExecutionRuntimeId;
import io.crewscope.domain.runtime.RuntimeWorkerId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/** Stable M3-Q01 tenant, ownership, Tool and Provider scope attack vectors. */
class TaskTokenFixedAttackSetM3Q01Test {

    @TestFactory
    Stream<DynamicTest> blocksEverySignedScopeCoordinateSubstitution() {
        TaskCredentialGrantDomainFixture fixture = new TaskCredentialGrantDomainFixture();
        TaskCredentialIssuance issuance = fixture.issue();
        return Stream.of(Coordinate.values()).map(coordinate -> dynamicTest(
                "TK-" + coordinate.name(),
                () -> {
                    TaskTokenClaims claims = new TaskTokenClaims(
                            issuance.claims().audience(),
                            issuance.claims().grantId(),
                            issuance.claims().jti(),
                            mutate(issuance.claims().scope(), coordinate),
                            issuance.claims().issuedAt(),
                            issuance.claims().expiresAt());
                    assertThrows(RuntimeException.class, () -> issuance.grant().authenticate(
                            claims, fixture.lease, TaskCredentialGrantDomainFixture.USED_AT));
                }));
    }

    @TestFactory
    Stream<DynamicTest> blocksToolBindingCapabilityAndResourceEscapes() {
        TaskCredentialGrantDomainFixture fixture = new TaskCredentialGrantDomainFixture();
        TaskCredentialIssuance issuance = fixture.issue();
        List<NamedAccess> attacks = List.of(
                new NamedAccess("TK-TOOL", TaskTokenAccessRequest.tool("validation.run")),
                new NamedAccess(
                        "TK-BINDING",
                        TaskTokenAccessRequest.provider(
                                "repository.read",
                                new TaskProviderAccessRequest(
                                        ProviderBindingId.generate(),
                                        new ProviderCapability("repository.read"),
                                        TaskCredentialGrantDomainFixture.RESOURCE))),
                new NamedAccess(
                        "TK-CAPABILITY",
                        TaskTokenAccessRequest.provider(
                                "repository.read",
                                new TaskProviderAccessRequest(
                                        fixture.binding.id(),
                                        new ProviderCapability("repository.write"),
                                        TaskCredentialGrantDomainFixture.RESOURCE))),
                new NamedAccess(
                        "TK-RESOURCE",
                        TaskTokenAccessRequest.provider(
                                "repository.read",
                                new TaskProviderAccessRequest(
                                        fixture.binding.id(),
                                        new ProviderCapability("repository.read"),
                                        "repository:other"))));
        return attacks.stream().map(attack -> dynamicTest(
                attack.name(),
                () -> assertThrows(RuntimeException.class, () -> issuance.grant().use(
                        issuance.claims(), fixture.lease, attack.request(), 0,
                        TaskCredentialGrantDomainFixture.USED_AT))));
    }

    private static TaskTokenGrantScope mutate(
            TaskTokenGrantScope source, Coordinate coordinate) {
        WorkItemScope workScope = source.workItemScope();
        TaskId taskId = source.taskId();
        TaskExecutionId executionId = source.taskExecutionId();
        int attempt = source.attempt();
        ExecutionLeaseId leaseId = source.executionLeaseId();
        ExecutionRuntimeId runtimeId = source.runtimeId();
        RuntimeWorkerId workerId = source.workerId();
        ClaimTokenHash claimHash = source.claimTokenHash();
        FencingToken fencing = source.fencingToken();
        ExecutionPrincipalSnapshot principal = source.executionPrincipal();
        switch (coordinate) {
            case ORGANIZATION -> workScope = new WorkItemScope(
                    OrganizationId.generate(), workScope.teamId(), workScope.workspaceId(),
                    workScope.projectId());
            case TEAM -> workScope = new WorkItemScope(
                    workScope.organizationId(), TeamId.generate(), workScope.workspaceId(),
                    workScope.projectId());
            case WORKSPACE -> workScope = new WorkItemScope(
                    workScope.organizationId(), workScope.teamId(), WorkspaceId.generate(),
                    workScope.projectId());
            case WORK_PROJECT -> workScope = new WorkItemScope(
                    workScope.organizationId(), workScope.teamId(), workScope.workspaceId(),
                    WorkProjectId.generate());
            case TASK -> taskId = TaskId.generate();
            case TASK_EXECUTION -> executionId = TaskExecutionId.generate();
            case ATTEMPT -> attempt++;
            case LEASE -> leaseId = ExecutionLeaseId.generate();
            case RUNTIME -> runtimeId = ExecutionRuntimeId.generate();
            case WORKER -> workerId = RuntimeWorkerId.generate();
            case CLAIM -> claimHash = new ClaimTokenHash("0".repeat(64));
            case FENCING -> fencing = fencing.next();
            case PRINCIPAL -> principal = new ExecutionPrincipalSnapshot(
                    PrincipalId.generate(), principal.assignmentId(), principal.assignmentVersion(),
                    principal.responsibilitySnapshotHash());
        }
        return new TaskTokenGrantScope(
                workScope, taskId, executionId, attempt, leaseId, source.environment(),
                runtimeId, workerId, claimHash, fencing, principal, source.policySnapshotId(),
                source.policySnapshotHash(), source.safetyOverlay(), source.allowedTools(),
                source.providerAuthorizations());
    }

    private enum Coordinate {
        ORGANIZATION,
        TEAM,
        WORKSPACE,
        WORK_PROJECT,
        TASK,
        TASK_EXECUTION,
        ATTEMPT,
        LEASE,
        RUNTIME,
        WORKER,
        CLAIM,
        FENCING,
        PRINCIPAL
    }

    private record NamedAccess(String name, TaskTokenAccessRequest request) {}
}

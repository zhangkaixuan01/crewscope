package io.crewscope.domain.runtime;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Optional;
import java.util.Set;

final class RuntimeDomainFixture {

    static final UtcTimestamp CREATED_AT = UtcTimestamp.parse("2026-08-13T08:00:00Z");
    static final UtcTimestamp HEARTBEAT_AT = UtcTimestamp.parse("2026-08-13T08:01:00Z");
    static final UtcTimestamp LATER = UtcTimestamp.parse("2026-08-13T08:02:00Z");

    final OrganizationId organizationId = OrganizationId.generate();
    final RuntimeEnvironment environment = new RuntimeEnvironment("production");
    final Principal operator = operator(organizationId, "Runtime operator");
    final RuntimeCapabilities runtimeCapabilities = RuntimeCapabilities.of(
            Set.of(
                    RuntimeCapability.CONVERSATION,
                    RuntimeCapability.STREAMING,
                    RuntimeCapability.PLAN,
                    RuntimeCapability.SANDBOX),
            Set.of("java", "typescript"),
            Set.of("maven", "pnpm"));
    final RuntimeCapabilities workerCapabilities = RuntimeCapabilities.of(
            Set.of(RuntimeCapability.CONVERSATION, RuntimeCapability.PLAN),
            Set.of("java"),
            Set.of("maven"));

    ExecutionRuntime runtime() {
        return runtime(organizationId, environment);
    }

    ExecutionRuntime runtime(
            OrganizationId targetOrganizationId, RuntimeEnvironment targetEnvironment) {
        Principal targetOperator = targetOrganizationId.equals(organizationId)
                ? operator
                : operator(targetOrganizationId, "Other operator");
        return ExecutionRuntime.register(
                ExecutionRuntimeId.generate(),
                targetOrganizationId,
                targetEnvironment,
                "agentscope-java",
                "AgentScope Java",
                "2.0.0",
                runtimeCapabilities,
                targetOperator,
                CREATED_AT);
    }

    RuntimeWorker worker(ExecutionRuntime runtime) {
        return RuntimeWorker.register(
                RuntimeWorkerId.generate(),
                runtime,
                "crewscope-worker-01",
                RuntimeProfile.WORKER,
                workerCapabilities,
                new RuntimeWorkerCapacity(4, 0),
                operator,
                CREATED_AT);
    }

    Principal operator(OrganizationId targetOrganizationId, String displayName) {
        return Principal.create(
                PrincipalId.generate(),
                PrincipalScope.organization(targetOrganizationId),
                PrincipalType.USER,
                Optional.empty(),
                displayName,
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                CREATED_AT);
    }
}

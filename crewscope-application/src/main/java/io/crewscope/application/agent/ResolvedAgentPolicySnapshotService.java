package io.crewscope.application.agent;

import io.crewscope.application.task.PolicySnapshotRepository;
import io.crewscope.domain.agent.ResolvedAgentExecutionConfiguration;
import io.crewscope.domain.task.PolicySnapshot;
import java.util.Objects;

/** Resolves model facts first, then persists the exact closed graph in PolicySnapshot Schema v2. */
public final class ResolvedAgentPolicySnapshotService {

    private final AgentExecutionConfigurationService configurations;
    private final PolicySnapshotRepository snapshots;

    public ResolvedAgentPolicySnapshotService(
            AgentExecutionConfigurationService configurations,
            PolicySnapshotRepository snapshots) {
        this.configurations = Objects.requireNonNull(configurations, "configurations");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
    }

    public PolicySnapshot createInitial(CreateResolvedPolicySnapshotRequest request) {
        CreateResolvedPolicySnapshotRequest required = Objects.requireNonNull(request, "request");
        ResolvedAgentExecutionConfiguration resolved = configurations.resolve(
                required.resolutionRequest());
        return createInitial(required, resolved);
    }

    /** Persists a configuration already preflighted by the Task selection boundary. */
    public PolicySnapshot createInitial(
            CreateResolvedPolicySnapshotRequest request,
            ResolvedAgentExecutionConfiguration resolvedConfiguration) {
        CreateResolvedPolicySnapshotRequest required = Objects.requireNonNull(request, "request");
        ResolvedAgentExecutionConfiguration resolved = Objects.requireNonNull(
                resolvedConfiguration, "resolvedConfiguration");
        if (!required.resolutionRequest().agentProfileId().equals(resolved.agentProfileId())
                || !required.executor().id().equals(resolved.agentPrincipalId())) {
            throw new IllegalArgumentException(
                    "PolicySnapshot request and resolved configuration must share an Agent");
        }
        PolicySnapshot snapshot = PolicySnapshot.initialV2(
                required.snapshotId(),
                required.task(),
                required.execution(),
                required.executor(),
                resolved,
                required.capabilities(),
                required.allowedTools(),
                required.providerBindingIds(),
                required.budget(),
                required.actor(),
                required.createdAt());
        return snapshots.create(snapshot);
    }
}

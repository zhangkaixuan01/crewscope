package io.crewscope.application.github;

import io.crewscope.domain.provider.ProviderExecutionIdentity;
import io.crewscope.domain.provider.ProviderRegistrationStatus;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import java.util.List;
import java.util.Objects;

/** Safe GitHub ProviderBinding projection with exact pinned revisions and resource scope. */
public record GitHubProviderBindingView(
        String bindingId,
        TeamId teamId,
        WorkspaceId workspaceId,
        String connectionId,
        long connectionVersion,
        ProviderExecutionIdentity executionIdentity,
        List<String> repositoryAllowlist,
        ProviderRegistrationStatus status,
        boolean defaultUsage,
        long version) {

    public GitHubProviderBindingView {
        bindingId = Objects.requireNonNull(bindingId, "bindingId");
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(workspaceId, "workspaceId");
        connectionId = Objects.requireNonNull(connectionId, "connectionId");
        if (connectionVersion < 0 || version < 0) {
            throw new IllegalArgumentException("binding versions must not be negative");
        }
        Objects.requireNonNull(executionIdentity, "executionIdentity");
        repositoryAllowlist = List.copyOf(
                Objects.requireNonNull(repositoryAllowlist, "repositoryAllowlist"));
        Objects.requireNonNull(status, "status");
    }
}

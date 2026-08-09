package io.crewscope.application.provider;

import io.crewscope.domain.provider.ProviderExecutionIdentity;
import io.crewscope.domain.provider.ProviderOwner;
import io.crewscope.domain.provider.ProviderType;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.Objects;
import java.util.Optional;

/** Exact trusted facts used to fetch candidates for the later BindingResolver. */
public record ProviderBindingQuery(
        OrganizationId organizationId,
        TeamId teamId,
        WorkspaceId workspaceId,
        Optional<WorkProjectId> workProjectId,
        ProviderOwner owner,
        ProviderType providerType,
        Optional<ProviderExecutionIdentity> executionIdentity) {
    public ProviderBindingQuery {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        teamId = Objects.requireNonNull(teamId, "teamId");
        workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
        workProjectId = Objects.requireNonNull(workProjectId, "workProjectId");
        owner = Objects.requireNonNull(owner, "owner");
        providerType = Objects.requireNonNull(providerType, "providerType");
        executionIdentity = Objects.requireNonNull(executionIdentity, "executionIdentity");
        if (!organizationId.equals(owner.organizationId())) {
            throw new IllegalArgumentException("binding owner must belong to the query Organization");
        }
    }
}

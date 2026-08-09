package io.crewscope.application.provider;

import io.crewscope.domain.provider.ProviderAccessScope;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.provider.ProviderExecutionIdentity;
import io.crewscope.domain.provider.ProviderOwner;
import io.crewscope.domain.provider.ProviderOwnerType;
import io.crewscope.domain.provider.ProviderType;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.Objects;
import java.util.Optional;

/** Server-derived Scope, identity and access facts for one read-only Binding resolution. */
public record ProviderBindingResolutionRequest(
        OrganizationId organizationId,
        TeamId teamId,
        WorkspaceId workspaceId,
        Optional<WorkProjectId> workProjectId,
        ProviderOwner bindingOwner,
        ProviderType providerType,
        Optional<ProviderExecutionIdentity> executionIdentity,
        ProviderAccessScope requestedAccess,
        Optional<ProviderBindingId> actionBindingId,
        Optional<ProviderBindingId> taskBindingId) {

    public ProviderBindingResolutionRequest {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        teamId = Objects.requireNonNull(teamId, "teamId");
        workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
        workProjectId = Objects.requireNonNull(workProjectId, "workProjectId");
        bindingOwner = Objects.requireNonNull(bindingOwner, "bindingOwner");
        providerType = Objects.requireNonNull(providerType, "providerType");
        executionIdentity = Objects.requireNonNull(executionIdentity, "executionIdentity");
        requestedAccess = Objects.requireNonNull(requestedAccess, "requestedAccess");
        actionBindingId = Objects.requireNonNull(actionBindingId, "actionBindingId");
        taskBindingId = Objects.requireNonNull(taskBindingId, "taskBindingId");
        if (!organizationId.equals(bindingOwner.organizationId())) {
            throw new IllegalArgumentException(
                    "binding owner must belong to the request Organization");
        }
        if (bindingOwner.type() == ProviderOwnerType.TEAM
                && bindingOwner.teamId().filter(teamId::equals).isEmpty()) {
            throw new IllegalArgumentException(
                    "TEAM binding owner must match the request Team");
        }
    }
}

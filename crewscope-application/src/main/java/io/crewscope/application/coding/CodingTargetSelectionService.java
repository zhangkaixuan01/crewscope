package io.crewscope.application.coding;

import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.domain.coding.BuildProfile;
import io.crewscope.domain.coding.RepositoryBinding;
import io.crewscope.domain.coding.RepositoryBindingId;
import io.crewscope.domain.coding.RepositoryBranchName;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.List;
import java.util.Objects;

/** Member-authorized BuildProfile discovery and Ref Preflight for CodingTarget forms. */
public final class CodingTargetSelectionService {

    private final WorkItemAccessPolicy accessPolicy;
    private final RepositoryBindingRepository bindingRepository;
    private final RepositoryBindingPreflightPort preflightPort;
    private final BuildProfileCatalog buildProfileCatalog;
    private final TransactionExecutor transactionExecutor;

    public CodingTargetSelectionService(
            WorkItemAccessPolicy accessPolicy,
            RepositoryBindingRepository bindingRepository,
            RepositoryBindingPreflightPort preflightPort,
            BuildProfileCatalog buildProfileCatalog,
            TransactionExecutor transactionExecutor) {
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
        this.bindingRepository = Objects.requireNonNull(bindingRepository, "bindingRepository");
        this.preflightPort = Objects.requireNonNull(preflightPort, "preflightPort");
        this.buildProfileCatalog = Objects.requireNonNull(
                buildProfileCatalog, "buildProfileCatalog");
        this.transactionExecutor = Objects.requireNonNull(transactionExecutor, "transactionExecutor");
    }

    public List<BuildProfile> listBuildProfiles(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId projectId,
            WorkItemId workItemId) {
        return transactionExecutor.required(() -> {
            accessPolicy.requireVisibleWorkItem(
                    context, organizationId, teamId, projectId, workItemId);
            return List.copyOf(buildProfileCatalog.findAll());
        });
    }

    public RepositoryBindingPreflightResult preflight(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId projectId,
            WorkItemId workItemId,
            RepositoryBindingId bindingId,
            RepositoryBranchName baselineRef) {
        return transactionExecutor.required(() -> {
            WorkItem workItem = accessPolicy.requireVisibleWorkItem(
                    context, organizationId, teamId, projectId, workItemId);
            RepositoryBinding binding = bindingRepository
                    .findById(organizationId, teamId, projectId, bindingId)
                    .filter(candidate -> candidate.scope().workspaceId()
                            .equals(workItem.scope().workspaceId()))
                    .filter(RepositoryBinding::acceptsNewTargets)
                    .orElseThrow(() -> new DomainValidationException(
                            "codingTarget.repositoryBindingId",
                            "must reference an active RepositoryBinding in the complete WorkItem scope"));
            RepositoryBindingPreflightResult result = preflightPort.preflight(binding, baselineRef);
            if (!result.repositoryKey().equals(binding.repositoryKey())
                    || !result.baselineRef().equals(baselineRef)) {
                throw new DomainValidationException(
                        "codingTarget.baselineRef",
                        "Repository Preflight facts must match the selected Binding and Ref");
            }
            return result;
        });
    }
}

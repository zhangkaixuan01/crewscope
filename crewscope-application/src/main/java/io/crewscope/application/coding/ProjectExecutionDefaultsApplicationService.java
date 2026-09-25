package io.crewscope.application.coding;

import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.coding.BuildProfileReference;
import io.crewscope.domain.coding.ProjectExecutionDefaults;
import io.crewscope.domain.coding.RepositoryBinding;
import io.crewscope.domain.coding.RepositoryBindingId;
import io.crewscope.domain.coding.RepositoryBranchName;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.workitem.WorkProject;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.domain.workspace.AgentProfileId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Resolves, validates and replaces project execution defaults without exposing host commands. */
public final class ProjectExecutionDefaultsApplicationService {
    private final RepositoryBindingAccessPolicy accessPolicy;
    private final ProjectExecutionDefaultsRepository defaultsRepository;
    private final RepositoryBindingRepository bindingRepository;
    private final BuildProfileCatalog buildProfileCatalog;
    private final TimeProvider timeProvider;

    public ProjectExecutionDefaultsApplicationService(
            RepositoryBindingAccessPolicy accessPolicy,
            ProjectExecutionDefaultsRepository defaultsRepository,
            RepositoryBindingRepository bindingRepository,
            BuildProfileCatalog buildProfileCatalog,
            TimeProvider timeProvider) {
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
        this.defaultsRepository = Objects.requireNonNull(defaultsRepository, "defaultsRepository");
        this.bindingRepository = Objects.requireNonNull(bindingRepository, "bindingRepository");
        this.buildProfileCatalog = Objects.requireNonNull(buildProfileCatalog, "buildProfileCatalog");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    public ProjectExecutionDefaults get(TeamAccessContext context, OrganizationId organizationId,
            TeamId teamId, WorkProjectId projectId) {
        WorkProject project = accessPolicy.requireVisibleProject(context, organizationId, teamId, projectId);
        return defaultsRepository.find(organizationId, teamId, projectId).orElseGet(() ->
                ProjectExecutionDefaults.empty(organizationId, teamId, project.scope().workspaceId(), projectId));
    }

    public List<io.crewscope.domain.coding.BuildProfile> listBuildProfiles(TeamAccessContext context,
            OrganizationId organizationId, TeamId teamId, WorkProjectId projectId) {
        accessPolicy.requireVisibleProject(context, organizationId, teamId, projectId);
        return buildProfileCatalog.findAll();
    }

    public ProjectExecutionDefaults replace(TeamAccessContext context, OrganizationId organizationId,
            TeamId teamId, WorkProjectId projectId, long expectedVersion,
            Optional<RepositoryBindingId> repositoryBindingId, Optional<Long> repositoryBindingVersion,
            Optional<RepositoryBranchName> branch, Optional<BuildProfileReference> buildProfile,
            Optional<AgentProfileId> agentProfileId, Optional<Long> agentProfileRevision) {
        if (expectedVersion < 0) throw new DomainValidationException("executionDefaults.version", "must not be negative");
        WorkProject project = accessPolicy.requireAdministrator(context, organizationId, teamId, projectId, timeProvider.now());
        Optional<ProjectExecutionDefaults> current = defaultsRepository.find(organizationId, teamId, projectId);
        long actual = current.map(ProjectExecutionDefaults::version).orElse(0L);
        if (actual != expectedVersion) throw new io.crewscope.domain.shared.error.OptimisticLockConflictException(
                "ProjectExecutionDefaults", projectId, expectedVersion, actual);
        if (repositoryBindingId.isPresent()) {
            RepositoryBinding binding = bindingRepository.findById(organizationId, teamId, projectId, repositoryBindingId.orElseThrow())
                    .filter(value -> value.scope().workspaceId().equals(project.scope().workspaceId()))
                    .orElseThrow(() -> new DomainValidationException("executionDefaults.repositoryBindingId", "must reference a repository in this project"));
            if (!binding.acceptsNewTargets()) throw new DomainValidationException(
                    "executionDefaults.repositoryBindingId", "must reference an active repository binding");
            if (repositoryBindingVersion.orElseThrow() != binding.version()) throw new DomainValidationException(
                    "executionDefaults.repositoryBindingVersion", "must match the current repository binding version");
        } else if (branch.isPresent()) {
            throw new DomainValidationException("executionDefaults.branch", "cannot be set without a repository binding");
        }
        buildProfile.ifPresent(reference -> buildProfileCatalog.findExact(reference).orElseThrow(() ->
                new DomainValidationException("executionDefaults.buildProfile", "must reference an available immutable BuildProfile")));
        ProjectExecutionDefaults next = new ProjectExecutionDefaults(organizationId, teamId,
                project.scope().workspaceId(), projectId, actual + 1, repositoryBindingId,
                repositoryBindingVersion, branch, buildProfile, agentProfileId, agentProfileRevision);
        return defaultsRepository.replace(next, expectedVersion);
    }
}

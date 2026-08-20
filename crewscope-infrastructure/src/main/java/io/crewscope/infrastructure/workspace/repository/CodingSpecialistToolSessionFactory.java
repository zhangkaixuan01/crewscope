package io.crewscope.infrastructure.workspace.repository;

import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.crewscope.application.coding.CommandEvidenceRepository;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.ExecutionLease;
import io.crewscope.infrastructure.workspace.git.GitCommandExecutor;
import java.util.List;
import java.util.Objects;

/** Builds repository, filesystem and command facades over one non-overlapping Sandbox call. */
public final class CodingSpecialistToolSessionFactory {

    private final RepositoryInspectionProperties inspectionProperties;
    private final CodingFilesystemProperties filesystemProperties;
    private final SandboxCommandProperties commandProperties;
    private final GitCommandExecutor git;
    private final CodingFilesystemUsageRegistry filesystemUsages;
    private final CommandEvidenceRepository commandEvidence;
    private final SandboxCommandUsageRegistry commandUsages;
    private final BuildProfileCommandRunner commandRunner;
    private final CommandEvidenceWriter commandWriter;
    private final TestEvidencePublisher testEvidencePublisher;

    CodingSpecialistToolSessionFactory(
            RepositoryInspectionProperties inspectionProperties,
            CodingFilesystemProperties filesystemProperties,
            SandboxCommandProperties commandProperties,
            GitCommandExecutor git,
            CodingFilesystemUsageRegistry filesystemUsages,
            CommandEvidenceRepository commandEvidence,
            SandboxCommandUsageRegistry commandUsages,
            BuildProfileCommandRunner commandRunner,
            CommandEvidenceWriter commandWriter,
            TestEvidencePublisher testEvidencePublisher) {
        this.inspectionProperties = Objects.requireNonNull(
                inspectionProperties, "inspectionProperties");
        this.filesystemProperties = Objects.requireNonNull(
                filesystemProperties, "filesystemProperties");
        this.commandProperties = Objects.requireNonNull(commandProperties, "commandProperties");
        this.git = Objects.requireNonNull(git, "git");
        this.filesystemUsages = Objects.requireNonNull(filesystemUsages, "filesystemUsages");
        this.commandEvidence = Objects.requireNonNull(commandEvidence, "commandEvidence");
        this.commandUsages = Objects.requireNonNull(commandUsages, "commandUsages");
        this.commandRunner = Objects.requireNonNull(commandRunner, "commandRunner");
        this.commandWriter = Objects.requireNonNull(commandWriter, "commandWriter");
        this.testEvidencePublisher = Objects.requireNonNull(
                testEvidencePublisher, "testEvidencePublisher");
    }

    public CodingSpecialistToolSession open(
            CodingWorkspaceExecution execution,
            ExecutionLease lease,
            Principal actor,
            UtcTimestamp authoritativeNow) {
        CodingWorkspaceExecution current = Objects.requireNonNull(execution, "execution");
        var workspace = current.workspace();
        var policy = current.policy();
        TaskExecutionSandboxCall call = current.sandbox().openCall(
                workspace,
                Objects.requireNonNull(lease, "lease"),
                Objects.requireNonNull(authoritativeNow, "authoritativeNow"));
        SandboxBackedFilesystem filesystem = new SandboxBackedFilesystem();
        try {
            call.requireCurrent();
            filesystem.setSandbox(call.sandboxContext().getExternalSandbox());
            RepositoryInspectionPathGuard inspectionGuard = new RepositoryInspectionPathGuard(
                    current.worktree().canonicalPath(),
                    current.sandbox().descriptor().repositoryContainerPath(),
                    policy.allowedPaths(),
                    inspectionProperties.requiredMaxPatternLength());
            RepositoryInspectionTool repositoryTool = new RepositoryInspectionTool(
                    call,
                    filesystem,
                    git,
                    current.worktree(),
                    current.repository(),
                    inspectionGuard,
                    policy.allowedPaths(),
                    inspectionProperties.requiredMaxPageSize(),
                    inspectionProperties.requiredMaxReadLines(),
                    inspectionProperties.requiredMaxTreeDepth(),
                    inspectionProperties.requiredMaxBackendOperations(),
                    (int) Math.min(
                            inspectionProperties.requiredMaxResultBytes(),
                            Math.min(policy.sandboxBudget().maxCommandOutputBytes(), Integer.MAX_VALUE)));
            CodingFilesystemToolFactory.InitialUsage initial =
                    CodingFilesystemToolFactory.initialUsage(
                            git, current.worktree(), policy, inspectionGuard);
            CodingFilesystemUsage filesystemUsage = filesystemUsages.acquire(
                    workspace, policy, initial.paths(), initial.bytes());
            CodingFilesystemTool filesystemTool = new CodingFilesystemTool(
                    call,
                    filesystem,
                    new CodingFilesystemPathPolicy(
                            current.worktree().canonicalPath(),
                            current.sandbox().descriptor().repositoryContainerPath(),
                            policy.allowedPaths()),
                    filesystemUsage,
                    policy.operationBudget(),
                    filesystemProperties.requiredMaxToolContentBytes(),
                    filesystemProperties.requiredMaxPatchHunks(),
                    CodingFilesystemMutationHook.NONE);
            List<io.crewscope.domain.coding.CommandEvidence> existing =
                    commandEvidence.findByWorkspace(
                            workspace.scope().organizationId(),
                            workspace.scope().teamId(),
                            workspace.scope().projectId(),
                            workspace.id());
            SandboxCommandUsage commandUsage = commandUsages.acquire(
                    workspace, policy, existing);
            SandboxCommandTool commandTool = new SandboxCommandTool(
                    call,
                    current.sandbox().descriptor().repositoryContainerPath(),
                    workspace,
                    policy,
                    current.buildProfile(),
                    Objects.requireNonNull(actor, "actor"),
                    commandUsage,
                    commandRunner,
                    commandWriter,
                    testEvidencePublisher,
                    current,
                    commandProperties.requiredMaxToolResultBytes());
            Toolkit toolkit = new Toolkit();
            toolkit.registerTool(repositoryTool);
            toolkit.registerTool(filesystemTool);
            toolkit.registerTool(commandTool);
            return new CodingSpecialistToolSession(call, filesystem, toolkit);
        } catch (RuntimeException failure) {
            filesystem.setSandbox(null);
            call.close();
            throw failure;
        }
    }
}

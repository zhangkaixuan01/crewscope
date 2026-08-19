package io.crewscope.infrastructure.workspace.repository;

import io.crewscope.application.coding.CommandEvidenceRepository;
import io.crewscope.domain.coding.BuildProfile;
import io.crewscope.domain.coding.CommandEvidence;
import io.crewscope.domain.coding.ExecutionWorkspace;
import io.crewscope.domain.coding.ExecutionWorkspaceStatus;
import io.crewscope.domain.coding.WorkspacePolicy;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.ExecutionLease;
import java.util.List;
import java.util.Objects;

/** Opens policy-bound structured command sessions over M4-I04 guarded Sandboxes. */
public final class SandboxCommandToolFactory {

    private final CommandEvidenceRepository evidenceRepository;
    private final SandboxCommandUsageRegistry usages;
    private final BuildProfileCommandRunner runner;
    private final CommandEvidenceWriter evidenceWriter;
    private final int maximumToolResultBytes;

    SandboxCommandToolFactory(
            SandboxCommandProperties properties,
            CommandEvidenceRepository evidenceRepository,
            SandboxCommandUsageRegistry usages,
            BuildProfileCommandRunner runner,
            CommandEvidenceWriter evidenceWriter) {
        this.evidenceRepository = Objects.requireNonNull(evidenceRepository, "evidenceRepository");
        this.usages = Objects.requireNonNull(usages, "usages");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.evidenceWriter = Objects.requireNonNull(evidenceWriter, "evidenceWriter");
        this.maximumToolResultBytes = Objects.requireNonNull(properties, "properties")
                .requiredMaxToolResultBytes();
    }

    /** Opens one exclusive execution window after matching Policy, Profile and Sandbox facts. */
    public SandboxCommandSession open(
            ManagedTaskExecutionSandbox sandbox,
            ExecutionWorkspace workspace,
            WorkspacePolicy policy,
            BuildProfile profile,
            ExecutionLease lease,
            Principal actor,
            UtcTimestamp authoritativeNow) {
        ManagedTaskExecutionSandbox managedSandbox = Objects.requireNonNull(sandbox, "sandbox");
        ExecutionWorkspace requiredWorkspace = Objects.requireNonNull(workspace, "workspace");
        WorkspacePolicy requiredPolicy = Objects.requireNonNull(policy, "policy");
        BuildProfile requiredProfile = Objects.requireNonNull(profile, "profile");
        Principal requiredActor = Objects.requireNonNull(actor, "actor");
        requireExactFacts(managedSandbox, requiredWorkspace, requiredPolicy, requiredProfile);
        requireActor(requiredWorkspace, requiredActor);

        TaskExecutionSandboxCall call = managedSandbox.openCall(
                requiredWorkspace,
                Objects.requireNonNull(lease, "lease"),
                Objects.requireNonNull(authoritativeNow, "authoritativeNow"));
        try {
            call.requireCurrent();
            List<CommandEvidence> existing = evidenceRepository.findByWorkspace(
                    requiredWorkspace.scope().organizationId(),
                    requiredWorkspace.scope().teamId(),
                    requiredWorkspace.scope().projectId(),
                    requiredWorkspace.id());
            SandboxCommandUsage usage = usages.acquire(
                    requiredWorkspace, requiredPolicy, existing);
            SandboxCommandTool tool = new SandboxCommandTool(
                    call,
                    managedSandbox.descriptor().repositoryContainerPath(),
                    requiredWorkspace,
                    requiredPolicy,
                    requiredProfile,
                    requiredActor,
                    usage,
                    runner,
                    evidenceWriter,
                    maximumToolResultBytes);
            return new SandboxCommandSession(call, tool);
        } catch (SandboxCommandException failure) {
            call.close();
            throw failure;
        } catch (RuntimeException failure) {
            call.close();
            throw new SandboxCommandException(
                    SandboxCommandError.INVALID_CONTEXT,
                    "Current command evidence state could not be verified");
        }
    }

    private static void requireActor(ExecutionWorkspace workspace, Principal actor) {
        boolean outsideTeam = actor.scope().teamId().isPresent()
                && actor.scope().teamId().filter(workspace.scope().teamId()::equals).isEmpty();
        if (workspace.status() != ExecutionWorkspaceStatus.ACTIVE
                || !actor.canAct()
                || !actor.scope().organizationId().equals(workspace.scope().organizationId())
                || outsideTeam) {
            throw new SandboxCommandException(
                    SandboxCommandError.INVALID_CONTEXT,
                    "Command actor does not match the active Workspace scope");
        }
    }

    private static void requireExactFacts(
            ManagedTaskExecutionSandbox sandbox,
            ExecutionWorkspace workspace,
            WorkspacePolicy policy,
            BuildProfile profile) {
        TaskExecutionSandboxDescriptor descriptor = sandbox.descriptor();
        if (!descriptor.workspace().id().equals(workspace.id())
                || !descriptor.workspace().fingerprint().equals(workspace.fingerprint())
                || !descriptor.policy().policyHash().equals(policy.policyHash())
                || !descriptor.buildProfile().profileHash().equals(profile.profileHash())
                || !policy.buildProfile().equals(profile.reference())) {
            throw new SandboxCommandException(
                    SandboxCommandError.INVALID_CONTEXT,
                    "Command facts do not match the current Sandbox epoch");
        }
    }
}

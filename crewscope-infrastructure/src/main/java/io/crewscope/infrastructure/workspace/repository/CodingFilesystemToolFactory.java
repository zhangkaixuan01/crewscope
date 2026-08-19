package io.crewscope.infrastructure.workspace.repository;

import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.crewscope.domain.coding.ExecutionWorkspace;
import io.crewscope.domain.coding.WorkspacePolicy;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.ExecutionLease;
import io.crewscope.infrastructure.workspace.git.GitCommandExecutor;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Opens policy-bound CodingFilesystemTool sessions over M4-I04 guarded Sandboxes. */
public final class CodingFilesystemToolFactory {

    private static final LinkOption[] NOFOLLOW = {LinkOption.NOFOLLOW_LINKS};

    private final GitCommandExecutor gitCommands;
    private final CodingFilesystemUsageRegistry usages;
    private final int maximumToolContentBytes;
    private final int maximumPatchHunks;
    private final CodingFilesystemMutationHook mutationHook;

    CodingFilesystemToolFactory(
            CodingFilesystemProperties properties,
            GitCommandExecutor gitCommands,
            CodingFilesystemUsageRegistry usages) {
        this(properties, gitCommands, usages, CodingFilesystemMutationHook.NONE);
    }

    CodingFilesystemToolFactory(
            CodingFilesystemProperties properties,
            GitCommandExecutor gitCommands,
            CodingFilesystemUsageRegistry usages,
            CodingFilesystemMutationHook mutationHook) {
        CodingFilesystemProperties configured = Objects.requireNonNull(properties, "properties");
        this.gitCommands = Objects.requireNonNull(gitCommands, "gitCommands");
        this.usages = Objects.requireNonNull(usages, "usages");
        this.maximumToolContentBytes = configured.requiredMaxToolContentBytes();
        this.maximumPatchHunks = configured.requiredMaxPatchHunks();
        this.mutationHook = Objects.requireNonNull(mutationHook, "mutationHook");
    }

    /** Opens one exclusive mutation window after matching durable and physical facts. */
    public CodingFilesystemSession open(
            ManagedTaskExecutionSandbox sandbox,
            ExecutionWorkspace workspace,
            ManagedWorktree worktree,
            WorkspacePolicy policy,
            ExecutionLease lease,
            UtcTimestamp authoritativeNow) {
        ManagedTaskExecutionSandbox managedSandbox = Objects.requireNonNull(sandbox, "sandbox");
        ExecutionWorkspace requiredWorkspace = Objects.requireNonNull(workspace, "workspace");
        ManagedWorktree managedWorktree = Objects.requireNonNull(worktree, "worktree");
        WorkspacePolicy effectivePolicy = Objects.requireNonNull(policy, "policy");
        requireExactFacts(managedSandbox, requiredWorkspace, managedWorktree, effectivePolicy);

        TaskExecutionSandboxCall call = managedSandbox.openCall(
                requiredWorkspace,
                Objects.requireNonNull(lease, "lease"),
                Objects.requireNonNull(authoritativeNow, "authoritativeNow"));
        try {
            call.requireCurrent();
            RepositoryInspectionPathGuard inspectionGuard = new RepositoryInspectionPathGuard(
                    managedWorktree.canonicalPath(),
                    managedSandbox.descriptor().repositoryContainerPath(),
                    effectivePolicy.allowedPaths(),
                    1);
            InitialUsage initial = initialUsage(managedWorktree, effectivePolicy, inspectionGuard);
            CodingFilesystemUsage usage = usages.acquire(
                    requiredWorkspace,
                    effectivePolicy,
                    initial.paths(),
                    initial.bytes());
            SandboxBackedFilesystem filesystem = new SandboxBackedFilesystem();
            filesystem.setSandbox(call.sandboxContext().getExternalSandbox());
            CodingFilesystemPathPolicy pathPolicy = new CodingFilesystemPathPolicy(
                    managedWorktree.canonicalPath(),
                    managedSandbox.descriptor().repositoryContainerPath(),
                    effectivePolicy.allowedPaths());
            CodingFilesystemTool tool = new CodingFilesystemTool(
                    call,
                    filesystem,
                    pathPolicy,
                    usage,
                    effectivePolicy.operationBudget(),
                    maximumToolContentBytes,
                    maximumPatchHunks,
                    mutationHook);
            return new CodingFilesystemSession(call, filesystem, tool);
        } catch (RuntimeException failure) {
            call.close();
            throw failure;
        }
    }

    private InitialUsage initialUsage(
            ManagedWorktree worktree,
            WorkspacePolicy policy,
            RepositoryInspectionPathGuard pathGuard) {
        try {
            Set<String> paths = statusPaths(gitCommands.inspectionStatus(
                    worktree.canonicalPath(), policy.allowedPaths()), pathGuard);
            long bytes = 0;
            for (String path : paths) {
                java.nio.file.Path host = pathGuard.toHostPath(path);
                if (Files.isRegularFile(host, NOFOLLOW)) {
                    bytes = Math.addExact(bytes, Files.size(host));
                }
            }
            return new InitialUsage(paths, bytes);
        } catch (CodingFilesystemException failure) {
            throw failure;
        } catch (RuntimeException | java.io.IOException failure) {
            throw new CodingFilesystemException(
                    CodingFilesystemError.FILESYSTEM_FAILED,
                    "Current repository mutation usage could not be verified",
                    failure);
        }
    }

    private static Set<String> statusPaths(
            String output, RepositoryInspectionPathGuard pathGuard) {
        List<String> tokens = splitNul(output);
        Set<String> paths = new LinkedHashSet<>();
        for (int index = 0; index < tokens.size(); index++) {
            String token = tokens.get(index);
            if (token.length() < 4 || token.charAt(2) != ' ') {
                throw new CodingFilesystemException(
                        CodingFilesystemError.FILESYSTEM_FAILED,
                        "Current Git status could not be verified");
            }
            String code = token.substring(0, 2);
            paths.add(pathGuard.requirePath(token.substring(3)));
            if (code.indexOf('R') >= 0 || code.indexOf('C') >= 0) {
                if (++index >= tokens.size()) {
                    throw new CodingFilesystemException(
                            CodingFilesystemError.FILESYSTEM_FAILED,
                            "Current Git status could not be verified");
                }
                paths.add(pathGuard.requirePath(tokens.get(index)));
            }
        }
        return Set.copyOf(paths);
    }

    private static List<String> splitNul(String value) {
        if (value == null || value.isEmpty()) {
            return List.of();
        }
        String[] fields = value.split("\\u0000", -1);
        int length = fields.length;
        while (length > 0 && fields[length - 1].isEmpty()) {
            length--;
        }
        return List.of(java.util.Arrays.copyOf(fields, length));
    }

    private static void requireExactFacts(
            ManagedTaskExecutionSandbox sandbox,
            ExecutionWorkspace workspace,
            ManagedWorktree worktree,
            WorkspacePolicy policy) {
        TaskExecutionSandboxDescriptor descriptor = sandbox.descriptor();
        if (!descriptor.workspace().id().equals(workspace.id())
                || !descriptor.worktree().workspaceId().equals(worktree.workspaceId())
                || !descriptor.worktree().physicalFingerprint().equals(worktree.physicalFingerprint())
                || !descriptor.policy().policyHash().equals(policy.policyHash())) {
            throw new CodingFilesystemException(
                    CodingFilesystemError.INVALID_CONTEXT,
                    "Coding filesystem facts do not match the current Sandbox epoch");
        }
    }

    private record InitialUsage(Set<String> paths, long bytes) {}
}

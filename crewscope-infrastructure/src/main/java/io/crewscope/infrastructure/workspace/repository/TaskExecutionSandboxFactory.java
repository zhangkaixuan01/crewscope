package io.crewscope.infrastructure.workspace.repository;

import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxClientOptions;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerFilesystemSpec;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxState;
import io.agentscope.harness.agent.sandbox.layout.BindMountEntry;
import io.crewscope.domain.coding.BuildProfile;
import io.crewscope.domain.coding.ExecutionWorkspace;
import io.crewscope.domain.coding.WorkspacePolicy;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.ExecutionLease;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Creates and owns TaskExecution-level Docker Sandboxes while reusing AgentScope's native Docker
 * filesystem implementation.
 *
 * <p>PostgreSQL Workspace and Lease facts remain authoritative. Docker labels are physical proof,
 * not a second registry; every reuse, pause and cleanup re-inspects the exact container.
 */
public final class TaskExecutionSandboxFactory {

    private static final LinkOption[] NO_FOLLOW_LINKS = {LinkOption.NOFOLLOW_LINKS};

    private final String workspaceRoot;
    private final String repositoryMount;
    private final Optional<Path> dependencyCacheRoot;
    private final String dependencyCacheMount;
    private final TaskExecutionSandboxPauseMode pauseMode;
    private final java.time.Duration pauseStopTimeout;
    private final DockerSandboxControl dockerControl;
    private final Clock clock;

    TaskExecutionSandboxFactory(
            TaskExecutionSandboxProperties properties,
            DockerSandboxControl dockerControl,
            Clock clock) {
        TaskExecutionSandboxProperties configured = Objects.requireNonNull(properties, "properties");
        this.workspaceRoot = configured.requiredWorkspaceRoot();
        this.repositoryMount = configured.requiredRepositoryMount();
        this.dependencyCacheRoot = configured.dependencyCacheRootPath();
        this.dependencyCacheMount = configured.requiredDependencyCacheMount();
        this.pauseMode = configured.requiredPauseMode();
        this.pauseStopTimeout = configured.requiredPauseStopTimeout();
        this.dockerControl = Objects.requireNonNull(dockerControl, "dockerControl");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Creates a new container or idempotently reconnects to the exact current fencing epoch. */
    public ManagedTaskExecutionSandbox provision(
            ExecutionWorkspace workspace,
            ManagedWorktree worktree,
            WorkspacePolicy policy,
            BuildProfile buildProfile,
            ExecutionLease lease,
            UtcTimestamp authoritativeNow) {
        TaskExecutionSandboxFacts.require(
                workspace, worktree, policy, buildProfile, lease, authoritativeNow);
        TaskExecutionSandboxDescriptor descriptor = descriptor(
                workspace, worktree, policy, buildProfile, lease);
        Optional<DockerContainerSnapshot> existing = dockerControl.inspect(
                descriptor.containerName());
        if (existing.isPresent()) {
            DockerContainerSnapshot current = existing.orElseThrow();
            if (descriptor.exactlyMatches(current)) {
                return reconnect(descriptor, current);
            }
            if (!descriptor.owns(current)) {
                throw TaskExecutionSandboxFacts.failure(
                        TaskExecutionSandboxError.CONTAINER_CONFLICT,
                        "Managed Sandbox name is occupied by an unowned container");
            }
            // A retained container from an older Lease/Fencing epoch never crosses ownership.
            dockerControl.remove(descriptor.containerName());
        }
        return create(descriptor);
    }

    /** Recovery has the same fail-closed reconciliation semantics as idempotent provision. */
    public ManagedTaskExecutionSandbox recover(
            ExecutionWorkspace workspace,
            ManagedWorktree worktree,
            WorkspacePolicy policy,
            BuildProfile buildProfile,
            ExecutionLease lease,
            UtcTimestamp authoritativeNow) {
        return provision(workspace, worktree, policy, buildProfile, lease, authoritativeNow);
    }

    /** Applies the configured pause policy without releasing durable Worktree state. */
    public void pause(
            ManagedTaskExecutionSandbox sandbox,
            ExecutionWorkspace workspace,
            ExecutionLease lease,
            UtcTimestamp authoritativeNow) {
        ManagedTaskExecutionSandbox managed = Objects.requireNonNull(sandbox, "sandbox");
        managed.requireSandboxIdentity(workspace);
        TaskExecutionSandboxFacts.requireOwnership(workspace, lease, authoritativeNow);
        DockerContainerSnapshot current = requireExactContainer(managed.descriptor());
        if (pauseMode == TaskExecutionSandboxPauseMode.STOP && current.running()) {
            dockerControl.stop(managed.containerName(), pauseStopTimeout);
        }
    }

    /** Removes the exact current container; repeated terminal cleanup is idempotent. */
    public void destroy(
            ManagedTaskExecutionSandbox sandbox, ExecutionWorkspace workspace) {
        ManagedTaskExecutionSandbox managed = Objects.requireNonNull(sandbox, "sandbox");
        managed.requireSandboxIdentity(workspace);
        Optional<DockerContainerSnapshot> current = dockerControl.inspect(managed.containerName());
        if (current.isEmpty()) {
            managed.markDestroyed();
            return;
        }
        if (!managed.descriptor().exactlyMatches(current.orElseThrow())) {
            throw TaskExecutionSandboxFacts.failure(
                    TaskExecutionSandboxError.CONTAINER_CONFLICT,
                    "Sandbox cleanup refused a different fencing epoch or security contract");
        }
        dockerControl.remove(managed.containerName());
        managed.markDestroyed();
    }

    private ManagedTaskExecutionSandbox create(TaskExecutionSandboxDescriptor descriptor) {
        Sandbox delegate = newAgentScopeSandbox(descriptor, Optional.empty());
        try {
            delegate.start();
            requireExactContainer(descriptor);
            return new ManagedTaskExecutionSandbox(
                    descriptor, delegate, dockerControl, clock);
        } catch (TaskExecutionSandboxException failure) {
            cleanupAfterFailedCreate(descriptor);
            throw failure;
        } catch (Exception failure) {
            cleanupAfterFailedCreate(descriptor);
            throw TaskExecutionSandboxFacts.failure(
                    TaskExecutionSandboxError.COMMAND_FAILED,
                    "AgentScope Docker Sandbox could not be started"
                            + sandboxErrorCodes(failure));
        }
    }

    private ManagedTaskExecutionSandbox reconnect(
            TaskExecutionSandboxDescriptor descriptor, DockerContainerSnapshot container) {
        Sandbox delegate = newAgentScopeSandbox(descriptor, Optional.of(container));
        try {
            delegate.start();
            requireExactContainer(descriptor);
            return new ManagedTaskExecutionSandbox(
                    descriptor, delegate, dockerControl, clock);
        } catch (TaskExecutionSandboxException failure) {
            throw failure;
        } catch (Exception failure) {
            throw TaskExecutionSandboxFacts.failure(
                    TaskExecutionSandboxError.COMMAND_FAILED,
                    "Managed Sandbox could not resume its verified container");
        }
    }

    private Sandbox newAgentScopeSandbox(
            TaskExecutionSandboxDescriptor descriptor,
            Optional<DockerContainerSnapshot> existing) {
        BindMountEntry worktreeMount = new BindMountEntry();
        worktreeMount.setHostPath(descriptor.canonicalWorktree().toString());
        worktreeMount.setReadOnly(false);

        WorkspaceSpec workspace = new WorkspaceSpec();
        workspace.setRoot(workspaceRoot);
        workspace.setEntries(Map.of(repositoryMount, worktreeMount));
        // Only platform constants are injected. Host environment and credentials are never copied.
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("HOME", "/tmp/crewscope-home");
        environment.put("MAVEN_CONFIG", "/tmp/crewscope-home/.m2");
        environment.put("TMPDIR", "/tmp");
        environment.put("CI", "true");
        environment.put("LANG", "C.UTF-8");
        dependencyCacheRoot.ifPresent(ignored -> environment.put(
                "MAVEN_ARGS",
                "--offline -Dmaven.repo.local=" + dependencyCacheMount + "/repository"));
        workspace.setEnvironment(environment);

        DockerFilesystemSpec filesystem = new DockerFilesystemSpec()
                .image(descriptor.buildProfile().sandboxImage().value())
                .workspaceRoot(workspaceRoot)
                .environment(environment)
                .memorySizeBytes(Math.multiplyExact(
                        (long) descriptor.budget().memoryMiB(), 1024 * 1024))
                .cpuCount((long) descriptor.budget().cpuCount())
                .network("none")
                .additionalRunArgs(additionalRunArguments(descriptor))
                .workspaceSpec(workspace);
        filesystem.workspaceProjectionEnabled(false);
        SandboxContext declaration = filesystem.toSandboxContext();
        @SuppressWarnings("unchecked")
        SandboxClient<SandboxClientOptions> client =
                (SandboxClient<SandboxClientOptions>) declaration.getClient();
        Sandbox delegate = client.create(
                workspace, declaration.getSnapshotSpec(), declaration.getClientOptions());
        if (!(delegate.getState() instanceof DockerSandboxState state)) {
            throw TaskExecutionSandboxFacts.failure(
                    TaskExecutionSandboxError.INVALID_CONFIGURATION,
                    "AgentScope did not create a Docker Sandbox state");
        }
        state.setSessionId(descriptor.sessionId());
        existing.ifPresent(container -> {
            state.setContainerId(container.id());
            state.setContainerName(container.name());
            state.setWorkspaceRootReady(true);
        });
        return delegate;
    }

    private List<String> additionalRunArguments(TaskExecutionSandboxDescriptor descriptor) {
        List<String> arguments = new ArrayList<>();
        arguments.add("--user");
        arguments.add(descriptor.containerUser());
        arguments.add("--read-only");
        arguments.add("--pids-limit");
        arguments.add(Integer.toString(descriptor.budget().pids()));
        arguments.add("--cap-drop");
        arguments.add("ALL");
        arguments.add("--security-opt");
        arguments.add("no-new-privileges");
        arguments.add("--tmpfs");
        arguments.add("/tmp:rw,nosuid,nodev,size=" + tmpfsSizeMiB(descriptor) + "m");
        arguments.add("--init");
        descriptor.dependencyCacheRoot().ifPresent(cacheRoot -> {
            arguments.add("--mount");
            arguments.add("type=bind,src=" + cacheRoot
                    + ",dst=" + descriptor.dependencyCacheMount() + ",readonly");
        });
        descriptor.labels().forEach((key, value) -> {
            arguments.add("--label");
            arguments.add(key + "=" + value);
        });
        return List.copyOf(arguments);
    }

    private static int tmpfsSizeMiB(TaskExecutionSandboxDescriptor descriptor) {
        return Math.max(32, Math.min(512, descriptor.budget().memoryMiB() / 2));
    }

    private TaskExecutionSandboxDescriptor descriptor(
            ExecutionWorkspace workspace,
            ManagedWorktree worktree,
            WorkspacePolicy policy,
            BuildProfile buildProfile,
            ExecutionLease lease) {
        Path canonical = canonicalWorktree(worktree);
        String containerUser = containerUser(canonical);
        return TaskExecutionSandboxDescriptor.create(
                workspace,
                worktree,
                policy,
                buildProfile,
                canonical,
                dependencyCacheRoot,
                workspaceRoot,
                repositoryMount,
                dependencyCacheMount,
                containerUser);
    }

    private static Path canonicalWorktree(ManagedWorktree worktree) {
        try {
            Path canonical = Objects.requireNonNull(worktree, "worktree").canonicalPath();
            if (Files.isSymbolicLink(canonical)
                    || !Files.isDirectory(canonical, NO_FOLLOW_LINKS)) {
                throw TaskExecutionSandboxFacts.failure(
                        TaskExecutionSandboxError.WORKSPACE_MISMATCH,
                        "Managed Worktree mount is no longer a canonical directory");
            }
            return canonical.toRealPath(LinkOption.NOFOLLOW_LINKS);
        } catch (TaskExecutionSandboxException failure) {
            throw failure;
        } catch (Exception failure) {
            throw TaskExecutionSandboxFacts.failure(
                    TaskExecutionSandboxError.WORKSPACE_MISMATCH,
                    "Managed Worktree mount could not be verified");
        }
    }

    private static String containerUser(Path canonicalWorktree) {
        try {
            Number uid = (Number) Files.getAttribute(
                    canonicalWorktree, "unix:uid", LinkOption.NOFOLLOW_LINKS);
            Number gid = (Number) Files.getAttribute(
                    canonicalWorktree, "unix:gid", LinkOption.NOFOLLOW_LINKS);
            long user = uid.longValue();
            long group = gid.longValue();
            if (user < 1 || group < 1) {
                throw TaskExecutionSandboxFacts.failure(
                        TaskExecutionSandboxError.INVALID_CONFIGURATION,
                        "Managed Worktree must be owned by an ordinary host user and group");
            }
            return user + ":" + group;
        } catch (TaskExecutionSandboxException failure) {
            throw failure;
        } catch (Exception failure) {
            throw TaskExecutionSandboxFacts.failure(
                    TaskExecutionSandboxError.INVALID_CONFIGURATION,
                    "Managed Worktree owner cannot be mapped to a Docker user");
        }
    }

    private DockerContainerSnapshot requireExactContainer(
            TaskExecutionSandboxDescriptor descriptor) {
        DockerContainerSnapshot current = dockerControl.inspect(descriptor.containerName())
                .orElseThrow(() -> TaskExecutionSandboxFacts.failure(
                        TaskExecutionSandboxError.CONTAINER_CORRUPT,
                        "Managed Sandbox container disappeared during lifecycle transition"));
        if (!descriptor.exactlyMatches(current)) {
            throw TaskExecutionSandboxFacts.failure(
                    TaskExecutionSandboxError.CONTAINER_CORRUPT,
                    "Managed Sandbox container does not match its immutable security contract");
        }
        return current;
    }

    private void cleanupAfterFailedCreate(TaskExecutionSandboxDescriptor descriptor) {
        Optional<DockerContainerSnapshot> residue = dockerControl.inspect(descriptor.containerName());
        if (residue.isEmpty()) {
            return;
        }
        if (!descriptor.owns(residue.orElseThrow())) {
            throw TaskExecutionSandboxFacts.failure(
                    TaskExecutionSandboxError.CLEANUP_FAILED,
                    "Failed Sandbox creation left an unowned name collision");
        }
        dockerControl.remove(descriptor.containerName());
    }

    private static String sandboxErrorCodes(Throwable failure) {
        StringBuilder codes = new StringBuilder();
        Throwable current = failure;
        while (current != null) {
            if (current instanceof io.agentscope.harness.agent.sandbox.SandboxException sandbox) {
                if (codes.isEmpty()) {
                    codes.append(" [");
                } else {
                    codes.append(" -> ");
                }
                codes.append(sandbox.getErrorCode());
            }
            current = current.getCause();
        }
        return codes.isEmpty() ? "" : codes.append(']').toString();
    }
}

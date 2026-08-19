package io.crewscope.infrastructure.workspace.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxException;
import io.crewscope.domain.coding.AllowedPathSet;
import io.crewscope.domain.coding.BuildCommand;
import io.crewscope.domain.coding.BuildProfile;
import io.crewscope.domain.coding.BuildProfileReference;
import io.crewscope.domain.coding.BuildTool;
import io.crewscope.domain.coding.CodingTargetSnapshotReference;
import io.crewscope.domain.coding.CommandCatalog;
import io.crewscope.domain.coding.CommandKind;
import io.crewscope.domain.coding.CommandTermination;
import io.crewscope.domain.coding.ExecutionWorkspace;
import io.crewscope.domain.coding.ExecutionWorkspaceFingerprint;
import io.crewscope.domain.coding.ExecutionWorkspaceId;
import io.crewscope.domain.coding.ExecutionWorkspaceKey;
import io.crewscope.domain.coding.ExecutionWorkspaceOwnership;
import io.crewscope.domain.coding.ManagedWorkspaceBranch;
import io.crewscope.domain.coding.RepositoryCommitId;
import io.crewscope.domain.coding.RepositoryKey;
import io.crewscope.domain.coding.SandboxImageReference;
import io.crewscope.domain.coding.SandboxNetworkMode;
import io.crewscope.domain.coding.SandboxResourceBudget;
import io.crewscope.domain.coding.WorkspaceOperationBudget;
import io.crewscope.domain.coding.WorkspacePolicy;
import io.crewscope.domain.coding.WorkspacePolicyId;
import io.crewscope.domain.coding.WorkspacePolicyReference;
import io.crewscope.domain.runtime.ExecutionRuntimeId;
import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.runtime.RuntimeWorkerId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.task.ExecutionLease;
import io.crewscope.domain.task.ExecutionLeaseId;
import io.crewscope.domain.task.ExecutionLeasePhase;
import io.crewscope.domain.task.FencingToken;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.infrastructure.workspace.git.GitCommandExecutor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real Docker proof for M4-I04 TaskExecution Sandbox ownership and security boundaries. */
class TaskExecutionSandboxFactoryM4I04DockerIntegrationTest {

    private static final String IMAGE =
            "maven@sha256:29a1658b1f3078e07c2b17f7b519b45eb47f65d9628e887eac45a8c5c8f939d4";
    private static final Instant NOW = Instant.parse("2026-08-18T12:00:00Z");

    @TempDir Path temporaryDirectory;

    private final Set<String> containerNames = new HashSet<>();
    private DockerCliSandboxControl dockerControl;
    private TaskExecutionSandboxFactory factory;

    @BeforeEach
    void requireDockerAndPinnedImage() {
        Assumptions.assumeTrue(commandSucceeds("docker", "info"), "Docker daemon is required");
        Assumptions.assumeTrue(
                commandSucceeds("docker", "image", "inspect", IMAGE),
                "Pinned M4 Sandbox image is required");
        TaskExecutionSandboxProperties properties = new TaskExecutionSandboxProperties();
        properties.setDockerCommandTimeout(Duration.ofSeconds(20));
        properties.setPauseStopTimeout(Duration.ofSeconds(1));
        dockerControl = new DockerCliSandboxControl(new ObjectMapper(), Duration.ofSeconds(20));
        factory = new TaskExecutionSandboxFactory(
                properties,
                dockerControl,
                Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC));
    }

    @AfterEach
    void removeExactTestContainers() {
        containerNames.forEach(name -> {
            if (dockerControl.inspect(name).isPresent()) {
                dockerControl.remove(name);
            }
        });
    }

    @Test
    void provisionsAgentScopeSandboxWithFixedMountAndHardSecurityLimits() throws Exception {
        SandboxFacts facts = facts(FencingToken.initial());
        ManagedTaskExecutionSandbox managed = provision(facts);

        DockerContainerSnapshot container = dockerControl.inspect(managed.containerName())
                .orElseThrow();
        assertTrue(facts.descriptor().exactlyMatches(container));
        assertTrue(container.running());
        assertFalse(container.configuredUser().startsWith("0:"));
        assertEquals("none", container.networkMode());
        assertTrue(container.readOnlyRootFilesystem());
        assertEquals(256L * 1024 * 1024, container.memoryBytes());
        assertEquals(1_000_000_000L, container.nanoCpus());
        assertEquals(32, container.pidsLimit());

        Sandbox external;
        try (TaskExecutionSandboxCall call = managed.openCall(
                facts.workspace(), facts.lease(), now())) {
            external = call.sandboxContext().getExternalSandbox();
            assertNull(external.getState().getWorkspaceSpec());
            external.start();
            ExecResult result = external.exec(
                    null,
                    "test \"$(id -u)\" != 0"
                            + " && test ! -e /sys/class/net/eth0"
                            + " && test \"$HOME\" = /tmp/crewscope-home"
                            + " && test \"$MAVEN_CONFIG\" = /tmp/crewscope-home/.m2"
                            + " && test \"$CI\" = true"
                            + " && test \"$LANG\" = C.UTF-8"
                            + " && test -z \"${AWS_SECRET_ACCESS_KEY:-}\""
                            + " && printf sandbox-ok > repository/m4-i04.txt",
                    5);
            assertEquals(0, result.exitCode());
            assertEquals("sandbox-ok", Files.readString(
                    facts.worktreePath().resolve("m4-i04.txt")));
            assertThrows(
                    SandboxException.ExecException.class,
                    () -> external.exec(null, "touch /crewscope-root-write", 5));
        }

        assertThrows(
                TaskExecutionSandboxException.class,
                () -> external.exec(null, "true", 1));
        factory.destroy(managed, facts.workspace());
        assertTrue(dockerControl.inspect(managed.containerName()).isEmpty());
    }

    @Test
    void idempotentProvisionReconnectsTheSameContainer() throws Exception {
        SandboxFacts facts = facts(FencingToken.initial());
        ManagedTaskExecutionSandbox first = provision(facts);
        String originalId = dockerControl.inspect(first.containerName()).orElseThrow().id();

        ManagedTaskExecutionSandbox second = factory.provision(
                facts.workspace(),
                facts.worktree(),
                facts.policy(),
                facts.buildProfile(),
                facts.lease(),
                now());

        assertEquals(originalId, dockerControl.inspect(second.containerName()).orElseThrow().id());
        assertEquals(first.fingerprint(), second.fingerprint());

        when(facts.lease().phase()).thenReturn(ExecutionLeasePhase.RUN);
        ManagedTaskExecutionSandbox running = factory.provision(
                facts.workspace(),
                facts.worktree(),
                facts.policy(),
                facts.buildProfile(),
                facts.lease(),
                now());
        assertEquals(originalId, dockerControl.inspect(running.containerName()).orElseThrow().id());
        factory.destroy(running, facts.workspace());
    }

    @Test
    void pauseStopsAndRecoveryRestartsTheRetainedContainer() throws Exception {
        SandboxFacts facts = facts(FencingToken.initial());
        ManagedTaskExecutionSandbox initial = provision(facts);
        String originalId = dockerControl.inspect(initial.containerName()).orElseThrow().id();

        factory.pause(initial, facts.workspace(), facts.lease(), now());
        assertFalse(dockerControl.inspect(initial.containerName()).orElseThrow().running());

        ManagedTaskExecutionSandbox recovered = factory.recover(
                facts.workspace(),
                facts.worktree(),
                facts.policy(),
                facts.buildProfile(),
                facts.lease(),
                now());
        DockerContainerSnapshot resumed = dockerControl.inspect(recovered.containerName())
                .orElseThrow();
        assertEquals(originalId, resumed.id());
        assertTrue(resumed.running());
        factory.destroy(recovered, facts.workspace());
    }

    @Test
    void newerFencingEpochRemovesStaleContainerBeforeRecovery() throws Exception {
        SandboxFacts oldFacts = facts(FencingToken.initial());
        ManagedTaskExecutionSandbox oldSandbox = provision(oldFacts);
        String staleId = dockerControl.inspect(oldSandbox.containerName()).orElseThrow().id();

        SandboxFacts current = reboundFacts(oldFacts, oldFacts.ownership().fencingToken().next());
        ManagedTaskExecutionSandbox recovered = factory.recover(
                current.workspace(),
                current.worktree(),
                current.policy(),
                current.buildProfile(),
                current.lease(),
                now());
        DockerContainerSnapshot active = dockerControl.inspect(recovered.containerName())
                .orElseThrow();

        assertNotEquals(staleId, active.id());
        assertEquals(
                Long.toString(current.ownership().fencingToken().value()),
                active.labels().get("io.crewscope.sandbox.fencing-token"));
        assertEquals(0, countContainerId(staleId));
        assertEquals(1, countContainerId(active.id()));
        TaskExecutionSandboxException staleLease = assertThrows(
                TaskExecutionSandboxException.class,
                () -> factory.recover(
                        current.workspace(),
                        current.worktree(),
                        current.policy(),
                        current.buildProfile(),
                        oldFacts.lease(),
                        now()));
        assertEquals(TaskExecutionSandboxError.OWNERSHIP_MISMATCH, staleLease.error());
        assertEquals(active.id(), dockerControl.inspect(recovered.containerName()).orElseThrow().id());
        factory.destroy(recovered, current.workspace());
    }

    @Test
    void staleSandboxHandleCannotDestroyTheCurrentFencingEpoch() throws Exception {
        SandboxFacts oldFacts = facts(FencingToken.initial());
        ManagedTaskExecutionSandbox stale = provision(oldFacts);

        SandboxFacts current = reboundFacts(oldFacts, oldFacts.ownership().fencingToken().next());
        ManagedTaskExecutionSandbox active = factory.recover(
                current.workspace(),
                current.worktree(),
                current.policy(),
                current.buildProfile(),
                current.lease(),
                now());
        String activeId = dockerControl.inspect(active.containerName()).orElseThrow().id();

        TaskExecutionSandboxException conflict = assertThrows(
                TaskExecutionSandboxException.class,
                () -> factory.destroy(stale, oldFacts.workspace()));
        assertEquals(TaskExecutionSandboxError.CONTAINER_CONFLICT, conflict.error());
        assertEquals(activeId, dockerControl.inspect(active.containerName()).orElseThrow().id());

        factory.destroy(active, current.workspace());
    }

    @Test
    void guardRejectsConcurrentAndExpiredLeaseWindows() throws Exception {
        SandboxFacts facts = facts(FencingToken.initial());
        ManagedTaskExecutionSandbox managed = provision(facts);

        try (TaskExecutionSandboxCall ignored = managed.openCall(
                facts.workspace(), facts.lease(), now())) {
            TaskExecutionSandboxException busy = assertThrows(
                    TaskExecutionSandboxException.class,
                    () -> managed.openCall(facts.workspace(), facts.lease(), now()));
            assertEquals(TaskExecutionSandboxError.SANDBOX_BUSY, busy.error());
        }

        ExecutionLease expired = lease(facts.workspace(), facts.ownership(), false);
        TaskExecutionSandboxException failure = assertThrows(
                TaskExecutionSandboxException.class,
                () -> managed.openCall(facts.workspace(), expired, now()));
        assertEquals(TaskExecutionSandboxError.LEASE_EXPIRED, failure.error());
        factory.destroy(managed, facts.workspace());
    }

    @Test
    void commandTimeoutAndOutputAreBoundedByWorkspacePolicy() throws Exception {
        SandboxFacts facts = facts(FencingToken.initial());
        ManagedTaskExecutionSandbox managed = provision(facts);
        try (TaskExecutionSandboxCall call = managed.openCall(
                facts.workspace(), facts.lease(), now())) {
            Sandbox external = call.sandboxContext().getExternalSandbox();
            external.start();
            TaskExecutionSandboxException timeout = assertThrows(
                    TaskExecutionSandboxException.class,
                    () -> external.exec(null, "true", 11));
            assertEquals(TaskExecutionSandboxError.POLICY_MISMATCH, timeout.error());
            ExecResult output = external.exec(
                    null,
                    "head -c 8192 /dev/zero | tr '\\0' x",
                    5);
            assertTrue(output.truncated());
            assertTrue(output.stdout().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                    <= facts.policy().sandboxBudget().maxCommandOutputBytes());

            ExecResult multibyteOutput = external.exec(
                    null,
                    "i=0; while [ $i -lt 2048 ]; do printf '你'; i=$((i + 1)); done",
                    5);
            assertTrue(multibyteOutput.truncated());
            assertTrue(multibyteOutput.stdout()
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)
                            .length
                    <= facts.policy().sandboxBudget().maxCommandOutputBytes());
        }
        factory.destroy(managed, facts.workspace());
    }

    @Test
    void m4I05DelegatesReadOnlyInspectionToAgentScopeFilesystemInsideGuardedCall()
            throws Exception {
        SandboxFacts facts = facts(FencingToken.initial());
        Path source = Files.createDirectories(facts.worktreePath().resolve("src/nested"));
        Files.writeString(facts.worktreePath().resolve("src/Main.java"), "one\ntwo\nthree\n");
        Files.writeString(source.resolve("Nested.java"), "class Nested {}\n");
        Files.writeString(facts.worktreePath().resolve("src/.env"), "TOKEN=secret\n");
        Files.write(facts.worktreePath().resolve("src/image.png"), new byte[] {0, 1, 2, 3});
        ManagedTaskExecutionSandbox managed = provision(facts);

        RepositoryInspectionToolFactory inspectionFactory = new RepositoryInspectionToolFactory(
                new RepositoryInspectionProperties(), mock(GitCommandExecutor.class));
        ManagedRepository repository = new ManagedRepository(
                facts.worktree().repositoryKey(),
                temporaryDirectory.resolve("managed.git").toAbsolutePath());
        RepositoryInspectionTool staleTool;
        RuntimeContext runtimeContext = RuntimeContext.builder()
                .userId("m4-i05-user")
                .sessionId("m4-i05-session")
                .build();
        try (RepositoryInspectionSession session = inspectionFactory.open(
                managed,
                facts.workspace(),
                facts.worktree(),
                repository,
                facts.policy(),
                facts.lease(),
                now())) {
            RepositoryInspectionTool tool = session.tool();
            staleTool = tool;
            String listing = tool.list(runtimeContext, "src", 0, 20);
            assertTrue(listing.contains("src/Main.java"));
            assertFalse(listing.contains(".env"));
            assertTrue(tool.tree(runtimeContext, "src", 2, 0, 20)
                    .contains("src/nested/Nested.java"));
            String read = tool.read(runtimeContext, "src/Main.java", 1, 1);
            assertTrue(read.contains("2:two"));
            assertTrue(read.contains("hasMore=true"));
            assertTrue(tool.grep(runtimeContext, "Nested", "src", "*.java", 0, 20)
                    .contains("src/nested/Nested.java:1"));
            assertTrue(tool.glob(runtimeContext, "**/*.java", "src", 0, 20)
                    .contains("src/Main.java"));
            RepositoryInspectionException binary = assertThrows(
                    RepositoryInspectionException.class,
                    () -> tool.read(runtimeContext, "src/image.png", 0, 1));
            assertEquals(RepositoryInspectionError.BINARY_FILE, binary.error());
        }

        RepositoryInspectionException closed = assertThrows(
                RepositoryInspectionException.class,
                () -> staleTool.list(runtimeContext, "src", 0, 1));
        assertEquals(RepositoryInspectionError.INVALID_CONTEXT, closed.error());
        factory.destroy(managed, facts.workspace());
    }

    @Test
    void m4I06MutatesOnlyThroughControlledAgentScopeFilesystemAndKeepsSessionUsage()
            throws Exception {
        SandboxFacts facts = facts(FencingToken.initial());
        Files.createDirectories(facts.worktreePath().resolve("src"));
        Files.writeString(facts.worktreePath().resolve("src/Main.java"), "one\ntwo\nthree\n");
        ManagedTaskExecutionSandbox managed = provision(facts);

        GitCommandExecutor gitCommands = mock(GitCommandExecutor.class);
        when(gitCommands.inspectionStatus(any(Path.class), any(AllowedPathSet.class)))
                .thenReturn("");
        CodingFilesystemToolFactory filesystemFactory = new CodingFilesystemToolFactory(
                new CodingFilesystemProperties(),
                gitCommands,
                new CodingFilesystemUsageRegistry());
        RuntimeContext runtimeContext = RuntimeContext.builder()
                .userId("m4-i06-user")
                .sessionId("m4-i06-session")
                .build();
        CodingFilesystemTool staleTool;

        try (CodingFilesystemSession session = filesystemFactory.open(
                managed,
                facts.workspace(),
                facts.worktree(),
                facts.policy(),
                facts.lease(),
                now())) {
            CodingFilesystemTool tool = session.tool();
            staleTool = tool;
            assertTrue(tool.create(runtimeContext, "src/New.java", "class New {}\n")
                    .contains("writeOperations=1"));
            assertTrue(tool.edit(runtimeContext, "src/Main.java", "two", "TWO", false)
                    .contains("status=edited"));
            assertTrue(tool.patch(
                            runtimeContext,
                            "src/Main.java",
                            "@@ -1,3 +1,4 @@\n one\n-TWO\n+second\n three\n+four\n")
                    .contains("status=patched"));
            assertTrue(tool.move(runtimeContext, "src/New.java", "src/Renamed.java")
                    .contains("status=moved"));
            assertTrue(tool.delete(runtimeContext, "src/Renamed.java")
                    .contains("writeOperations=5"));
        }

        CodingFilesystemException closed = assertThrows(
                CodingFilesystemException.class,
                () -> staleTool.create(runtimeContext, "src/Blocked.java", "blocked\n"));
        assertEquals(CodingFilesystemError.INVALID_CONTEXT, closed.error());
        assertEquals("one\nsecond\nthree\nfour\n", Files.readString(
                facts.worktreePath().resolve("src/Main.java")));
        assertFalse(Files.exists(facts.worktreePath().resolve("src/New.java")));
        assertFalse(Files.exists(facts.worktreePath().resolve("src/Renamed.java")));

        try (CodingFilesystemSession resumed = filesystemFactory.open(
                managed,
                facts.workspace(),
                facts.worktree(),
                facts.policy(),
                facts.lease(),
                now())) {
            assertTrue(resumed.tool()
                    .edit(runtimeContext, "src/Main.java", "second", "SECOND", false)
                    .contains("writeOperations=6"));
        }
        assertEquals("one\nSECOND\nthree\nfour\n", Files.readString(
                facts.worktreePath().resolve("src/Main.java")));

        factory.destroy(managed, facts.workspace());
        assertTrue(dockerControl.inspect(managed.containerName()).isEmpty());
    }

    @Test
    void m4I07TimeoutTerminatesTheContainerProcessTreeAndRestartsTheSandbox() throws Exception {
        SandboxFacts facts = facts(FencingToken.initial());
        Path scripts = Files.createDirectories(facts.worktreePath().resolve("scripts"));
        Path command = scripts.resolve("m4-i07.sh");
        Files.writeString(
                command,
                "#!/bin/sh\n(sleep 2; printf leaked > src/late.txt) &\nwait\n");
        Files.createDirectories(facts.worktreePath().resolve("src"));
        Files.setPosixFilePermissions(
                command,
                java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        ManagedTaskExecutionSandbox managed = provision(facts);
        RuntimeContext runtimeContext = RuntimeContext.builder()
                .userId("m4-i07-user")
                .sessionId("m4-i07-session")
                .build();
        BuildProfileCommandRunner runner = new BuildProfileCommandRunner(Clock.systemUTC());

        try (TaskExecutionSandboxCall call = managed.openCall(
                facts.workspace(), facts.lease(), now())) {
            SandboxCommandExecution timeout = runner.run(
                    call,
                    runtimeContext,
                    facts.descriptor().repositoryContainerPath(),
                    facts.policy(),
                    facts.buildProfile(),
                    CommandKind.TEST,
                    List.of(),
                    List.of(),
                    1);
            assertEquals(CommandTermination.TIMED_OUT, timeout.termination());
            assertTrue(dockerControl.inspect(managed.containerName()).orElseThrow().running());

            Files.writeString(command, "#!/bin/sh\nprintf recovered\n");
            SandboxCommandExecution recovered = runner.run(
                    call,
                    runtimeContext,
                    facts.descriptor().repositoryContainerPath(),
                    facts.policy(),
                    facts.buildProfile(),
                    CommandKind.TEST,
                    List.of(),
                    List.of(),
                    3);
            assertEquals(CommandTermination.EXITED, recovered.termination());
            assertEquals("recovered", recovered.stdout());
        }

        Thread.sleep(2_500);
        assertFalse(Files.exists(facts.worktreePath().resolve("src/late.txt")));
        factory.destroy(managed, facts.workspace());
    }

    private ManagedTaskExecutionSandbox provision(SandboxFacts facts) {
        containerNames.add(facts.descriptor().containerName());
        return factory.provision(
                facts.workspace(),
                facts.worktree(),
                facts.policy(),
                facts.buildProfile(),
                facts.lease(),
                now());
    }

    private SandboxFacts facts(FencingToken fencingToken) throws Exception {
        Path worktreePath = Files.createDirectory(
                temporaryDirectory.resolve("worktree-" + fencingToken.value()));
        ExecutionWorkspaceId workspaceId = ExecutionWorkspaceId.generate();
        TaskExecutionId executionId = TaskExecutionId.generate();
        RepositoryKey repositoryKey = new RepositoryKey("crewscope");
        RepositoryCommitId baseline = new RepositoryCommitId("a".repeat(40));
        ExecutionWorkspaceKey workspaceKey = ExecutionWorkspaceKey.derive(workspaceId, 1);
        CodingTargetSnapshotReference target = mock(CodingTargetSnapshotReference.class);
        WorkItemScope scope = mock(WorkItemScope.class);
        when(scope.organizationId()).thenReturn(OrganizationId.generate());
        TaskId taskId = TaskId.generate();
        ExecutionWorkspaceOwnership ownership = new ExecutionWorkspaceOwnership(
                new RuntimeEnvironment("test"),
                ExecutionRuntimeId.generate(),
                RuntimeWorkerId.generate(),
                ExecutionLeaseId.generate(),
                fencingToken);
        ExecutionWorkspace workspace = workspace(
                workspaceId,
                executionId,
                repositoryKey,
                baseline,
                workspaceKey,
                target,
                scope,
                taskId,
                ownership,
                "workspace-" + fencingToken.value());
        ManagedWorktree worktree = new ManagedWorktree(
                workspaceId,
                repositoryKey,
                workspaceKey,
                ManagedWorkspaceBranch.derive(executionId, 1),
                baseline,
                baseline,
                new WorkspacePhysicalFingerprint(TaskFactHash.sha256(
                        "physical-" + fencingToken.value()).value()),
                worktreePath.toRealPath());
        BuildProfile buildProfile = BuildProfile.define(
                "java-project-script",
                1,
                BuildTool.PROJECT_SCRIPT,
                17,
                new SandboxImageReference(IMAGE),
                CommandCatalog.of(
                        CommandKind.TEST,
                        new BuildCommand(
                                "command.test",
                                List.of("./scripts/m4-i07.sh"),
                                ".",
                                1,
                                10)));
        BuildProfileReference profileReference = buildProfile.reference();
        WorkspacePolicy policy = policy(
                workspace, target, scope, taskId, profileReference);
        ExecutionLease lease = lease(workspace, ownership, true);
        TaskExecutionSandboxDescriptor descriptor = TaskExecutionSandboxDescriptor.create(
                workspace,
                worktree,
                policy,
                buildProfile,
                worktreePath.toRealPath(),
                "/workspace",
                "repository",
                unixUser(worktreePath));
        return new SandboxFacts(
                workspace,
                worktree,
                policy,
                buildProfile,
                lease,
                ownership,
                worktreePath,
                descriptor);
    }

    private SandboxFacts reboundFacts(SandboxFacts previous, FencingToken fencingToken)
            throws Exception {
        ExecutionWorkspaceOwnership ownership = new ExecutionWorkspaceOwnership(
                previous.ownership().environment(),
                previous.ownership().runtimeId(),
                previous.ownership().workerId(),
                ExecutionLeaseId.generate(),
                fencingToken);
        ExecutionWorkspace workspace = workspace(
                previous.workspace().id(),
                previous.workspace().taskExecutionId(),
                previous.workspace().repositoryKey(),
                previous.workspace().baselineCommit(),
                previous.workspace().workspaceKey(),
                previous.workspace().codingTarget(),
                previous.workspace().scope(),
                previous.workspace().taskId(),
                ownership,
                "workspace-" + fencingToken.value());
        ManagedWorktree worktree = new ManagedWorktree(
                previous.worktree().workspaceId(),
                previous.worktree().repositoryKey(),
                previous.worktree().workspaceKey(),
                previous.worktree().managedBranch(),
                previous.worktree().baselineCommit(),
                previous.worktree().headCommit(),
                new WorkspacePhysicalFingerprint(
                        TaskFactHash.sha256("physical-" + fencingToken.value()).value()),
                previous.worktreePath().toRealPath());
        WorkspacePolicy policy = policy(
                workspace,
                workspace.codingTarget(),
                workspace.scope(),
                workspace.taskId(),
                previous.buildProfile().reference());
        ExecutionLease lease = lease(workspace, ownership, true);
        TaskExecutionSandboxDescriptor descriptor = TaskExecutionSandboxDescriptor.create(
                workspace,
                worktree,
                policy,
                previous.buildProfile(),
                previous.worktreePath().toRealPath(),
                "/workspace",
                "repository",
                unixUser(previous.worktreePath()));
        containerNames.add(descriptor.containerName());
        return new SandboxFacts(
                workspace,
                worktree,
                policy,
                previous.buildProfile(),
                lease,
                ownership,
                previous.worktreePath(),
                descriptor);
    }

    private static ExecutionWorkspace workspace(
            ExecutionWorkspaceId workspaceId,
            TaskExecutionId executionId,
            RepositoryKey repositoryKey,
            RepositoryCommitId baseline,
            ExecutionWorkspaceKey workspaceKey,
            CodingTargetSnapshotReference target,
            WorkItemScope scope,
            TaskId taskId,
            ExecutionWorkspaceOwnership ownership,
            String fingerprintSeed) {
        ExecutionWorkspace workspace = mock(ExecutionWorkspace.class);
        when(workspace.id()).thenReturn(workspaceId);
        when(workspace.scope()).thenReturn(scope);
        when(workspace.taskId()).thenReturn(taskId);
        when(workspace.taskExecutionId()).thenReturn(executionId);
        when(workspace.attempt()).thenReturn(1);
        when(workspace.codingTarget()).thenReturn(target);
        when(workspace.repositoryKey()).thenReturn(repositoryKey);
        when(workspace.baselineCommit()).thenReturn(baseline);
        when(workspace.workspaceKey()).thenReturn(workspaceKey);
        when(workspace.managedBranch()).thenReturn(ManagedWorkspaceBranch.derive(executionId, 1));
        when(workspace.ownership()).thenReturn(ownership);
        when(workspace.fingerprint()).thenReturn(new ExecutionWorkspaceFingerprint(
                TaskFactHash.sha256(fingerprintSeed).value()));
        return workspace;
    }

    private static WorkspacePolicy policy(
            ExecutionWorkspace workspace,
            CodingTargetSnapshotReference target,
            WorkItemScope scope,
            TaskId taskId,
            BuildProfileReference profileReference) {
        TaskExecutionId executionId = workspace.taskExecutionId();
        int attempt = workspace.attempt();
        WorkspacePolicy policy = mock(WorkspacePolicy.class);
        when(policy.scope()).thenReturn(scope);
        when(policy.taskId()).thenReturn(taskId);
        when(policy.taskExecutionId()).thenReturn(executionId);
        when(policy.attempt()).thenReturn(attempt);
        when(policy.codingTarget()).thenReturn(target);
        when(policy.buildProfile()).thenReturn(profileReference);
        TaskFactHash policyHash = TaskFactHash.sha256("policy");
        when(policy.policyHash()).thenReturn(policyHash);
        when(policy.reference()).thenReturn(new WorkspacePolicyReference(
                WorkspacePolicyId.generate(), policyHash));
        when(policy.commandCatalog()).thenReturn(new CommandCatalog(Map.of(
                CommandKind.TEST,
                new BuildCommand(
                        "command.test",
                        List.of("./scripts/m4-i07.sh"),
                        ".",
                        1,
                        10))));
        when(policy.allowedPaths()).thenReturn(AllowedPathSet.of("src"));
        when(policy.sandboxBudget()).thenReturn(new SandboxResourceBudget(
                SandboxNetworkMode.NONE, 1, 256, 32, 10, 4096, true));
        when(policy.operationBudget()).thenReturn(new WorkspaceOperationBudget(
                10, 20, 1024 * 1024, 20, 1024 * 1024, 1024 * 1024, 2));
        return policy;
    }

    private static ExecutionLease lease(
            ExecutionWorkspace workspace,
            ExecutionWorkspaceOwnership ownership,
            boolean active) {
        TaskExecutionId executionId = workspace.taskExecutionId();
        int attempt = workspace.attempt();
        ExecutionLease lease = mock(ExecutionLease.class);
        when(lease.id()).thenReturn(ownership.leaseId());
        when(lease.environment()).thenReturn(ownership.environment());
        when(lease.taskExecutionId()).thenReturn(executionId);
        when(lease.attempt()).thenReturn(attempt);
        when(lease.runtimeId()).thenReturn(ownership.runtimeId());
        when(lease.workerId()).thenReturn(ownership.workerId());
        when(lease.fencingToken()).thenReturn(ownership.fencingToken());
        when(lease.phase()).thenReturn(ExecutionLeasePhase.PREPARE);
        when(lease.isActiveAt(any(UtcTimestamp.class))).thenReturn(active);
        return lease;
    }

    private static String unixUser(Path path) throws IOException {
        Number uid = (Number) Files.getAttribute(path, "unix:uid");
        Number gid = (Number) Files.getAttribute(path, "unix:gid");
        return uid.longValue() + ":" + gid.longValue();
    }

    private static UtcTimestamp now() {
        return UtcTimestamp.from(NOW);
    }

    private static int countContainerId(String id) throws Exception {
        Process process = new ProcessBuilder(
                "docker", "ps", "-aq", "--no-trunc", "--filter", "id=" + id).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        return output.isBlank() ? 0 : output.strip().split("\\R").length;
    }

    private static boolean commandSucceeds(String... command) {
        try {
            Process process = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return process.waitFor(20, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception failure) {
            return false;
        }
    }

    private record SandboxFacts(
            ExecutionWorkspace workspace,
            ManagedWorktree worktree,
            WorkspacePolicy policy,
            BuildProfile buildProfile,
            ExecutionLease lease,
            ExecutionWorkspaceOwnership ownership,
            Path worktreePath,
            TaskExecutionSandboxDescriptor descriptor) {}
}

package io.crewscope.infrastructure.workspace.repository;

import io.crewscope.domain.coding.BuildProfile;
import io.crewscope.domain.coding.ExecutionWorkspace;
import io.crewscope.domain.coding.ExecutionWorkspaceOwnership;
import io.crewscope.domain.coding.SandboxResourceBudget;
import io.crewscope.domain.coding.WorkspacePolicy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Complete internal desired state for one CrewScope-owned AgentScope Docker Sandbox. */
record TaskExecutionSandboxDescriptor(
        ExecutionWorkspace workspace,
        ManagedWorktree worktree,
        WorkspacePolicy policy,
        BuildProfile buildProfile,
        Path canonicalWorktree,
        String workspaceRoot,
        String repositoryMount,
        String containerUser,
        TaskExecutionSandboxFingerprint fingerprint,
        String sessionId,
        String containerName,
        Map<String, String> labels) {

    static final String LABEL_PREFIX = "io.crewscope.sandbox.";

    TaskExecutionSandboxDescriptor {
        workspace = Objects.requireNonNull(workspace, "workspace");
        worktree = Objects.requireNonNull(worktree, "worktree");
        policy = Objects.requireNonNull(policy, "policy");
        buildProfile = Objects.requireNonNull(buildProfile, "buildProfile");
        canonicalWorktree = Objects.requireNonNull(canonicalWorktree, "canonicalWorktree");
        workspaceRoot = Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        repositoryMount = Objects.requireNonNull(repositoryMount, "repositoryMount");
        containerUser = Objects.requireNonNull(containerUser, "containerUser");
        fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
        containerName = Objects.requireNonNull(containerName, "containerName");
        labels = Map.copyOf(Objects.requireNonNull(labels, "labels"));
    }

    static TaskExecutionSandboxDescriptor create(
            ExecutionWorkspace workspace,
            ManagedWorktree worktree,
            WorkspacePolicy policy,
            BuildProfile buildProfile,
            Path canonicalWorktree,
            String workspaceRoot,
            String repositoryMount,
            String containerUser) {
        TaskExecutionSandboxFingerprint fingerprint = fingerprint(
                workspace,
                worktree,
                policy,
                buildProfile,
                canonicalWorktree,
                workspaceRoot,
                repositoryMount,
                containerUser);
        String sessionId = sessionId(workspace);
        String containerName = "agentscope-sandbox-" + sessionId;
        ExecutionWorkspaceOwnership ownership = workspace.ownership();
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(LABEL_PREFIX + "managed", "true");
        labels.put(
                LABEL_PREFIX + "organization-id",
                workspace.scope().organizationId().toString());
        labels.put(LABEL_PREFIX + "environment", ownership.environment().value());
        labels.put(LABEL_PREFIX + "workspace-key", workspace.workspaceKey().value());
        labels.put(LABEL_PREFIX + "task-execution-id", workspace.taskExecutionId().toString());
        labels.put(LABEL_PREFIX + "workspace-fingerprint", workspace.fingerprint().value());
        labels.put(LABEL_PREFIX + "physical-fingerprint", worktree.physicalFingerprint().value());
        labels.put(LABEL_PREFIX + "policy-hash", policy.policyHash().toString());
        labels.put(LABEL_PREFIX + "profile-hash", buildProfile.profileHash().toString());
        labels.put(LABEL_PREFIX + "image", buildProfile.sandboxImage().value());
        labels.put(LABEL_PREFIX + "runtime-id", ownership.runtimeId().toString());
        labels.put(LABEL_PREFIX + "worker-id", ownership.workerId().toString());
        labels.put(LABEL_PREFIX + "lease-id", ownership.leaseId().toString());
        labels.put(LABEL_PREFIX + "fencing-token", Long.toString(ownership.fencingToken().value()));
        labels.put(LABEL_PREFIX + "fingerprint", fingerprint.value());
        return new TaskExecutionSandboxDescriptor(
                workspace,
                worktree,
                policy,
                buildProfile,
                canonicalWorktree,
                workspaceRoot,
                repositoryMount,
                containerUser,
                fingerprint,
                sessionId,
                containerName,
                labels);
    }

    static String containerName(ExecutionWorkspace workspace) {
        return "agentscope-sandbox-" + sessionId(Objects.requireNonNull(workspace, "workspace"));
    }

    static boolean ownsIdentity(
            ExecutionWorkspace workspace, DockerContainerSnapshot container) {
        Map<String, String> actual = Objects.requireNonNull(container, "container").labels();
        return "true".equals(actual.get(LABEL_PREFIX + "managed"))
                && workspace.workspaceKey().value().equals(
                        actual.get(LABEL_PREFIX + "workspace-key"))
                && workspace.taskExecutionId().toString().equals(
                        actual.get(LABEL_PREFIX + "task-execution-id"));
    }

    private static String sessionId(ExecutionWorkspace workspace) {
        return "crewscope-" + sha256(
                "task-execution-sandbox-name-v1|" + workspace.workspaceKey().value())
                .substring(0, 32);
    }

    SandboxResourceBudget budget() {
        return policy.sandboxBudget();
    }

    String repositoryContainerPath() {
        return workspaceRoot + "/" + repositoryMount;
    }

    boolean owns(DockerContainerSnapshot container) {
        Map<String, String> actual = container.labels();
        return ownsIdentity(workspace, container);
    }

    boolean exactlyMatches(DockerContainerSnapshot container) {
        SandboxResourceBudget budget = budget();
        return containerName.equals(container.name())
                && buildProfile.sandboxImage().value().equals(container.configuredImage())
                && containerUser.equals(container.configuredUser())
                && labels.entrySet().stream()
                        .allMatch(entry -> entry.getValue().equals(
                                container.labels().get(entry.getKey())))
                && "none".equals(container.networkMode())
                && container.readOnlyRootFilesystem()
                && container.memoryBytes() == Math.multiplyExact((long) budget.memoryMiB(), 1024 * 1024)
                && container.nanoCpus() == Math.multiplyExact((long) budget.cpuCount(), 1_000_000_000L)
                && container.pidsLimit() == budget.pids()
                && container.dropsAllCapabilities()
                && container.preventsPrivilegeEscalation()
                && container.hasReadWriteBindMount(canonicalWorktree, repositoryContainerPath())
                && container.environment("HOME").filter("/tmp/crewscope-home"::equals).isPresent()
                && container.environment("MAVEN_CONFIG")
                        .filter("/tmp/crewscope-home/.m2"::equals)
                        .isPresent()
                && container.environment("TMPDIR").filter("/tmp"::equals).isPresent()
                && container.environment("CI").filter("true"::equals).isPresent()
                && container.environment("LANG").filter("C.UTF-8"::equals).isPresent();
    }

    private static TaskExecutionSandboxFingerprint fingerprint(
            ExecutionWorkspace workspace,
            ManagedWorktree worktree,
            WorkspacePolicy policy,
            BuildProfile buildProfile,
            Path canonicalWorktree,
            String workspaceRoot,
            String repositoryMount,
            String containerUser) {
        StringBuilder canonical = new StringBuilder("task-execution-sandbox-v1");
        append(canonical, workspace.id().toString());
        append(canonical, workspace.taskExecutionId().toString());
        append(canonical, Integer.toString(workspace.attempt()));
        append(canonical, workspace.workspaceKey().value());
        append(canonical, workspace.fingerprint().value());
        append(canonical, worktree.physicalFingerprint().value());
        append(canonical, policy.policyHash().toString());
        append(canonical, buildProfile.profileHash().toString());
        append(canonical, buildProfile.sandboxImage().value());
        append(canonical, canonicalWorktree.toString());
        append(canonical, workspaceRoot);
        append(canonical, repositoryMount);
        append(canonical, containerUser);
        ExecutionWorkspaceOwnership ownership = workspace.ownership();
        append(canonical, ownership.environment().value());
        append(canonical, ownership.runtimeId().toString());
        append(canonical, ownership.workerId().toString());
        append(canonical, ownership.leaseId().toString());
        append(canonical, Long.toString(ownership.fencingToken().value()));
        SandboxResourceBudget budget = policy.sandboxBudget();
        append(canonical, budget.networkMode().name());
        append(canonical, Integer.toString(budget.cpuCount()));
        append(canonical, Integer.toString(budget.memoryMiB()));
        append(canonical, Integer.toString(budget.pids()));
        append(canonical, Integer.toString(budget.maxCommandDurationSeconds()));
        append(canonical, Long.toString(budget.maxCommandOutputBytes()));
        append(canonical, Boolean.toString(budget.readOnlyRootFilesystem()));
        return new TaskExecutionSandboxFingerprint(sha256(canonical.toString()));
    }

    private static void append(StringBuilder target, String value) {
        target.append('|').append(value.length()).append(':').append(value);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", impossible);
        }
    }
}

package io.crewscope.infrastructure.workspace.repository;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.sandbox.SandboxException;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.crewscope.domain.coding.ExecutionWorkspace;
import io.crewscope.domain.coding.ExecutionWorkspaceId;
import io.crewscope.domain.coding.ExecutionWorkspaceOwnership;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.ExecutionLease;
import io.crewscope.domain.task.TaskExecutionId;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * CrewScope-owned TaskExecution Sandbox backed by an AgentScope Docker Sandbox.
 *
 * <p>The underlying container identity and host bind mount remain private. AgentScope receives a
 * sanitized external Sandbox proxy only during a non-overlapping live Lease/Fencing call window.
 */
public final class ManagedTaskExecutionSandbox {

    private final TaskExecutionSandboxDescriptor descriptor;
    private final Sandbox delegate;
    private final DockerSandboxControl dockerControl;
    private final Clock clock;
    private final AtomicBoolean callActive = new AtomicBoolean(false);
    private final AtomicReference<ExecutionLease> activeLease = new AtomicReference<>();
    private final Sandbox guardedSandbox;
    private volatile boolean destroyed;

    ManagedTaskExecutionSandbox(
            TaskExecutionSandboxDescriptor descriptor,
            Sandbox delegate,
            DockerSandboxControl dockerControl,
            Clock clock) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.dockerControl = Objects.requireNonNull(dockerControl, "dockerControl");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.guardedSandbox = new GuardedSandbox();
    }

    /** Opens the exclusive AgentScope invocation window after rechecking current ownership. */
    public TaskExecutionSandboxCall openCall(
            ExecutionWorkspace workspace,
            ExecutionLease lease,
            UtcTimestamp authoritativeNow) {
        requireSandboxIdentity(workspace);
        TaskExecutionSandboxFacts.requireOwnership(workspace, lease, authoritativeNow);
        if (destroyed) {
            throw TaskExecutionSandboxFacts.failure(
                    TaskExecutionSandboxError.CONTAINER_CORRUPT,
                    "Destroyed Sandbox cannot accept an AgentScope call");
        }
        if (!callActive.compareAndSet(false, true)) {
            throw TaskExecutionSandboxFacts.failure(
                    TaskExecutionSandboxError.SANDBOX_BUSY,
                    "TaskExecution Sandbox already has an active call");
        }
        activeLease.set(lease);
        SandboxContext context = SandboxContext.builder().externalSandbox(guardedSandbox).build();
        return new TaskExecutionSandboxCall(this, context, workspace, lease);
    }

    /** Revalidates one still-open call before a host-side or AgentScope-backed read operation. */
    void requireCurrentCall(ExecutionWorkspace workspace, ExecutionLease lease) {
        requireSandboxIdentity(workspace);
        ExecutionLease active = activeLease.get();
        if (!callActive.get() || active == null || active != lease) {
            throw TaskExecutionSandboxFacts.failure(
                    TaskExecutionSandboxError.OWNERSHIP_MISMATCH,
                    "Repository inspection is outside its guarded AgentScope call window");
        }
        TaskExecutionSandboxFacts.requireOwnership(
                workspace, lease, UtcTimestamp.from(clock.instant()));
    }

    void closeCall() {
        activeLease.set(null);
        callActive.set(false);
    }

    /**
     * Stops and restarts the exact managed container after a timed-out command.
     *
     * <p>AgentScope 2.0.0 terminates the host-side {@code docker exec} process on timeout but does
     * not prove that descendants inside the container have exited. The call is exclusive, so a
     * container restart is the smallest reliable process-tree boundary available here.
     */
    void resetAfterCommandTimeout(ExecutionWorkspace workspace, ExecutionLease lease) {
        requireCurrentCall(workspace, lease);
        try {
            dockerControl.stop(descriptor.containerName(), Duration.ofSeconds(1));
            delegate.start();
        } catch (Exception failure) {
            destroyed = true;
            throw TaskExecutionSandboxFacts.failure(
                    TaskExecutionSandboxError.CONTAINER_CORRUPT,
                    "Timed-out command process tree could not be reset safely");
        }
    }

    void requireSandboxIdentity(ExecutionWorkspace workspace) {
        ExecutionWorkspace current = Objects.requireNonNull(workspace, "workspace");
        ExecutionWorkspace expected = descriptor.workspace();
        if (!expected.id().equals(current.id())
                || !expected.taskExecutionId().equals(current.taskExecutionId())
                || expected.attempt() != current.attempt()
                || !expected.workspaceKey().equals(current.workspaceKey())
                || !expected.fingerprint().equals(current.fingerprint())
                || !expected.ownership().equals(current.ownership())) {
            throw TaskExecutionSandboxFacts.failure(
                    TaskExecutionSandboxError.OWNERSHIP_MISMATCH,
                    "Sandbox does not belong to the Workspace current fencing epoch");
        }
    }

    void markDestroyed() {
        destroyed = true;
        activeLease.set(null);
    }

    String containerName() {
        return descriptor.containerName();
    }

    TaskExecutionSandboxDescriptor descriptor() {
        return descriptor;
    }

    DockerSandboxControl dockerControl() {
        return dockerControl;
    }

    Sandbox delegate() {
        return delegate;
    }

    public ExecutionWorkspaceId workspaceId() {
        return descriptor.workspace().id();
    }

    public TaskExecutionId taskExecutionId() {
        return descriptor.workspace().taskExecutionId();
    }

    public TaskExecutionSandboxFingerprint fingerprint() {
        return descriptor.fingerprint();
    }

    public ExecutionWorkspaceOwnership ownership() {
        return descriptor.workspace().ownership();
    }

    public boolean isRunning() {
        return !destroyed
                && dockerControl.inspect(descriptor.containerName())
                        .map(DockerContainerSnapshot::running)
                        .orElse(false);
    }

    @Override
    public String toString() {
        return "ManagedTaskExecutionSandbox[workspaceId=" + workspaceId()
                + ", taskExecutionId=" + taskExecutionId()
                + ", fingerprint=" + fingerprint().value() + "]";
    }

    private ExecutionLease requireLiveCall() {
        ExecutionLease lease = activeLease.get();
        if (lease == null || !callActive.get()) {
            throw TaskExecutionSandboxFacts.failure(
                    TaskExecutionSandboxError.OWNERSHIP_MISMATCH,
                    "Sandbox operation is outside its guarded AgentScope call window");
        }
        TaskExecutionSandboxFacts.requireOwnership(
                descriptor.workspace(), lease, UtcTimestamp.from(clock.instant()));
        return lease;
    }

    private final class GuardedSandbox implements Sandbox {

        private final SandboxState safeState = safeState();

        @Override
        public void start() throws Exception {
            requireLiveCall();
            delegate.start();
        }

        @Override
        public void stop() throws Exception {
            requireLiveCall();
            delegate.stop();
        }

        @Override
        public void shutdown() {
            // AgentScope sees this as an external Sandbox; CrewScope owns destruction explicitly.
        }

        @Override
        public void close() {
            // Closing a call must never destroy the TaskExecution-level container.
        }

        @Override
        public boolean isRunning() {
            requireLiveCall();
            return delegate.isRunning();
        }

        @Override
        public SandboxState getState() {
            return safeState;
        }

        @Override
        public ExecResult exec(
                RuntimeContext runtimeContext, String command, Integer timeoutSeconds)
                throws Exception {
            requireLiveCall();
            int maximumSeconds = descriptor.budget().maxCommandDurationSeconds();
            int effectiveTimeout = timeoutSeconds == null ? maximumSeconds : timeoutSeconds;
            if (effectiveTimeout < 1 || effectiveTimeout > maximumSeconds) {
                throw TaskExecutionSandboxFacts.failure(
                        TaskExecutionSandboxError.POLICY_MISMATCH,
                        "Sandbox command timeout exceeds the Workspace Policy");
            }
            try {
                return bounded(delegate.exec(runtimeContext, command, effectiveTimeout));
            } catch (SandboxException.ExecException failure) {
                if (exceedsOutputBudget(failure.getStdout())
                        || exceedsOutputBudget(failure.getStderr())) {
                    throw new TaskExecutionSandboxOutputLimitException(
                            truncate(failure.getStdout()), truncate(failure.getStderr()));
                }
                throw new SandboxException.ExecException(
                        failure.getExitCode(),
                        truncate(failure.getStdout()),
                        truncate(failure.getStderr()));
            }
        }

        @Override
        public InputStream persistWorkspace() throws Exception {
            requireLiveCall();
            return delegate.persistWorkspace();
        }

        @Override
        public void hydrateWorkspace(InputStream archive) throws Exception {
            requireLiveCall();
            delegate.hydrateWorkspace(archive);
        }

        private ExecResult bounded(ExecResult result) {
            String stdout = truncate(result.stdout());
            String stderr = truncate(result.stderr());
            boolean truncated = result.truncated()
                    || !Objects.equals(stdout, result.stdout())
                    || !Objects.equals(stderr, result.stderr());
            return new ExecResult(result.exitCode(), stdout, stderr, truncated);
        }

        private String truncate(String value) {
            if (value == null) {
                return null;
            }
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            long maximum = descriptor.budget().maxCommandOutputBytes();
            if (bytes.length <= maximum) {
                return value;
            }
            int accepted = (int) Math.min(maximum, Integer.MAX_VALUE);
            while (accepted > 0
                    && accepted < bytes.length
                    && (bytes[accepted] & 0xC0) == 0x80) {
                accepted--;
            }
            return new String(bytes, 0, accepted, StandardCharsets.UTF_8);
        }

        private boolean exceedsOutputBudget(String value) {
            return value != null
                    && value.getBytes(StandardCharsets.UTF_8).length
                            > descriptor.budget().maxCommandOutputBytes();
        }

        private SandboxState safeState() {
            SandboxState state = new PublicSandboxState();
            state.setSessionId("crewscope-" + descriptor.fingerprint().value().substring(0, 16));
            return state;
        }
    }

    private static final class PublicSandboxState extends SandboxState {}
}

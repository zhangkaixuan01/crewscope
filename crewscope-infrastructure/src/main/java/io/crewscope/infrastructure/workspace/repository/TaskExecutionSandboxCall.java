package io.crewscope.infrastructure.workspace.repository;

import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.crewscope.domain.coding.ExecutionWorkspace;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** One guarded AgentScope call window for a TaskExecution-owned external Sandbox. */
public final class TaskExecutionSandboxCall implements AutoCloseable {

    private final ManagedTaskExecutionSandbox owner;
    private final SandboxContext sandboxContext;
    private final ExecutionWorkspace workspace;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    TaskExecutionSandboxCall(
            ManagedTaskExecutionSandbox owner,
            SandboxContext sandboxContext,
            ExecutionWorkspace workspace) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.sandboxContext = Objects.requireNonNull(sandboxContext, "sandboxContext");
        this.workspace = Objects.requireNonNull(workspace, "workspace");
    }

    /** Context injected into the AgentScope RuntimeContext for this guarded call only. */
    public SandboxContext sandboxContext() {
        requireCurrent();
        return sandboxContext;
    }

    /** Rechecks call liveness, Workspace identity and current Lease/Fencing ownership. */
    void requireCurrent() {
        if (closed.get()) {
            throw new IllegalStateException("TaskExecution Sandbox call is already closed");
        }
        owner.requireCurrentCall(workspace);
    }

    /** Resets the exclusive container so every descendant of a timed-out command is terminated. */
    void resetAfterCommandTimeout() {
        requireCurrent();
        owner.resetAfterCommandTimeout(workspace);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            owner.closeCall();
        }
    }
}

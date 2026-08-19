package io.crewscope.infrastructure.workspace.repository;

import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** One exclusive, short-lived registration scope for the repository inspection tools. */
public final class RepositoryInspectionSession implements AutoCloseable {

    private final TaskExecutionSandboxCall call;
    private final SandboxBackedFilesystem filesystem;
    private final RepositoryInspectionTool tool;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    RepositoryInspectionSession(
            TaskExecutionSandboxCall call,
            SandboxBackedFilesystem filesystem,
            RepositoryInspectionTool tool) {
        this.call = Objects.requireNonNull(call, "call");
        this.filesystem = Objects.requireNonNull(filesystem, "filesystem");
        this.tool = Objects.requireNonNull(tool, "tool");
    }

    /** Context merged into the corresponding AgentScope invocation only. */
    public SandboxContext sandboxContext() {
        requireOpen();
        return call.sandboxContext();
    }

    public RepositoryInspectionTool tool() {
        requireOpen();
        return tool;
    }

    /** Registers exactly the CrewScope read-only facade, never AgentScope write or shell tools. */
    public void registerInto(Toolkit toolkit) {
        requireOpen();
        Objects.requireNonNull(toolkit, "toolkit").registerTool(tool);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            filesystem.setSandbox(null);
            call.close();
        }
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Repository inspection session is already closed");
        }
    }
}

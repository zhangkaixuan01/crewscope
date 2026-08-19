package io.crewscope.infrastructure.workspace.repository;

import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** One exclusive registration scope containing only the controlled Coding mutation facade. */
public final class CodingFilesystemSession implements AutoCloseable {

    private final TaskExecutionSandboxCall call;
    private final SandboxBackedFilesystem filesystem;
    private final CodingFilesystemTool tool;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    CodingFilesystemSession(
            TaskExecutionSandboxCall call,
            SandboxBackedFilesystem filesystem,
            CodingFilesystemTool tool) {
        this.call = Objects.requireNonNull(call, "call");
        this.filesystem = Objects.requireNonNull(filesystem, "filesystem");
        this.tool = Objects.requireNonNull(tool, "tool");
    }

    /** Context merged into the corresponding AgentScope invocation only. */
    public SandboxContext sandboxContext() {
        requireOpen();
        return call.sandboxContext();
    }

    public CodingFilesystemTool tool() {
        requireOpen();
        return tool;
    }

    /** Registers five explicit mutation tools and never the native raw FilesystemTool. */
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
            throw new IllegalStateException("Coding filesystem session is already closed");
        }
    }
}

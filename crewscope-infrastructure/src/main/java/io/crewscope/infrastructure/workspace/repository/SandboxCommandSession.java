package io.crewscope.infrastructure.workspace.repository;

import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** One exclusive registration scope containing only the structured command facade. */
public final class SandboxCommandSession implements AutoCloseable {

    private final TaskExecutionSandboxCall call;
    private final SandboxCommandTool tool;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    SandboxCommandSession(TaskExecutionSandboxCall call, SandboxCommandTool tool) {
        this.call = Objects.requireNonNull(call, "call");
        this.tool = Objects.requireNonNull(tool, "tool");
    }

    public SandboxContext sandboxContext() {
        requireOpen();
        return call.sandboxContext();
    }

    public SandboxCommandTool tool() {
        requireOpen();
        return tool;
    }

    /** Registers one structured Tool and never AgentScope's native raw ShellExecuteTool. */
    public void registerInto(Toolkit toolkit) {
        requireOpen();
        Objects.requireNonNull(toolkit, "toolkit").registerTool(tool);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            call.close();
        }
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Sandbox command session is already closed");
        }
    }
}

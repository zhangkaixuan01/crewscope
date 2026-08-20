package io.crewscope.infrastructure.workspace.repository;

import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** One exclusive Sandbox call containing the complete frozen Coding Specialist Tool surface. */
public final class CodingSpecialistToolSession implements AutoCloseable {

    private final TaskExecutionSandboxCall call;
    private final SandboxBackedFilesystem filesystem;
    private final Toolkit toolkit;
    private final AtomicBoolean closed = new AtomicBoolean();

    CodingSpecialistToolSession(
            TaskExecutionSandboxCall call,
            SandboxBackedFilesystem filesystem,
            Toolkit toolkit) {
        this.call = Objects.requireNonNull(call, "call");
        this.filesystem = Objects.requireNonNull(filesystem, "filesystem");
        this.toolkit = Objects.requireNonNull(toolkit, "toolkit");
    }

    public Toolkit toolkit() {
        if (closed.get()) {
            throw new IllegalStateException("Coding Specialist Tool session is closed");
        }
        return toolkit;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            filesystem.setSandbox(null);
            call.close();
        }
    }
}

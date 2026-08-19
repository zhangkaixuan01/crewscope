package io.crewscope.infrastructure.workspace.repository;

import java.io.IOException;
import java.nio.channels.FileLock;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/** One non-blocking JVM and operating-system lock for a stable Workspace path. */
public final class WorkspacePathLock implements AutoCloseable {

    private final WorkspacePathLockManager owner;
    private final String lockKey;
    private final ReentrantLock jvmLock;
    private final FileLock fileLock;
    private final AtomicBoolean closed = new AtomicBoolean();

    WorkspacePathLock(
            WorkspacePathLockManager owner,
            String lockKey,
            ReentrantLock jvmLock,
            FileLock fileLock) {
        this.owner = owner;
        this.lockKey = lockKey;
        this.jvmLock = jvmLock;
        this.fileLock = fileLock;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        IOException releaseFailure = null;
        try {
            fileLock.release();
        } catch (IOException failure) {
            releaseFailure = failure;
        }
        try {
            fileLock.channel().close();
        } catch (IOException failure) {
            if (releaseFailure == null) {
                releaseFailure = failure;
            } else {
                releaseFailure.addSuppressed(failure);
            }
        } finally {
            jvmLock.unlock();
            owner.releaseJvmReference(lockKey);
        }
        if (releaseFailure != null) {
            throw new WorktreeOperationException(
                    WorktreeOperationError.CLEANUP_FAILED,
                    "Workspace path lock could not be released");
        }
    }
}

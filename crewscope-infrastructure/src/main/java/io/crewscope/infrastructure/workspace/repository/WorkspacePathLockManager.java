package io.crewscope.infrastructure.workspace.repository;

import io.crewscope.domain.coding.ManagedWorktreeLocator;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** Coordinates all lifecycle operations with a shared non-blocking path lock protocol. */
public final class WorkspacePathLockManager {

    private static final LinkOption[] NO_FOLLOW_LINKS = {LinkOption.NOFOLLOW_LINKS};
    private static final OpenOption[] LOCK_OPTIONS = {
        StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS
    };
    private static final ConcurrentHashMap<String, LockEntry> JVM_LOCKS =
            new ConcurrentHashMap<>();

    private final Path canonicalLockRoot;
    private final String requiredOwner;

    public WorkspacePathLockManager(Path lockRoot) {
        this(lockRoot, System.getProperty("user.name"));
    }

    public WorkspacePathLockManager(Path lockRoot, String requiredOwner) {
        this.canonicalLockRoot = canonicalRoot(lockRoot);
        this.requiredOwner = requireOwner(requiredOwner);
        requireOwner(this.canonicalLockRoot, this.requiredOwner);
    }

    /** Attempts the JVM lock and OS FileLock without waiting. */
    public WorkspacePathLock tryAcquire(ManagedWorktreeLocator locator) {
        ManagedWorktreeLocator required = Objects.requireNonNull(locator, "locator");
        String fileKey = sha256(required.relativeValue());
        Path lockFile = canonicalLockRoot.resolve(fileKey + ".lock");
        String jvmKey = lockFile.toString();
        LockEntry entry = JVM_LOCKS.compute(jvmKey, (ignored, existing) -> {
            LockEntry current = existing == null ? new LockEntry() : existing;
            current.references++;
            return current;
        });
        if (!entry.lock.tryLock()) {
            releaseJvmReference(jvmKey);
            throw busy();
        }

        FileChannel channel = null;
        boolean released = false;
        try {
            if (Files.isSymbolicLink(lockFile)) {
                throw new WorktreeOperationException(
                        WorktreeOperationError.PATH_SYMLINK_ESCAPE,
                        "Workspace lock path must not be a symbolic link");
            }
            channel = FileChannel.open(lockFile, LOCK_OPTIONS);
            requireOwner(lockFile, requiredOwner);
            FileLock fileLock;
            try {
                fileLock = channel.tryLock();
            } catch (OverlappingFileLockException overlapping) {
                fileLock = null;
            }
            if (fileLock == null) {
                closeQuietly(channel);
                entry.lock.unlock();
                releaseJvmReference(jvmKey);
                released = true;
                throw busy();
            }
            return new WorkspacePathLock(this, jvmKey, entry.lock, fileLock);
        } catch (IOException failure) {
            closeQuietly(channel);
            entry.lock.unlock();
            releaseJvmReference(jvmKey);
            throw new WorktreeOperationException(
                    WorktreeOperationError.COMMAND_FAILED,
                    "Workspace path lock could not be opened");
        } catch (RuntimeException failure) {
            closeQuietly(channel);
            if (!released && entry.lock.isHeldByCurrentThread()) {
                entry.lock.unlock();
                releaseJvmReference(jvmKey);
            }
            throw failure;
        }
    }

    void releaseJvmReference(String key) {
        JVM_LOCKS.computeIfPresent(key, (ignored, entry) -> {
            entry.references--;
            return entry.references == 0 ? null : entry;
        });
    }

    private static Path canonicalRoot(Path configuredRoot) {
        Path root = Objects.requireNonNull(configuredRoot, "lockRoot")
                .toAbsolutePath()
                .normalize();
        try {
            if (Files.isSymbolicLink(root)) {
                throw invalidRoot();
            }
            Path canonical = root.toRealPath();
            if (!Files.isDirectory(canonical, NO_FOLLOW_LINKS)) {
                throw invalidRoot();
            }
            return canonical;
        } catch (IOException failure) {
            throw new WorktreeOperationException(
                    WorktreeOperationError.MANAGED_ROOT_INVALID,
                    "Workspace lock root could not be resolved");
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 must be available", impossible);
        }
    }

    private static String requireOwner(String owner) {
        String value = Objects.requireNonNull(owner, "requiredOwner").trim();
        if (value.isEmpty() || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Workspace lock owner must be non-blank");
        }
        return value;
    }

    private static void requireOwner(Path path, String requiredOwner) {
        try {
            if (!requiredOwner.equals(Files.getOwner(path, NO_FOLLOW_LINKS).getName())) {
                throw invalidRoot();
            }
        } catch (IOException failure) {
            throw invalidRoot();
        }
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException ignored) {
            // The operation is already failing and no lock was acquired.
        }
    }

    private static WorktreeOperationException busy() {
        return new WorktreeOperationException(
                WorktreeOperationError.WORKSPACE_BUSY,
                "Workspace lifecycle operation is already in progress");
    }

    private static WorktreeOperationException invalidRoot() {
        return new WorktreeOperationException(
                WorktreeOperationError.MANAGED_ROOT_INVALID,
                "Workspace lock root must be a canonical directory");
    }

    private static final class LockEntry {
        private final ReentrantLock lock = new ReentrantLock();
        private int references;
    }
}

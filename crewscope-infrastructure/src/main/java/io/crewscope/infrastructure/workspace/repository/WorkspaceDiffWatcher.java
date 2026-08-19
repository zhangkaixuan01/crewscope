package io.crewscope.infrastructure.workspace.repository;

import io.crewscope.domain.coding.AllowedPathSet;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** Recursive WatchService adapter whose events only schedule Git-authoritative reconciliation. */
public final class WorkspaceDiffWatcher implements AutoCloseable {

    private static final LinkOption[] NO_FOLLOW = {LinkOption.NOFOLLOW_LINKS};

    private final WorkspaceDiffStreamKey streamKey;
    private final Path worktree;
    private final AllowedPathSet allowedPaths;
    private final WatchService watchService;
    private final Consumer<WorkspaceDiffHint> hints;
    private final Clock clock;
    private final long debounceMillis;
    private final ScheduledExecutorService scheduler;
    private final Map<WatchKey, Path> directories = new HashMap<>();
    private final AtomicBoolean reconcilePending = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<WorkspaceDiffException> lastFailure = new AtomicReference<>();
    private final Thread watchThread;
    private final ScheduledFuture<?> periodic;

    private WorkspaceDiffWatcher(
            WorkspaceDiffStreamKey streamKey,
            Path worktree,
            AllowedPathSet allowedPaths,
            WatchService watchService,
            WorkspaceDiffProperties properties,
            Clock clock,
            Consumer<WorkspaceDiffHint> hints) throws IOException {
        this.streamKey = Objects.requireNonNull(streamKey, "streamKey");
        this.worktree = Objects.requireNonNull(worktree, "worktree").toRealPath();
        this.allowedPaths = Objects.requireNonNull(allowedPaths, "allowedPaths");
        this.watchService = Objects.requireNonNull(watchService, "watchService");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.hints = Objects.requireNonNull(hints, "hints");
        this.debounceMillis = properties.getDebounce().toMillis();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "crewscope-diff-reconcile");
            thread.setDaemon(true);
            return thread;
        });
        registerInitialDirectories();
        this.watchThread = new Thread(this::watchLoop, "crewscope-diff-watcher");
        this.watchThread.setDaemon(true);
        this.watchThread.start();
        this.periodic = scheduler.scheduleWithFixedDelay(
                () -> emit(WorkspaceDiffHintKind.FULL_RECONCILE),
                properties.getReconcileInterval().toMillis(),
                properties.getReconcileInterval().toMillis(),
                TimeUnit.MILLISECONDS);
        emit(WorkspaceDiffHintKind.FULL_RECONCILE);
    }

    static WorkspaceDiffWatcher start(
            WorkspaceDiffStreamKey streamKey,
            Path worktree,
            AllowedPathSet allowedPaths,
            WorkspaceDiffProperties properties,
            Clock clock,
            Consumer<WorkspaceDiffHint> hints) throws IOException {
        return new WorkspaceDiffWatcher(
                streamKey,
                worktree,
                allowedPaths,
                FileSystems.getDefault().newWatchService(),
                properties,
                clock,
                hints);
    }

    public Optional<WorkspaceDiffException> lastFailure() {
        return Optional.ofNullable(lastFailure.get());
    }

    private void registerInitialDirectories() throws IOException {
        register(worktree);
        for (String root : allowedPaths.values()) {
            if (".".equals(root)) {
                registerTree(worktree);
                return;
            }
            Path candidate = worktree.resolve(root).normalize();
            registerExistingAncestors(candidate);
            if (Files.isDirectory(candidate, NO_FOLLOW) && !Files.isSymbolicLink(candidate)) {
                registerTree(candidate);
            }
        }
    }

    private void registerExistingAncestors(Path candidate) throws IOException {
        Path current = candidate.getParent();
        while (current != null && current.startsWith(worktree)) {
            if (Files.isDirectory(current, NO_FOLLOW) && !Files.isSymbolicLink(current)) {
                register(current);
            }
            if (current.equals(worktree)) {
                break;
            }
            current = current.getParent();
        }
    }

    private void registerTree(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(candidate ->
                            Files.isDirectory(candidate, NO_FOLLOW)
                                    && !Files.isSymbolicLink(candidate)
                                    && !isGitMetadata(candidate))
                    .toList()) {
                register(path);
            }
        }
    }

    private void register(Path directory) throws IOException {
        if (directories.containsValue(directory) || isGitMetadata(directory)) {
            return;
        }
        WatchKey key = directory.register(
                watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE);
        directories.put(key, directory);
    }

    private void watchLoop() {
        try {
            while (!closed.get()) {
                WatchKey key = watchService.take();
                Path directory = directories.get(key);
                boolean fullReconcile = directory == null;
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                        fullReconcile = true;
                        continue;
                    }
                    Path changed = directory.resolve((Path) event.context()).normalize();
                    if (!changed.startsWith(worktree) || isGitMetadata(changed)) {
                        continue;
                    }
                    Path relative = worktree.relativize(changed);
                    if (allowedPaths.allows(relative.toString().replace('\\', '/'))) {
                        scheduleChanged();
                    }
                    if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE
                            && Files.isDirectory(changed, NO_FOLLOW)
                            && !Files.isSymbolicLink(changed)
                            && shouldWatchDirectory(relative)) {
                        registerTree(changed);
                    }
                }
                if (!key.reset()) {
                    directories.remove(key);
                    fullReconcile = true;
                }
                if (fullReconcile) {
                    emit(WorkspaceDiffHintKind.FULL_RECONCILE);
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException failure) {
            if (!closed.get()) {
                WorkspaceDiffException watcherFailure = new WorkspaceDiffException(
                        WorkspaceDiffError.WATCHER_FAILED,
                        "Workspace Diff Watcher stopped unexpectedly");
                lastFailure.compareAndSet(null, watcherFailure);
                emit(WorkspaceDiffHintKind.FULL_RECONCILE);
            }
        }
    }

    private void scheduleChanged() {
        if (reconcilePending.compareAndSet(false, true)) {
            scheduler.schedule(() -> {
                reconcilePending.set(false);
                emit(WorkspaceDiffHintKind.CHANGED);
            }, debounceMillis, TimeUnit.MILLISECONDS);
        }
    }

    private void emit(WorkspaceDiffHintKind kind) {
        if (closed.get()) {
            return;
        }
        try {
            hints.accept(new WorkspaceDiffHint(
                    streamKey, kind, UtcTimestamp.from(clock.instant())));
        } catch (RuntimeException callbackFailure) {
            lastFailure.compareAndSet(
                    null,
                    new WorkspaceDiffException(
                            WorkspaceDiffError.WATCHER_FAILED,
                            "Workspace Diff hint consumer failed"));
        }
    }

    private boolean isGitMetadata(Path path) {
        Path relative = worktree.relativize(path.normalize());
        return relative.getNameCount() > 0 && ".git".equals(relative.getName(0).toString());
    }

    private boolean shouldWatchDirectory(Path relative) {
        String candidate = relative.toString().replace('\\', '/');
        return allowedPaths.values().stream().anyMatch(root ->
                ".".equals(root)
                        || root.equals(candidate)
                        || root.startsWith(candidate + "/")
                        || candidate.startsWith(root + "/"));
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        periodic.cancel(false);
        scheduler.shutdownNow();
        try {
            watchService.close();
        } catch (IOException ignored) {
            // The WatchService is process-local and already detached from durable Workspace facts.
        }
        watchThread.interrupt();
    }
}

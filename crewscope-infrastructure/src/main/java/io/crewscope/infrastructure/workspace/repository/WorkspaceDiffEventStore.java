package io.crewscope.infrastructure.workspace.repository;

import io.crewscope.domain.coding.DiffFileEntry;
import io.crewscope.domain.coding.DiffManifest;
import io.crewscope.domain.coding.DiffPath;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/** Thread-safe bounded event store; a Worker rebuild rotates epoch and serves a RESET snapshot. */
public final class WorkspaceDiffEventStore {

    private final Map<io.crewscope.domain.coding.ExecutionWorkspaceId, Stream> streams =
            new ConcurrentHashMap<>();
    private final WorkspaceDiffCursorCodec cursors;
    private final Clock clock;
    private final int retainedEvents;
    private final int maximumReplayEvents;
    private final int maximumEventBytes;

    WorkspaceDiffEventStore(
            WorkspaceDiffProperties properties,
            WorkspaceDiffCursorCodec cursors,
            Clock clock) {
        WorkspaceDiffProperties configured = Objects.requireNonNull(properties, "properties");
        configured.validate();
        this.cursors = Objects.requireNonNull(cursors, "cursors");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.retainedEvents = configured.getRetainedEvents();
        this.maximumReplayEvents = configured.getMaximumReplayEvents();
        this.maximumEventBytes = configured.getMaximumEventBytes();
    }

    /** Starts a new epoch and publishes a complete RESET, including after Watcher recovery. */
    public WorkspaceDiffEvent restart(
            WorkspaceDiffStreamKey key, DiffManifest manifest) {
        WorkspaceDiffStreamKey requiredKey = Objects.requireNonNull(key, "key");
        DiffManifest current = Objects.requireNonNull(manifest, "manifest");
        AtomicReference<WorkspaceDiffEvent> published = new AtomicReference<>();
        streams.compute(requiredKey.workspaceId(), (ignored, existing) -> {
            Stream replacement = new Stream(requiredKey, UUID.randomUUID(), current);
            published.set(replacement.append(
                    WorkspaceDiffEventKind.RESET, current.files(), List.of(), current));
            return replacement;
        });
        return published.get();
    }

    /** Publishes a full RESET in the current epoch, creating an epoch when absent. */
    public WorkspaceDiffEvent reset(WorkspaceDiffStreamKey key, DiffManifest manifest) {
        WorkspaceDiffStreamKey requiredKey = Objects.requireNonNull(key, "key");
        DiffManifest current = Objects.requireNonNull(manifest, "manifest");
        while (true) {
            Stream stream = streams.get(requiredKey.workspaceId());
            if (stream == null || !stream.key.equals(requiredKey)) {
                return restart(requiredKey, current);
            }
            synchronized (stream) {
                if (streams.get(requiredKey.workspaceId()) != stream) {
                    continue;
                }
                stream.requireKey(requiredKey);
                WorkspaceDiffEvent event = stream.append(
                        WorkspaceDiffEventKind.RESET,
                        current.files(),
                        List.of(),
                        current);
                stream.manifest = current;
                return event;
            }
        }
    }

    /** Emits a DELTA only when the Git-authoritative Manifest Content Hash changes. */
    public Optional<WorkspaceDiffEvent> reconcile(
            WorkspaceDiffStreamKey key, DiffManifest manifest) {
        WorkspaceDiffStreamKey requiredKey = Objects.requireNonNull(key, "key");
        DiffManifest next = Objects.requireNonNull(manifest, "manifest");
        while (true) {
            Stream stream = streams.get(requiredKey.workspaceId());
            if (stream == null || !stream.key.equals(requiredKey)) {
                return Optional.of(restart(requiredKey, next));
            }
            synchronized (stream) {
                if (streams.get(requiredKey.workspaceId()) != stream) {
                    continue;
                }
                stream.requireKey(requiredKey);
                if (stream.manifest.contentHash().equals(next.contentHash())) {
                    return Optional.empty();
                }
                if (!stream.manifest.generation().next().equals(next.generation())) {
                    throw new WorkspaceDiffException(
                            WorkspaceDiffError.INVALID_CONTEXT,
                            "Diff generation must be the direct successor of current authority");
                }
                Map<DiffPath, DiffFileEntry> before = byPath(stream.manifest.files());
                Map<DiffPath, DiffFileEntry> after = byPath(next.files());
                List<DiffFileEntry> upserts = after.values().stream()
                        .filter(entry -> !entry.equals(before.get(entry.path())))
                        .sorted(Comparator.comparing(DiffFileEntry::path))
                        .toList();
                List<DiffPath> removals = before.keySet().stream()
                        .filter(path -> !after.containsKey(path))
                        .sorted()
                        .toList();
                WorkspaceDiffEvent event = stream.append(
                        WorkspaceDiffEventKind.DELTA, upserts, removals, next);
                stream.manifest = next;
                return Optional.of(event);
            }
        }
    }

    /** Replays events after a cursor or requests RESET when epoch/history no longer matches. */
    public WorkspaceDiffReplay replay(
            WorkspaceDiffStreamKey key, String cursor, int requestedLimit) {
        Stream stream = streams.get(Objects.requireNonNull(key, "key").workspaceId());
        if (stream == null) {
            throw new WorkspaceDiffException(
                    WorkspaceDiffError.REPLAY_UNAVAILABLE,
                    "Diff stream has not been initialized");
        }
        int limit = Math.min(requestedLimit, maximumReplayEvents);
        if (limit < 1) {
            throw new IllegalArgumentException("requestedLimit must be positive");
        }
        WorkspaceDiffCursorCodec.Cursor decoded = cursors.decode(cursor);
        synchronized (stream) {
            if (!stream.key.equals(key)
                    || !decoded.workspaceId().equals(key.workspaceId().value())
                    || !decoded.epoch().equals(stream.epoch)) {
                return WorkspaceDiffReplay.reset(stream.manifest);
            }
            if (decoded.sequence() > stream.sequence) {
                throw new WorkspaceDiffException(
                        WorkspaceDiffError.INVALID_CURSOR, "Diff cursor is ahead of the stream");
            }
            WorkspaceDiffEvent matching = stream.events.stream()
                    .filter(event -> event.sequence() == decoded.sequence())
                    .findFirst()
                    .orElse(null);
            long oldest = stream.events.getFirst().sequence();
            if (matching == null || decoded.sequence() < oldest) {
                return WorkspaceDiffReplay.reset(stream.manifest);
            }
            if (matching.generation().value() != decoded.generation()) {
                throw new WorkspaceDiffException(
                        WorkspaceDiffError.INVALID_CURSOR,
                        "Diff cursor generation does not match its sequence");
            }
            List<WorkspaceDiffEvent> available = stream.events.stream()
                    .filter(event -> event.sequence() > decoded.sequence())
                    .toList();
            List<WorkspaceDiffEvent> page = available.stream().limit(limit).toList();
            return new WorkspaceDiffReplay(
                    page, available.size() > page.size(), false, Optional.empty());
        }
    }

    public Optional<DiffManifest> latest(WorkspaceDiffStreamKey key) {
        Stream stream = streams.get(Objects.requireNonNull(key, "key").workspaceId());
        if (stream == null) {
            return Optional.empty();
        }
        synchronized (stream) {
            return stream.key.equals(key) ? Optional.of(stream.manifest) : Optional.empty();
        }
    }

    private static Map<DiffPath, DiffFileEntry> byPath(List<DiffFileEntry> files) {
        Map<DiffPath, DiffFileEntry> result = new LinkedHashMap<>();
        files.forEach(entry -> result.put(entry.path(), entry));
        return result;
    }

    private final class Stream {
        private final WorkspaceDiffStreamKey key;
        private final UUID epoch;
        private final Deque<WorkspaceDiffEvent> events = new ArrayDeque<>();
        private long sequence;
        private DiffManifest manifest;

        private Stream(
                WorkspaceDiffStreamKey key, UUID epoch, DiffManifest manifest) {
            this.key = key;
            this.epoch = epoch;
            this.manifest = manifest;
        }

        private WorkspaceDiffEvent append(
                WorkspaceDiffEventKind kind,
                List<DiffFileEntry> upserts,
                List<DiffPath> removals,
                DiffManifest authority) {
            long nextSequence = Math.addExact(sequence, 1);
            List<DiffFileEntry> boundedUpserts = bounded(upserts, removals);
            String cursor = cursors.encode(new WorkspaceDiffCursorCodec.Cursor(
                    key.workspaceId().value(),
                    epoch,
                    nextSequence,
                    authority.generation().value()));
            WorkspaceDiffEvent event = new WorkspaceDiffEvent(
                    key.scope(),
                    key.workspaceId(),
                    epoch,
                    nextSequence,
                    authority.generation(),
                    UUID.randomUUID(),
                    kind,
                    cursor,
                    boundedUpserts,
                    removals,
                    authority.contentHash(),
                    UtcTimestamp.from(clock.instant()));
            sequence = nextSequence;
            events.addLast(event);
            while (events.size() > retainedEvents) {
                events.removeFirst();
            }
            return event;
        }

        private List<DiffFileEntry> bounded(
                List<DiffFileEntry> upserts, List<DiffPath> removals) {
            List<DiffFileEntry> copied = List.copyOf(upserts);
            if (estimatedBytes(copied, removals) <= maximumEventBytes) {
                return copied;
            }
            // Preview is non-authoritative and can be omitted without changing Manifest Hash.
            List<DiffFileEntry> withoutPreview = copied.stream()
                    .map(entry -> new DiffFileEntry(
                            entry.path(),
                            entry.oldPath(),
                            entry.kind(),
                            entry.additions(),
                            entry.deletions(),
                            entry.binary(),
                            entry.patchTruncated(),
                            entry.patchSha256(),
                            Optional.empty()))
                    .toList();
            if (estimatedBytes(withoutPreview, removals) > maximumEventBytes) {
                throw new WorkspaceDiffException(
                        WorkspaceDiffError.DIFF_LIMIT_EXCEEDED,
                        "Diff event metadata exceeds its byte budget");
            }
            return withoutPreview;
        }

        private void requireKey(WorkspaceDiffStreamKey expected) {
            if (!key.equals(expected)) {
                throw new WorkspaceDiffException(
                        WorkspaceDiffError.INVALID_CONTEXT,
                        "Diff stream does not match Workspace recovery facts");
            }
        }
    }

    private static int estimatedBytes(
            List<DiffFileEntry> upserts, List<DiffPath> removals) {
        long bytes = 256;
        for (DiffFileEntry entry : upserts) {
            bytes += utf8(entry.path().value())
                    + entry.oldPath().map(path -> utf8(path.value())).orElse(0)
                    + entry.patchPreview().map(WorkspaceDiffEventStore::utf8).orElse(0)
                    + 256L;
        }
        for (DiffPath removal : removals) {
            bytes += utf8(removal.value()) + 32L;
        }
        return bytes > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) bytes;
    }

    private static int utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }
}

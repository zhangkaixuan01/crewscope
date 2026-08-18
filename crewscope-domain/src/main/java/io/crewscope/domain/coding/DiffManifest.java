package io.crewscope.domain.coding;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.task.RuntimeContentHash;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Sorted immutable Workspace Diff projection whose content Hash excludes its Generation. */
public final class DiffManifest {

    public static final int MAX_FILES = 10_000;

    private final DiffGeneration generation;
    private final List<DiffFileEntry> files;
    private final int fileCount;
    private final long additions;
    private final long deletions;
    private final RuntimeContentHash contentHash;

    private DiffManifest(
            DiffGeneration generation,
            List<DiffFileEntry> files,
            Optional<Integer> expectedFileCount,
            Optional<Long> expectedAdditions,
            Optional<Long> expectedDeletions,
            Optional<RuntimeContentHash> expectedContentHash) {
        this.generation = Objects.requireNonNull(generation, "generation");
        this.files = requireFiles(files);
        this.fileCount = this.files.size();
        this.additions = total(this.files, true);
        this.deletions = total(this.files, false);
        this.contentHash = calculateContentHash();
        requireExpected(expectedFileCount, this.fileCount, "diffManifest.fileCount");
        requireExpected(expectedAdditions, this.additions, "diffManifest.additions");
        requireExpected(expectedDeletions, this.deletions, "diffManifest.deletions");
        Objects.requireNonNull(expectedContentHash, "expectedContentHash").ifPresent(expected -> {
            if (!expected.equals(this.contentHash)) {
                throw new DomainValidationException(
                        "diffManifest.contentHash", "must match the canonical sorted file facts");
            }
        });
    }

    public static DiffManifest initial(List<DiffFileEntry> files) {
        return capture(DiffGeneration.first(), files);
    }

    public static DiffManifest capture(DiffGeneration generation, List<DiffFileEntry> files) {
        return new DiffManifest(
                generation,
                files,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    /** Returns the previous projection for a no-op Reconcile, otherwise its direct successor. */
    public static DiffManifest reconcile(
            DiffManifest previous, List<DiffFileEntry> authorityFiles) {
        DiffManifest current = Objects.requireNonNull(previous, "previous");
        DiffManifest candidate = capture(current.generation, authorityFiles);
        if (candidate.contentHash.equals(current.contentHash)) {
            return current;
        }
        return capture(current.generation.next(), candidate.files);
    }

    public static DiffManifest reconstitute(
            DiffGeneration generation,
            List<DiffFileEntry> files,
            int fileCount,
            long additions,
            long deletions,
            RuntimeContentHash contentHash) {
        return new DiffManifest(
                generation,
                files,
                Optional.of(fileCount),
                Optional.of(additions),
                Optional.of(deletions),
                Optional.of(Objects.requireNonNull(contentHash, "contentHash")));
    }

    private static List<DiffFileEntry> requireFiles(List<DiffFileEntry> values) {
        List<DiffFileEntry> supplied = List.copyOf(Objects.requireNonNull(values, "files"));
        if (supplied.size() > MAX_FILES) {
            throw new DomainValidationException(
                    "diffManifest.files", "must contain at most 10000 changed files");
        }
        Set<DiffPath> paths = new HashSet<>();
        supplied.forEach(entry -> {
            DiffFileEntry required = Objects.requireNonNull(entry, "diffFileEntry");
            if (!paths.add(required.path())) {
                throw new DomainValidationException(
                        "diffManifest.files", "current paths must be unique");
            }
        });
        return supplied.stream()
                .sorted(Comparator.comparing(DiffFileEntry::path))
                .toList();
    }

    private static long total(List<DiffFileEntry> files, boolean additions) {
        try {
            long total = 0;
            for (DiffFileEntry file : files) {
                total = Math.addExact(total, additions ? file.additions() : file.deletions());
            }
            return total;
        } catch (ArithmeticException exception) {
            throw new DomainValidationException(
                    additions ? "diffManifest.additions" : "diffManifest.deletions",
                    "exceeds the supported range");
        }
    }

    private RuntimeContentHash calculateContentHash() {
        StringBuilder canonical = new StringBuilder("diff-manifest-v1");
        append(canonical, Integer.toString(files.size()));
        for (DiffFileEntry file : files) {
            append(canonical, file.path().value());
            append(canonical, file.oldPath().map(DiffPath::value).orElse("-"));
            append(canonical, file.kind().name());
            append(canonical, Long.toString(file.additions()));
            append(canonical, Long.toString(file.deletions()));
            append(canonical, Boolean.toString(file.binary()));
            append(canonical, Boolean.toString(file.patchTruncated()));
            append(canonical, file.patchSha256().toString());
        }
        return RuntimeContentHash.sha256(canonical.toString());
    }

    private static void append(StringBuilder target, String value) {
        target.append('|').append(value.length()).append(':').append(value);
    }

    private static void requireExpected(
            Optional<? extends Number> expected, long actual, String field) {
        Objects.requireNonNull(expected, "expected").ifPresent(value -> {
            if (value.longValue() != actual) {
                throw new DomainValidationException(field, "must match the canonical file facts");
            }
        });
    }

    public DiffGeneration generation() {
        return generation;
    }

    public List<DiffFileEntry> files() {
        return files;
    }

    public int fileCount() {
        return fileCount;
    }

    public long additions() {
        return additions;
    }

    public long deletions() {
        return deletions;
    }

    public RuntimeContentHash contentHash() {
        return contentHash;
    }
}

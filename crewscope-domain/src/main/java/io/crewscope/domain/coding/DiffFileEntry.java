package io.crewscope.domain.coding;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.task.RuntimeContentHash;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;

/** Immutable Git-authority facts for one changed path and its bounded Patch preview. */
public record DiffFileEntry(
        DiffPath path,
        Optional<DiffPath> oldPath,
        DiffFileKind kind,
        long additions,
        long deletions,
        boolean binary,
        boolean patchTruncated,
        RuntimeContentHash patchSha256,
        Optional<String> patchPreview) {

    public static final int MAX_PREVIEW_BYTES = 262_144;
    public static final int MAX_PREVIEW_LINES = 4_000;

    public DiffFileEntry {
        path = Objects.requireNonNull(path, "path");
        oldPath = Objects.requireNonNull(oldPath, "oldPath");
        kind = Objects.requireNonNull(kind, "kind");
        requireOldPathShape(path, oldPath, kind);
        if (additions < 0 || deletions < 0) {
            throw new DomainValidationException(
                    "diffFileEntry.lineStatistics", "must not be negative");
        }
        if (binary && (additions != 0 || deletions != 0)) {
            throw new DomainValidationException(
                    "diffFileEntry.lineStatistics", "binary changes use zero line statistics");
        }
        patchSha256 = Objects.requireNonNull(patchSha256, "patchSha256");
        patchPreview = requirePreview(patchPreview, binary);
        if (!patchTruncated
                && patchPreview.isPresent()
                && !RuntimeContentHash.sha256(patchPreview.orElseThrow()).equals(patchSha256)) {
            throw new DomainValidationException(
                    "diffFileEntry.patchSha256",
                    "must match the complete non-truncated Patch preview");
        }
    }

    public static DiffFileEntry text(
            String path,
            Optional<String> oldPath,
            DiffFileKind kind,
            long additions,
            long deletions,
            boolean patchTruncated,
            RuntimeContentHash patchSha256,
            Optional<String> patchPreview) {
        return new DiffFileEntry(
                new DiffPath(path),
                Objects.requireNonNull(oldPath, "oldPath").map(DiffPath::new),
                kind,
                additions,
                deletions,
                false,
                patchTruncated,
                patchSha256,
                patchPreview);
    }

    public static DiffFileEntry binary(
            String path,
            Optional<String> oldPath,
            DiffFileKind kind,
            RuntimeContentHash patchSha256) {
        return new DiffFileEntry(
                new DiffPath(path),
                Objects.requireNonNull(oldPath, "oldPath").map(DiffPath::new),
                kind,
                0,
                0,
                true,
                true,
                patchSha256,
                Optional.empty());
    }

    private static void requireOldPathShape(
            DiffPath path, Optional<DiffPath> oldPath, DiffFileKind kind) {
        boolean requiresOldPath = kind == DiffFileKind.RENAMED || kind == DiffFileKind.COPIED;
        if (requiresOldPath != oldPath.isPresent()) {
            throw new DomainValidationException(
                    "diffFileEntry.oldPath",
                    requiresOldPath
                            ? "is required for renamed and copied files"
                            : "is supported only for renamed and copied files");
        }
        if (oldPath.filter(path::equals).isPresent()) {
            throw new DomainValidationException(
                    "diffFileEntry.oldPath", "must differ from the current path");
        }
    }

    private static Optional<String> requirePreview(Optional<String> preview, boolean binary) {
        Optional<String> required = Objects.requireNonNull(preview, "patchPreview");
        required.ifPresent(value -> {
            int bytes = value.getBytes(StandardCharsets.UTF_8).length;
            if (binary
                    || bytes > MAX_PREVIEW_BYTES
                    || countLines(value) > MAX_PREVIEW_LINES
                    || value.indexOf('\0') >= 0) {
                throw new DomainValidationException(
                        "diffFileEntry.patchPreview",
                        "must be bounded UTF-8 text and is unavailable for binary changes");
            }
        });
        return required;
    }

    private static int countLines(String value) {
        if (value.isEmpty()) {
            return 0;
        }
        int lines = 0;
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) == '\n') {
                lines++;
            }
        }
        return value.endsWith("\n") ? lines : lines + 1;
    }
}

package io.crewscope.domain.review;

import io.crewscope.domain.coding.DiffPath;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.task.RuntimeContentHash;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;

/** One bounded changed Hunk made available to the Reviewer model. */
public record ReviewDiffHunk(
        DiffPath path,
        int startLine,
        int endLine,
        RuntimeContentHash patchHash,
        Optional<String> patch) {

    public ReviewDiffHunk {
        path = Objects.requireNonNull(path, "path");
        if (startLine < 1 || endLine < startLine) {
            throw new DomainValidationException(
                    "reviewDiffHunk.lineRange", "must be a positive ordered range");
        }
        RuntimeContentHash requiredPatchHash = Objects.requireNonNull(patchHash, "patchHash");
        patchHash = requiredPatchHash;
        patch = Objects.requireNonNull(patch, "patch");
        patch.ifPresent(value -> {
            if (value.indexOf('\0') >= 0
                    || value.getBytes(StandardCharsets.UTF_8).length
                            > ContextPackage.MAX_PATCH_BYTES
                    || !RuntimeContentHash.sha256(value).equals(requiredPatchHash)) {
                throw new DomainValidationException(
                        "reviewDiffHunk.patch",
                        "must be bounded UTF-8 text matching the declared Hash");
            }
        });
    }

    public static ReviewDiffHunk captured(
            String path, int startLine, int endLine, String patch) {
        String requiredPatch = Objects.requireNonNull(patch, "patch");
        return new ReviewDiffHunk(
                new DiffPath(path),
                startLine,
                endLine,
                RuntimeContentHash.sha256(requiredPatch),
                Optional.of(requiredPatch));
    }

    public int patchBytes() {
        return patch.map(value -> value.getBytes(StandardCharsets.UTF_8).length).orElse(0);
    }
}

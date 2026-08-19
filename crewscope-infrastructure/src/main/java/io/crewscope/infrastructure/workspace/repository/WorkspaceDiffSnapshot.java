package io.crewscope.infrastructure.workspace.repository;

import io.crewscope.domain.coding.DiffManifest;
import io.crewscope.domain.task.RuntimeContentHash;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** One Git-authoritative Manifest plus the complete bounded Patch used to derive it. */
public record WorkspaceDiffSnapshot(DiffManifest manifest, String fullPatch) {

    public WorkspaceDiffSnapshot {
        manifest = Objects.requireNonNull(manifest, "manifest");
        fullPatch = Objects.requireNonNull(fullPatch, "fullPatch");
        if (fullPatch.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("fullPatch must be UTF-8 text");
        }
    }

    public long patchSizeBytes() {
        return fullPatch.getBytes(StandardCharsets.UTF_8).length;
    }

    public RuntimeContentHash patchHash() {
        return RuntimeContentHash.sha256(fullPatch);
    }
}

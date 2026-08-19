package io.crewscope.infrastructure.workspace.repository;

import io.crewscope.domain.shared.id.ArtifactId;
import io.crewscope.domain.task.RuntimeContentHash;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/** Authorized bounded content stream plus a storage-location-free response envelope. */
public record CodingArtifactReadResult(
        ArtifactId artifactId,
        CodingArtifactKind kind,
        String contentType,
        RuntimeContentHash contentHash,
        long totalSize,
        long startInclusive,
        long endExclusive,
        InputStream stream)
        implements AutoCloseable {

    public CodingArtifactReadResult {
        Objects.requireNonNull(artifactId, "artifactId");
        Objects.requireNonNull(kind, "kind");
        if (contentType == null || contentType.isBlank()) {
            throw new IllegalArgumentException("contentType must not be blank");
        }
        Objects.requireNonNull(contentHash, "contentHash");
        if (totalSize < 0
                || startInclusive < 0
                || endExclusive < startInclusive
                || endExclusive > totalSize) {
            throw new IllegalArgumentException("Coding Artifact response range is invalid");
        }
        Objects.requireNonNull(stream, "stream");
    }

    public long contentLength() {
        return endExclusive - startInclusive;
    }

    public boolean partial() {
        return startInclusive != 0 || endExclusive != totalSize;
    }

    @Override
    public void close() throws IOException {
        stream.close();
    }
}

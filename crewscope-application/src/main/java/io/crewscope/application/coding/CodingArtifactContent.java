package io.crewscope.application.coding;

import io.crewscope.domain.shared.id.ArtifactId;
import io.crewscope.domain.task.RuntimeContentHash;
import java.io.IOException;
import java.io.InputStream;

/** Authorized, integrity-checked Coding Artifact bytes with an exact response boundary. */
public interface CodingArtifactContent extends AutoCloseable {

    ArtifactId artifactId();

    String contentType();

    RuntimeContentHash contentHash();

    long totalSize();

    long startInclusive();

    long endExclusive();

    InputStream stream();

    default long contentLength() {
        return endExclusive() - startInclusive();
    }

    default boolean partial() {
        return startInclusive() != 0 || endExclusive() != totalSize();
    }

    @Override
    void close() throws IOException;
}

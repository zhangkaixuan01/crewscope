package io.crewscope.application.artifact;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/** Authorized one-shot content stream; callers close it after consumption. */
public record ArtifactContent(ArtifactDescriptor descriptor, InputStream stream)
        implements AutoCloseable {

    public ArtifactContent {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(stream, "stream");
    }

    @Override
    public void close() throws IOException {
        stream.close();
    }
}

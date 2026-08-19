package io.crewscope.application.artifact;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/** Authorized one-shot stream for an exact slice of an immutable Artifact. */
public record ArtifactContentRange(
        ArtifactDescriptor descriptor, ArtifactByteRange range, InputStream stream)
        implements AutoCloseable {

    public ArtifactContentRange {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(range, "range").requireWithin(descriptor.size());
        Objects.requireNonNull(stream, "stream");
    }

    /**
     * Takes ownership of a verified full-content stream and exposes only the requested bytes.
     * Closing the range also closes the underlying ArtifactContent.
     */
    public static ArtifactContentRange slice(
            ArtifactContent content, ArtifactByteRange requestedRange) {
        ArtifactContent source = Objects.requireNonNull(content, "content");
        ArtifactByteRange range = Objects.requireNonNull(requestedRange, "requestedRange");
        try {
            range.requireWithin(source.descriptor().size());
            source.stream().skipNBytes(range.startInclusive());
            return new ArtifactContentRange(
                    source.descriptor(), range, new BoundedInputStream(source.stream(), range.length()));
        } catch (RuntimeException failure) {
            closeAfterFailure(source, failure);
            throw failure;
        } catch (IOException failure) {
            closeAfterFailure(source, failure);
            throw new ArtifactStoreException(
                    ArtifactStoreError.INTEGRITY_VIOLATION,
                    "Artifact content ended before the requested byte range",
                    failure);
        }
    }

    public long contentLength() {
        return range.length();
    }

    @Override
    public void close() throws IOException {
        stream.close();
    }

    private static void closeAfterFailure(ArtifactContent source, Exception failure) {
        try {
            source.close();
        } catch (IOException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    /** Prevents a caller from reading past the exact authorized range. */
    private static final class BoundedInputStream extends FilterInputStream {

        private long remaining;

        private BoundedInputStream(InputStream source, long remaining) {
            super(source);
            this.remaining = remaining;
        }

        @Override
        public int read() throws IOException {
            if (remaining == 0) {
                return -1;
            }
            int value = super.read();
            if (value < 0) {
                throw new IOException("Artifact range source ended unexpectedly");
            }
            remaining--;
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length == 0) {
                return 0;
            }
            if (remaining == 0) {
                return -1;
            }
            int requested = (int) Math.min(length, remaining);
            int read = super.read(bytes, offset, requested);
            if (read < 0) {
                throw new IOException("Artifact range source ended unexpectedly");
            }
            remaining -= read;
            return read;
        }

        @Override
        public long skip(long count) throws IOException {
            long skipped = super.skip(Math.min(count, remaining));
            remaining -= skipped;
            return skipped;
        }
    }
}

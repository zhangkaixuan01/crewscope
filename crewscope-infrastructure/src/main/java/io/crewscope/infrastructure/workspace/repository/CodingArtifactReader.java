package io.crewscope.infrastructure.workspace.repository;

import io.crewscope.application.artifact.ArtifactAccessContext;
import io.crewscope.application.artifact.ArtifactByteRange;
import io.crewscope.application.artifact.ArtifactContent;
import io.crewscope.application.artifact.ArtifactContentRange;
import io.crewscope.application.artifact.ArtifactDescriptor;
import io.crewscope.application.artifact.ArtifactStore;
import io.crewscope.application.artifact.ArtifactStoreError;
import io.crewscope.application.artifact.ArtifactStoreException;
import io.crewscope.domain.coding.CommandEvidence;
import io.crewscope.domain.coding.DiffArtifact;
import io.crewscope.domain.coding.TestEvidence;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.RuntimeContentHash;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

/** Reads Coding bytes only after ArtifactStore and committed relational metadata agree exactly. */
public final class CodingArtifactReader {

    private final ArtifactStore artifactStore;
    private final CodingArtifactProperties properties;
    private final Clock clock;

    CodingArtifactReader(
            ArtifactStore artifactStore, CodingArtifactProperties properties, Clock clock) {
        this.artifactStore = Objects.requireNonNull(artifactStore, "artifactStore");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.properties.validate();
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public CodingArtifactSummary summarizePatch(
            DiffArtifact artifact, ArtifactAccessContext accessContext) {
        return summarize(CodingArtifactMetadata.patch(artifact), accessContext);
    }

    public CodingArtifactSummary summarizeBuildLog(
            CommandEvidence evidence, ArtifactAccessContext accessContext) {
        return summarize(CodingArtifactMetadata.commandLog(evidence), accessContext);
    }

    public CodingArtifactSummary summarizeTestReport(
            TestEvidence evidence, ArtifactAccessContext accessContext) {
        return summarize(CodingArtifactMetadata.testReport(evidence), accessContext);
    }

    public CodingArtifactReadResult readPatch(
            DiffArtifact artifact,
            ArtifactAccessContext accessContext,
            Optional<ArtifactByteRange> range) {
        return read(CodingArtifactMetadata.patch(artifact), accessContext, range);
    }

    public CodingArtifactReadResult readBuildLog(
            CommandEvidence evidence,
            ArtifactAccessContext accessContext,
            Optional<ArtifactByteRange> range) {
        return read(CodingArtifactMetadata.commandLog(evidence), accessContext, range);
    }

    public CodingArtifactReadResult readTestReport(
            TestEvidence evidence,
            ArtifactAccessContext accessContext,
            Optional<ArtifactByteRange> range) {
        return read(CodingArtifactMetadata.testReport(evidence), accessContext, range);
    }

    private CodingArtifactSummary summarize(
            CodingArtifactMetadata metadata, ArtifactAccessContext accessContext) {
        ArtifactDescriptor descriptor = descriptor(metadata, accessContext);
        UtcTimestamp now = UtcTimestamp.from(clock.instant());
        CodingArtifactAvailability availability = descriptor.tombstone().isPresent()
                ? CodingArtifactAvailability.TOMBSTONED
                : descriptor.isExpiredAt(now)
                        ? CodingArtifactAvailability.EXPIRED
                        : CodingArtifactAvailability.ACTIVE;
        return new CodingArtifactSummary(
                metadata.artifactId(),
                metadata.kind(),
                metadata.contentType(),
                metadata.sizeBytes(),
                metadata.contentHash(),
                availability,
                descriptor.retentionUntil());
    }

    private CodingArtifactReadResult read(
            CodingArtifactMetadata metadata,
            ArtifactAccessContext accessContext,
            Optional<ArtifactByteRange> range) {
        ArtifactDescriptor committed = descriptor(metadata, accessContext);
        Optional<ArtifactByteRange> requested = Objects.requireNonNull(range, "range");
        requested.ifPresent(value -> {
            if (value.length() > properties.getMaximumRangeBytes()) {
                throw new CodingArtifactException(
                        CodingArtifactError.SIZE_LIMIT_EXCEEDED,
                        "Requested Coding Artifact range exceeds the configured response limit");
            }
            if (value.startInclusive() >= committed.size()
                    || value.endExclusive() > committed.size()) {
                throw new CodingArtifactException(
                        CodingArtifactError.RANGE_NOT_SATISFIABLE,
                        "Coding Artifact range is outside the available content");
            }
        });
        if (requested.isEmpty() && committed.size() > properties.getMaximumRangeBytes()) {
            throw new CodingArtifactException(
                    CodingArtifactError.SIZE_LIMIT_EXCEEDED,
                    "Coding Artifact requires a bounded Range request");
        }
        try {
            return requested.isPresent()
                    ? ranged(metadata, accessContext, requested.orElseThrow())
                    : complete(metadata, accessContext);
        } catch (ArtifactStoreException failure) {
            if (failure.error() == ArtifactStoreError.RANGE_NOT_SATISFIABLE) {
                throw new CodingArtifactException(
                        CodingArtifactError.RANGE_NOT_SATISFIABLE,
                        "Coding Artifact range is outside the available content",
                        failure);
            }
            throw new CodingArtifactException(
                    CodingArtifactError.CONTENT_UNAVAILABLE,
                    "Coding Artifact content could not be read",
                    failure);
        }
    }

    private CodingArtifactReadResult complete(
            CodingArtifactMetadata metadata, ArtifactAccessContext accessContext) {
        ArtifactContent content = artifactStore.get(metadata.artifactId(), accessContext)
                .orElseThrow(CodingArtifactReader::contentUnavailable);
        try {
            metadata.requireMatches(content.descriptor());
            return result(metadata, 0, content.descriptor().size(), content.stream());
        } catch (RuntimeException failure) {
            closeAfterFailure(content, failure);
            throw failure;
        }
    }

    private CodingArtifactReadResult ranged(
            CodingArtifactMetadata metadata,
            ArtifactAccessContext accessContext,
            ArtifactByteRange range) {
        ArtifactContentRange content = artifactStore
                .getRange(metadata.artifactId(), accessContext, range)
                .orElseThrow(CodingArtifactReader::contentUnavailable);
        try {
            metadata.requireMatches(content.descriptor());
            return result(
                    metadata,
                    content.range().startInclusive(),
                    content.range().endExclusive(),
                    content.stream());
        } catch (RuntimeException failure) {
            closeAfterFailure(content, failure);
            throw failure;
        }
    }

    private ArtifactDescriptor descriptor(
            CodingArtifactMetadata metadata, ArtifactAccessContext accessContext) {
        ArtifactAccessContext access = Objects.requireNonNull(accessContext, "accessContext");
        if (!metadata.scope().organizationId().equals(access.organizationId())) {
            throw new CodingArtifactException(
                    CodingArtifactError.INVALID_CONTEXT,
                    "Coding Artifact access context is outside the committed organization");
        }
        ArtifactDescriptor descriptor = artifactStore.head(metadata.artifactId(), access)
                .orElseThrow(CodingArtifactReader::contentUnavailable);
        metadata.requireMatches(descriptor);
        return descriptor;
    }

    private static CodingArtifactReadResult result(
            CodingArtifactMetadata metadata,
            long startInclusive,
            long endExclusive,
            java.io.InputStream stream) {
        return new CodingArtifactReadResult(
                metadata.artifactId(),
                metadata.kind(),
                metadata.contentType(),
                new RuntimeContentHash(metadata.contentHash().value()),
                metadata.sizeBytes(),
                startInclusive,
                endExclusive,
                stream);
    }

    private static CodingArtifactException contentUnavailable() {
        return new CodingArtifactException(
                CodingArtifactError.CONTENT_UNAVAILABLE,
                "Coding Artifact is unavailable, unauthorized or outside retention");
    }

    private static void closeAfterFailure(AutoCloseable content, RuntimeException failure) {
        try {
            content.close();
        } catch (Exception closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }
}

package io.crewscope.domain.coding;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.ArtifactId;
import io.crewscope.domain.task.RuntimeContentHash;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Integrity-closed logical ArtifactStore reference for a command log or test report. */
public record EvidenceArtifactReference(
        ArtifactId artifactId,
        EvidenceArtifactKind kind,
        String contentType,
        long sizeBytes,
        RuntimeContentHash contentHash) {

    private static final Pattern CONTENT_TYPE = Pattern.compile(
            "[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+(?:;[a-z0-9!#$&^_.+\\-=]+)*");

    public EvidenceArtifactReference {
        artifactId = Objects.requireNonNull(artifactId, "artifactId");
        kind = Objects.requireNonNull(kind, "kind");
        contentType = requireContentType(contentType);
        if (sizeBytes < 0) {
            throw new DomainValidationException(
                    "evidenceArtifactReference.sizeBytes", "must not be negative");
        }
        contentHash = Objects.requireNonNull(contentHash, "contentHash");
        if (sizeBytes == 0 && !RuntimeContentHash.sha256("").equals(contentHash)) {
            throw new DomainValidationException(
                    "evidenceArtifactReference.contentHash",
                    "an empty Artifact must use the SHA-256 of empty content");
        }
    }

    private static String requireContentType(String value) {
        if (value == null || value.isBlank()) {
            throw invalidContentType();
        }
        String normalized = value.strip().toLowerCase(Locale.ROOT).replace(" ", "");
        if (normalized.length() > 255 || !CONTENT_TYPE.matcher(normalized).matches()) {
            throw invalidContentType();
        }
        return normalized;
    }

    private static DomainValidationException invalidContentType() {
        return new DomainValidationException(
                "evidenceArtifactReference.contentType", "must be a bounded canonical media type");
    }
}

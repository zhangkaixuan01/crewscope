package io.crewscope.application.artifact;

import io.crewscope.domain.shared.id.ArtifactId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.time.DateTimeException;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** Immutable metadata and integrity claims supplied before streaming artifact content. */
public record ArtifactWriteRequest(
        ArtifactId artifactId,
        ArtifactScope scope,
        String contentType,
        long declaredSize,
        Sha256Hash expectedHash,
        ArtifactDataClassification dataClassification,
        ArtifactVisibility visibility,
        Optional<Duration> timeToLive,
        ArtifactProducer producer) {

    public static final int MAX_CONTENT_TYPE_LENGTH = 255;
    private static final Duration MINIMUM_TTL = Duration.ofNanos(1_000);
    private static final Pattern MEDIA_TYPE_TOKEN =
            Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");

    public ArtifactWriteRequest {
        Objects.requireNonNull(artifactId, "artifactId");
        Objects.requireNonNull(scope, "scope");
        contentType = requireContentType(contentType);
        if (declaredSize < 0) {
            throw new IllegalArgumentException("declaredSize must not be negative");
        }
        Objects.requireNonNull(expectedHash, "expectedHash");
        Objects.requireNonNull(dataClassification, "dataClassification");
        Objects.requireNonNull(visibility, "visibility");
        scope.validateVisibility(visibility);
        timeToLive = requireTimeToLive(timeToLive);
        Objects.requireNonNull(producer, "producer");
    }

    /** Resolves the retention deadline using the Store's persisted creation timestamp. */
    public Optional<UtcTimestamp> retentionUntil(UtcTimestamp createdAt) {
        UtcTimestamp timestamp = Objects.requireNonNull(createdAt, "createdAt");
        try {
            return timeToLive.map(ttl -> UtcTimestamp.from(timestamp.value().plus(ttl)));
        } catch (DateTimeException | ArithmeticException exception) {
            throw new IllegalArgumentException("timeToLive exceeds the supported time range", exception);
        }
    }

    static String requireContentType(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("contentType must not be blank");
        }
        String normalized = value.strip();
        if (normalized.length() > MAX_CONTENT_TYPE_LENGTH) {
            throw new IllegalArgumentException("contentType must be a valid media type");
        }
        String mediaType = normalized.split(";", 2)[0].strip();
        int separator = mediaType.indexOf('/');
        if (separator <= 0
                || separator != mediaType.lastIndexOf('/')
                || !MEDIA_TYPE_TOKEN.matcher(mediaType.substring(0, separator)).matches()
                || !MEDIA_TYPE_TOKEN.matcher(mediaType.substring(separator + 1)).matches()) {
            throw new IllegalArgumentException("contentType must be a valid media type");
        }
        if (normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("contentType must not contain control characters");
        }
        return normalized;
    }

    private static Optional<Duration> requireTimeToLive(Optional<Duration> value) {
        Optional<Duration> required = Objects.requireNonNull(value, "timeToLive");
        required.ifPresent(ttl -> {
            if (ttl.compareTo(MINIMUM_TTL) < 0) {
                throw new IllegalArgumentException(
                        "timeToLive must be positive at UTC microsecond precision");
            }
        });
        return required;
    }
}

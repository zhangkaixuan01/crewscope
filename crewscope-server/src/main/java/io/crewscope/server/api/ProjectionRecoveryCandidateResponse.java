package io.crewscope.server.api;

/** Projection dead-letter replay coordinates with the exact strong confirmation phrase. */
public record ProjectionRecoveryCandidateResponse(
        String type,
        String action,
        String projectionName,
        long generation,
        String deadLetterId,
        String domainEventId,
        long expectedGenerationVersion,
        String referenceHash,
        String confirmation) implements RecoveryCandidateResponse {}

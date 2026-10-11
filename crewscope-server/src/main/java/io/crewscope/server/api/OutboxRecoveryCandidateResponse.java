package io.crewscope.server.api;

/** Outbox dead-letter replay coordinates with the exact strong confirmation phrase. */
public record OutboxRecoveryCandidateResponse(
        String type,
        String action,
        String outboxEventId,
        String domainEventId,
        long expectedVersion,
        String referenceHash,
        String confirmation) implements RecoveryCandidateResponse {}

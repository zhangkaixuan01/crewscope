package io.crewscope.server.api;

/** Closed union of the diagnostics-provided recovery coordinates. */
public sealed interface RecoveryCandidateResponse permits
        OutboxRecoveryCandidateResponse,
        ProjectionRecoveryCandidateResponse,
        NotificationRecoveryCandidateResponse {}

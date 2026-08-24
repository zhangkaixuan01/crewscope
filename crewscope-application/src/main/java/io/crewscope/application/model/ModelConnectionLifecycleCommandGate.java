package io.crewscope.application.model;

import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.command.CommandReservation;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Optional;
import java.util.UUID;

/** Keeps public-command idempotency in the same transaction as a connection lifecycle fact. */
public interface ModelConnectionLifecycleCommandGate {

    /** Checks a completed replay before any external verification call is attempted. */
    Optional<CommandReceipt> findCompletedReplay();

    /** Rechecks current authority and reserves or replays the public idempotency key. */
    CommandReservation reserve(UtcTimestamp occurredAt);

    /** Completes the acquired receipt against the exact committed domain event. */
    CommandReceipt complete(UUID domainEventId, long committedVersion, UtcTimestamp occurredAt);
}

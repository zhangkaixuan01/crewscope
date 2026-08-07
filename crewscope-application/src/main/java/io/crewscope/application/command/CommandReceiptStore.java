package io.crewscope.application.command;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.time.UtcTimestamp;

/** Persistence Port for concurrent command reservation and durable completion receipts. */
public interface CommandReceiptStore {

    /** Acquires the key or returns the completed receipt for a semantically identical command. */
    CommandReservation reserve(CommandReservationRequest request);

    /** Completes an acquired reservation in the same transaction as all command side effects. */
    void complete(
            OrganizationId organizationId,
            IdempotencyKey idempotencyKey,
            CommandReceipt receipt,
            UtcTimestamp completedAt);
}

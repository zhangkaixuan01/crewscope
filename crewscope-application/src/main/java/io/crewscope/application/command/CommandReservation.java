package io.crewscope.application.command;

import java.util.Objects;
import java.util.Optional;

/** Outcome of the atomic idempotency reservation attempt. */
public record CommandReservation(boolean acquired, Optional<CommandReceipt> receipt) {

    public CommandReservation {
        receipt = Objects.requireNonNull(receipt, "receipt");
        if (acquired == receipt.isPresent()) {
            throw new IllegalArgumentException("Reservation must be acquired or return one receipt");
        }
    }

    public static CommandReservation newlyAcquired() {
        return new CommandReservation(true, Optional.empty());
    }

    public static CommandReservation replay(CommandReceipt receipt) {
        return new CommandReservation(false, Optional.of(receipt));
    }
}

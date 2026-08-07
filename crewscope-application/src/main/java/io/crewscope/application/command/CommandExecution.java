package io.crewscope.application.command;

import java.util.Objects;
import java.util.Optional;

/** First execution carries its result; an idempotent replay carries the original receipt only. */
public record CommandExecution<T>(
        Optional<T> result, CommandReceipt receipt, boolean replayed) {

    public CommandExecution {
        result = Objects.requireNonNull(result, "result");
        receipt = Objects.requireNonNull(receipt, "receipt");
        if (replayed && result.isPresent()) {
            throw new IllegalArgumentException("A replay must not expose a newly executed result");
        }
        if (!replayed && result.isEmpty()) {
            throw new IllegalArgumentException("A first execution must expose its result");
        }
    }

    public static <T> CommandExecution<T> completed(T result, CommandReceipt receipt) {
        return new CommandExecution<>(
                Optional.of(Objects.requireNonNull(result, "result")), receipt, false);
    }

    public static <T> CommandExecution<T> replayed(CommandReceipt receipt) {
        return new CommandExecution<>(Optional.empty(), receipt, true);
    }
}

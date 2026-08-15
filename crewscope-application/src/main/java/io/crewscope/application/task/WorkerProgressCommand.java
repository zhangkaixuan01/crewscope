package io.crewscope.application.task;

import java.util.Objects;
import java.util.Optional;

/** Bounded public progress plus the expected TaskExecution version. */
public record WorkerProgressCommand(
        long expectedExecutionVersion, String safeSummary, Optional<Integer> percent) {

    public WorkerProgressCommand {
        if (expectedExecutionVersion < 0) {
            throw new IllegalArgumentException("expectedExecutionVersion must not be negative");
        }
        safeSummary = requireText(safeSummary);
        percent = Objects.requireNonNull(percent, "percent");
        percent.ifPresent(value -> {
            if (value < 0 || value > 100) {
                throw new IllegalArgumentException("percent must be between 0 and 100");
            }
        });
    }

    private static String requireText(String value) {
        String normalized = Objects.requireNonNull(value, "safeSummary").strip();
        if (normalized.isEmpty()
                || normalized.length() > 1_000
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    "safeSummary must contain between 1 and 1000 printable characters");
        }
        return normalized;
    }
}

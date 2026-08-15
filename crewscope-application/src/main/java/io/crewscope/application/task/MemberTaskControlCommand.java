package io.crewscope.application.task;

import io.crewscope.domain.shared.error.DomainValidationException;

/** Expected attempt version and safe member-visible reason for Pause or Cancel. */
public record MemberTaskControlCommand(long expectedExecutionVersion, String reason) {

    public static final int MAX_REASON_LENGTH = 500;

    public MemberTaskControlCommand {
        if (expectedExecutionVersion < 0) {
            throw new IllegalArgumentException("expectedExecutionVersion must not be negative");
        }
        if (reason == null || reason.isBlank()) {
            throw new DomainValidationException("taskControl.reason", "must not be blank");
        }
        reason = reason.strip();
        if (reason.length() > MAX_REASON_LENGTH
                || reason.chars().anyMatch(Character::isISOControl)) {
            throw new DomainValidationException(
                    "taskControl.reason", "must contain at most 500 printable characters");
        }
    }
}

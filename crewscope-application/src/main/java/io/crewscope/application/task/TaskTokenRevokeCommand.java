package io.crewscope.application.task;

/** Revokes the persisted Grant backing a presented Task Token. */
public record TaskTokenRevokeCommand(String token, long expectedGrantVersion, String reason) {
    public TaskTokenRevokeCommand {
        if (token == null || token.isBlank() || token.length() > 16384) {
            throw new IllegalArgumentException("token must be a bounded non-blank value");
        }
        if (expectedGrantVersion < 0) {
            throw new IllegalArgumentException("expectedGrantVersion must not be negative");
        }
        if (reason == null || reason.isBlank() || reason.strip().length() > 500) {
            throw new IllegalArgumentException("reason must contain at most 500 characters");
        }
        reason = reason.strip();
    }

    @Override
    public String toString() {
        return "TaskTokenRevokeCommand[token=[REDACTED], reason=[REDACTED]]";
    }
}

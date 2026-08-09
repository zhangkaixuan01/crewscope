package io.crewscope.application.execution;

import java.util.Objects;
import java.util.Optional;

/** Closed event payload family shared by Conversation runtime implementations and consumers. */
public sealed interface ExecutionEventPayload {

    default Optional<ExecutionTerminalStatus> terminalStatus() {
        return Optional.empty();
    }

    record Started(ExecutionSegmentKind segmentKind) implements ExecutionEventPayload {
        public Started {
            segmentKind = Objects.requireNonNull(segmentKind, "segmentKind");
        }
    }

    record TextDelta(String text) implements ExecutionEventPayload {
        public TextDelta {
            text = Objects.requireNonNull(text, "text");
            if (text.isEmpty() || text.length() > 10_000) {
                throw new IllegalArgumentException(
                        "text delta must contain between 1 and 10000 characters");
            }
        }
    }

    record StructuredOutput<T>(StructuredOutputSpec<T> spec, T value)
            implements ExecutionEventPayload {
        public StructuredOutput {
            spec = Objects.requireNonNull(spec, "spec");
            value = spec.requireValue(value);
        }
    }

    record Completed() implements ExecutionEventPayload {
        @Override
        public Optional<ExecutionTerminalStatus> terminalStatus() {
            return Optional.of(ExecutionTerminalStatus.COMPLETED);
        }
    }

    record Interrupted(
            ExecutionInterruptToken token,
            ExecutionInterruptKind kind,
            String safePrompt)
            implements ExecutionEventPayload {
        public Interrupted {
            token = Objects.requireNonNull(token, "token");
            kind = Objects.requireNonNull(kind, "kind");
            safePrompt = requireReason(safePrompt, "safePrompt", 1_000);
        }

        @Override
        public Optional<ExecutionTerminalStatus> terminalStatus() {
            return Optional.of(ExecutionTerminalStatus.INTERRUPTED);
        }
    }

    record Canceled(String reason) implements ExecutionEventPayload {
        public Canceled {
            reason = requireReason(reason, "reason", 500);
        }

        @Override
        public Optional<ExecutionTerminalStatus> terminalStatus() {
            return Optional.of(ExecutionTerminalStatus.CANCELED);
        }
    }

    record Failed(ExecutionFailure failure) implements ExecutionEventPayload {
        public Failed {
            failure = Objects.requireNonNull(failure, "failure");
        }

        @Override
        public Optional<ExecutionTerminalStatus> terminalStatus() {
            return Optional.of(ExecutionTerminalStatus.FAILED);
        }
    }

    private static String requireReason(String value, String field, int maxLength) {
        String required = Objects.requireNonNull(value, field).strip();
        if (required.isEmpty()
                || required.length() > maxLength
                || required.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    field + " must contain 1 to " + maxLength + " printable characters");
        }
        return required;
    }
}

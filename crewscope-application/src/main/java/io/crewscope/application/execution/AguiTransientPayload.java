package io.crewscope.application.execution;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Safe AG-UI payload family emitted before PostgreSQL business facts are committed.
 *
 * <p>The payloads intentionally have no Reasoning, Tool argument/result, Provider response or
 * RuntimeContext fields. The enclosing realtime envelope supplies correlation and causation.
 */
public sealed interface AguiTransientPayload {

    String threadId();

    String runId();

    UUID segmentId();

    record RunStarted(
            String threadId,
            String runId,
            UUID segmentId,
            ExecutionSegmentKind segmentKind)
            implements AguiTransientPayload {

        public RunStarted {
            threadId = requireProtocolId(threadId, "threadId");
            runId = requireProtocolId(runId, "runId");
            segmentId = Objects.requireNonNull(segmentId, "segmentId");
            segmentKind = Objects.requireNonNull(segmentKind, "segmentKind");
        }
    }

    record TextMessageContent(
            String threadId,
            String runId,
            UUID segmentId,
            String messageId,
            String delta)
            implements AguiTransientPayload {

        public TextMessageContent {
            threadId = requireProtocolId(threadId, "threadId");
            runId = requireProtocolId(runId, "runId");
            segmentId = Objects.requireNonNull(segmentId, "segmentId");
            messageId = requireProtocolId(messageId, "messageId");
            delta = Objects.requireNonNull(delta, "delta");
            if (delta.isEmpty() || delta.length() > 10_000) {
                throw new IllegalArgumentException(
                        "delta must contain between 1 and 10000 characters");
            }
        }
    }

    /** Carries the server-issued resume coordinate but no raw pending Tool arguments. */
    record RunInterrupted(
            String threadId,
            String runId,
            UUID segmentId,
            String interruptToken,
            ExecutionInterruptKind kind,
            String safePrompt)
            implements AguiTransientPayload {

        public RunInterrupted {
            threadId = requireProtocolId(threadId, "threadId");
            runId = requireProtocolId(runId, "runId");
            segmentId = Objects.requireNonNull(segmentId, "segmentId");
            interruptToken = requireProtocolId(interruptToken, "interruptToken");
            kind = Objects.requireNonNull(kind, "kind");
            safePrompt = requireSafeText(safePrompt, "safePrompt", 1_000);
        }
    }

    record RunFinished(
            String threadId,
            String runId,
            UUID segmentId,
            ExecutionTerminalStatus status)
            implements AguiTransientPayload {

        public RunFinished {
            threadId = requireProtocolId(threadId, "threadId");
            runId = requireProtocolId(runId, "runId");
            segmentId = Objects.requireNonNull(segmentId, "segmentId");
            status = Objects.requireNonNull(status, "status");
            if (status != ExecutionTerminalStatus.COMPLETED
                    && status != ExecutionTerminalStatus.CANCELED) {
                throw new IllegalArgumentException(
                        "RunFinished status must be COMPLETED or CANCELED");
            }
        }
    }

    /** Contains only the already-sanitized runtime failure contract. */
    record RunError(
            String threadId,
            String runId,
            UUID segmentId,
            String safeMessage,
            Optional<String> runtimeCode,
            boolean retryable)
            implements AguiTransientPayload {

        public RunError {
            threadId = requireProtocolId(threadId, "threadId");
            runId = requireProtocolId(runId, "runId");
            segmentId = Objects.requireNonNull(segmentId, "segmentId");
            safeMessage = requireSafeText(safeMessage, "safeMessage", 500);
            runtimeCode = Objects.requireNonNull(runtimeCode, "runtimeCode");
        }
    }

    private static String requireProtocolId(String value, String field) {
        return requireSafeText(value, field, 512);
    }

    private static String requireSafeText(String value, String field, int maximumLength) {
        String required = Objects.requireNonNull(value, field).strip();
        if (required.isEmpty()
                || required.length() > maximumLength
                || required.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    field + " must contain 1 to " + maximumLength + " printable characters");
        }
        return required;
    }
}

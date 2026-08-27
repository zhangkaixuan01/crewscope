package io.crewscope.application.teamobserver;

import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.teamobserver.TeamSummaryResult;
import java.util.Objects;
import java.util.Optional;

/** Ordered safe event retained for SSE replay without model, Tool or private identity payloads. */
public record TeamObserverStreamEvent(
        TeamObserverInvocationId invocationId,
        long sequence,
        UtcTimestamp occurredAt,
        Type type,
        Optional<TeamSummaryResult> summary,
        Optional<String> errorCode) {

    public TeamObserverStreamEvent {
        invocationId = Objects.requireNonNull(invocationId, "invocationId");
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        type = Objects.requireNonNull(type, "type");
        summary = Objects.requireNonNull(summary, "summary");
        errorCode = Objects.requireNonNull(errorCode, "errorCode");
        if ((type == Type.SUMMARY_COMPLETED) != summary.isPresent()
                || (type == Type.FAILED) != errorCode.isPresent()) {
            throw new IllegalArgumentException("Team Observer event payload does not match its type");
        }
    }

    public enum Type {
        STARTED,
        SUMMARY_COMPLETED,
        CANCELLED,
        FAILED
    }
}

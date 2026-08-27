package io.crewscope.application.teamobserver;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Logical Team Observer invocation retained while an SSE client disconnects and resumes. */
public record TeamObserverInvocationId(UUID value) implements AggregateId {

    public TeamObserverInvocationId {
        value = AggregateId.requireValue(value, "TeamObserverInvocationId");
    }

    public static TeamObserverInvocationId generate() {
        return new TeamObserverInvocationId(AggregateId.generateValue());
    }

    public static TeamObserverInvocationId from(String value) {
        return new TeamObserverInvocationId(
                AggregateId.parseCanonical(value, "TeamObserverInvocationId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}

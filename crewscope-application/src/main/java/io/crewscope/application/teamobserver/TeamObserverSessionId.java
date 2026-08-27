package io.crewscope.application.teamobserver;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Server-issued identity of one member-bound Team Observer conversation session. */
public record TeamObserverSessionId(UUID value) implements AggregateId {

    public TeamObserverSessionId {
        value = AggregateId.requireValue(value, "TeamObserverSessionId");
    }

    public static TeamObserverSessionId generate() {
        return new TeamObserverSessionId(AggregateId.generateValue());
    }

    public static TeamObserverSessionId from(String value) {
        return new TeamObserverSessionId(
                AggregateId.parseCanonical(value, "TeamObserverSessionId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}

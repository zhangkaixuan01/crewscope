package io.crewscope.domain.shared.id;

import java.util.UUID;

/** Strongly typed identifier of one soft budget alert (the deduplication key). */
public record TeamBudgetAlertId(UUID value) implements AggregateId {

    public TeamBudgetAlertId {
        value = AggregateId.requireValue(value, "TeamBudgetAlertId");
    }

    public static TeamBudgetAlertId generate() {
        return new TeamBudgetAlertId(AggregateId.generateValue());
    }

    public static TeamBudgetAlertId from(UUID value) {
        return new TeamBudgetAlertId(value);
    }
}

package io.crewscope.domain.shared.error;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.Map;
import java.util.Objects;

/** Reports that an aggregate identifier has no visible matching aggregate. */
public final class AggregateNotFoundException extends DomainException {

    public AggregateNotFoundException(String aggregateType, AggregateId aggregateId) {
        super(new DomainError(
                DomainErrorCode.AGGREGATE_NOT_FOUND,
                "%s %s was not found".formatted(requireText(aggregateType), aggregateId),
                Map.of(
                        "aggregateType", aggregateType.strip(),
                        "aggregateId", Objects.requireNonNull(aggregateId, "aggregateId").toString())));
    }

    private static String requireText(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("aggregateType must not be blank");
        }
        return value.strip();
    }
}

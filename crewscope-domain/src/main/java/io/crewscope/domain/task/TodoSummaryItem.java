package io.crewscope.domain.task;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.Objects;
import java.util.Optional;

/** Safe runtime Todo projection preserved with a PlanVersion without controlling Step state. */
public record TodoSummaryItem(
        String content, TodoStatus status, Optional<String> priority, Optional<String> planStepKey) {
    public TodoSummaryItem {
        if (content == null || content.isBlank()) {
            throw new DomainValidationException("todoSummary.content", "must not be blank");
        }
        content = content.strip();
        if (content.length() > 1000 || content.chars().anyMatch(Character::isISOControl)) {
            throw new DomainValidationException(
                    "todoSummary.content", "must not exceed 1000 safe characters");
        }
        status = Objects.requireNonNull(status, "status");
        priority = Objects.requireNonNull(priority, "priority").map(value -> {
            String normalized = value.strip();
            if (normalized.isEmpty() || normalized.length() > 20
                    || normalized.chars().anyMatch(Character::isISOControl)) {
                throw new DomainValidationException("todoSummary.priority", "is invalid");
            }
            return normalized;
        });
        planStepKey = Objects.requireNonNull(planStepKey, "planStepKey")
                .map(PlanStep::requireKey);
    }
}

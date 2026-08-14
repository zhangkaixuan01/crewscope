package io.crewscope.domain.task;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.List;
import java.util.Objects;

/** Framework-neutral normalized plan candidate produced by an execution Runtime. */
public record ProposedPlan(String markdown, TaskFactHash contentHash, List<PlanStep> steps) {

    public static final int MAX_MARKDOWN_LENGTH = 100_000;

    public ProposedPlan {
        markdown = normalizeMarkdown(markdown);
        TaskFactHash expected = TaskFactHash.sha256(markdown);
        if (!expected.equals(Objects.requireNonNull(contentHash, "contentHash"))) {
            throw new DomainValidationException(
                    "proposedPlan.contentHash", "must match normalized markdown");
        }
        contentHash = expected;
        steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
        if (steps.isEmpty() || steps.size() > 100) {
            throw new DomainValidationException(
                    "proposedPlan.steps", "must contain 1 to 100 steps");
        }
    }

    public static ProposedPlan of(String markdown, List<PlanStep> steps) {
        String normalized = normalizeMarkdown(markdown);
        return new ProposedPlan(normalized, TaskFactHash.sha256(normalized), steps);
    }

    private static String normalizeMarkdown(String value) {
        if (value == null) {
            throw new DomainValidationException("proposedPlan.markdown", "must not be null");
        }
        String normalized = value.replace("\r\n", "\n").replace('\r', '\n').strip();
        if (normalized.isEmpty() || normalized.length() > MAX_MARKDOWN_LENGTH
                || normalized.chars().anyMatch(character ->
                        Character.isISOControl(character) && character != '\n' && character != '\t')) {
            throw new DomainValidationException(
                    "proposedPlan.markdown", "must contain safe normalized content");
        }
        return normalized;
    }
}

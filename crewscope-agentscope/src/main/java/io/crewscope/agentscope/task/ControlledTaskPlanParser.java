package io.crewscope.agentscope.task;

import io.crewscope.application.task.TaskPlanPublicationService;
import io.crewscope.domain.task.ExecutionCapability;
import io.crewscope.domain.task.PlanStep;
import io.crewscope.domain.task.PlanStepType;
import io.crewscope.domain.task.ProposedPlan;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Parses the deliberately small, deterministic M3 Task plan language. */
public final class ControlledTaskPlanParser {

    public static final String HEADER = "# Controlled Task Plan";
    public static final String FORMAT =
            "- `key` | TYPE | Title | deps=- | capabilities=PLAN | "
                    + "tools=fixture.inspect | critical=true";
    private static final int MAX_STEPS = 100;

    /** Revalidates the exact Markdown at the publication boundary. */
    public ProposedPlan parse(String markdown) {
        String normalized = normalize(markdown);
        List<String> nonBlank = normalized.lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .toList();
        if (nonBlank.isEmpty() || !HEADER.equals(nonBlank.get(0))) {
            throw invalid("the first non-blank line must be '" + HEADER + "'");
        }
        if (nonBlank.size() < 2 || nonBlank.size() > MAX_STEPS + 1) {
            throw invalid("the plan must contain 1 to " + MAX_STEPS + " step lines");
        }
        List<PlanStep> steps = new ArrayList<>(nonBlank.size() - 1);
        for (int index = 1; index < nonBlank.size(); index++) {
            steps.add(parseStep(nonBlank.get(index), index));
        }
        validateGraph(steps);
        // ProposedPlan and PlanVersion intentionally perform another independent domain check.
        return ProposedPlan.of(normalized, steps);
    }

    public Validation validate(String markdown) {
        try {
            ProposedPlan candidate = parse(markdown);
            return new Validation(true, candidate.steps().size(), "VALID");
        } catch (IllegalArgumentException exception) {
            return new Validation(false, 0, exception.getMessage());
        } catch (RuntimeException exception) {
            return new Validation(false, 0, "the plan violates CrewScope domain constraints");
        }
    }

    private static PlanStep parseStep(String line, int sequence) {
        if (!line.startsWith("- ")) {
            throw invalid("step " + sequence + " must start with '- '");
        }
        String[] columns = line.substring(2).split("\\|", -1);
        if (columns.length != 7) {
            throw invalid("step " + sequence + " must use exactly seven pipe-delimited columns");
        }
        String key = stripBackticks(columns[0].strip());
        PlanStepType type = enumValue(PlanStepType.class, columns[1], "type", sequence);
        String title = columns[2].strip();
        Set<String> dependencies = values(columns[3], "deps", sequence, true);
        Set<ExecutionCapability> capabilities = values(
                        columns[4], "capabilities", sequence, false)
                .stream()
                .map(value -> enumValue(
                        ExecutionCapability.class, value, "capability", sequence))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> tools = values(columns[5], "tools", sequence, false);
        if (!TaskPlanPublicationService.M3_CONTROLLED_TOOLS.containsAll(tools)) {
            throw invalid("step " + sequence + " requests a non-Fixture Tool");
        }
        boolean critical = booleanValue(columns[6], "critical", sequence);
        return new PlanStep(
                key,
                sequence,
                title,
                type,
                dependencies,
                capabilities,
                tools,
                critical);
    }

    private static void validateGraph(List<PlanStep> steps) {
        java.util.Map<String, Integer> sequenceByKey = new java.util.LinkedHashMap<>();
        for (PlanStep step : steps) {
            if (sequenceByKey.putIfAbsent(step.key(), step.sequence()) != null) {
                throw invalid("step keys must be unique");
            }
        }
        for (PlanStep step : steps) {
            for (String dependency : step.dependencyKeys()) {
                Integer dependencySequence = sequenceByKey.get(dependency);
                if (dependencySequence == null || dependencySequence >= step.sequence()) {
                    throw invalid("dependencies must reference an earlier step");
                }
            }
        }
        if (steps.stream().noneMatch(step -> step.type() == PlanStepType.VALIDATION)) {
            throw invalid("the plan must contain a VALIDATION step");
        }
    }

    private static Set<String> values(
            String column, String name, int sequence, boolean dashMeansEmpty) {
        String prefix = name + "=";
        String required = column.strip();
        if (!required.startsWith(prefix)) {
            throw invalid("step " + sequence + " requires the '" + prefix + "' column");
        }
        String raw = required.substring(prefix.length()).strip();
        if (dashMeansEmpty && "-".equals(raw)) {
            return Set.of();
        }
        if (raw.isEmpty() || "-".equals(raw)) {
            throw invalid("step " + sequence + " requires at least one " + name + " value");
        }
        LinkedHashSet<String> result = Arrays.stream(raw.split(",", -1))
                .map(String::strip)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (result.contains("") || result.size() != raw.split(",", -1).length) {
            throw invalid("step " + sequence + " contains blank or duplicate " + name + " values");
        }
        return Set.copyOf(result);
    }

    private static boolean booleanValue(String column, String name, int sequence) {
        String prefix = name + "=";
        String required = column.strip();
        if (!required.startsWith(prefix)) {
            throw invalid("step " + sequence + " requires the '" + prefix + "' column");
        }
        return switch (required.substring(prefix.length()).strip()) {
            case "true" -> true;
            case "false" -> false;
            default -> throw invalid("step " + sequence + " has an invalid boolean " + name);
        };
    }

    private static <E extends Enum<E>> E enumValue(
            Class<E> type, String value, String name, int sequence) {
        try {
            return Enum.valueOf(type, value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw invalid("step " + sequence + " has an unsupported " + name);
        }
    }

    private static String stripBackticks(String value) {
        if (value.length() >= 2 && value.startsWith("`") && value.endsWith("`")) {
            return value.substring(1, value.length() - 1).strip();
        }
        return value;
    }

    private static String normalize(String value) {
        String normalized = Objects.requireNonNull(value, "markdown")
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .strip();
        if (normalized.isEmpty() || normalized.length() > ProposedPlan.MAX_MARKDOWN_LENGTH) {
            throw invalid("plan Markdown is empty or exceeds the M3 limit");
        }
        return normalized;
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("Invalid controlled Task plan: " + message);
    }

    /** Model-safe validation result; it contains no domain identifiers or authorization facts. */
    public record Validation(boolean valid, int stepCount, String message) {
        public Validation {
            if (stepCount < 0 || (valid && stepCount < 1)) {
                throw new IllegalArgumentException("stepCount is inconsistent with validation");
            }
            message = Objects.requireNonNull(message, "message");
        }
    }
}

package io.crewscope.agentscope.task;

import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.Task;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Maps AgentScope planning state into immutable CrewScope candidates without publishing domain
 * facts.
 *
 * <p>AgentScope Plan Mode and TodoTools are runtime cognition. M3 Application Services validate
 * these candidates before creating a PlanVersion, StepExecution or durable AgentRun.
 */
public final class AgentScopeTaskPlanningSnapshotMapper {

    private static final int MAX_PLAN_LENGTH = 100_000;
    private static final int MAX_TODOS = 100;
    private static final int MAX_TODO_CONTENT_LENGTH = 1_000;

    public TaskPlanningSnapshot map(AgentState state, Optional<String> planMarkdown) {
        AgentState requiredState = Objects.requireNonNull(state, "state");
        Optional<String> normalizedPlan = Objects.requireNonNull(planMarkdown, "planMarkdown")
                .map(AgentScopeTaskPlanningSnapshotMapper::normalizePlan);
        String planPath = optionalText(
                requiredState.getPlanModeContext().getCurrentPlanFile(), "planPath", 1_000)
                .orElse(null);
        ProposedPlan proposedPlan = normalizedPlan
                .map(markdown -> new ProposedPlan(
                        Optional.ofNullable(planPath), markdown, sha256(markdown)))
                .orElse(null);
        List<Task> runtimeTodos = requiredState.getTasksContext().getTasks();
        if (runtimeTodos.size() > MAX_TODOS) {
            throw new IllegalArgumentException("AgentScope Todo count exceeds the M3 limit");
        }
        List<TodoSnapshotItem> todos = runtimeTodos.stream()
                .map(AgentScopeTaskPlanningSnapshotMapper::mapTodo)
                .toList();
        return new TaskPlanningSnapshot(
                requiredState.getPlanModeContext().isPlanActive(),
                Optional.ofNullable(proposedPlan),
                todos);
    }

    private static TodoSnapshotItem mapTodo(Task task) {
        Task required = Objects.requireNonNull(task, "todo");
        String content = requireText(required.getSubject(), "todo content", MAX_TODO_CONTENT_LENGTH);
        TodoStatus status = switch (Objects.requireNonNull(required.getState(), "todo state")) {
            case PENDING -> TodoStatus.PENDING;
            case IN_PROGRESS -> TodoStatus.IN_PROGRESS;
            case COMPLETED -> TodoStatus.COMPLETED;
        };
        Optional<String> priority = optionalText(
                required.getMetadata().get("priority"), "todo priority", 20);
        return new TodoSnapshotItem(content, status, priority);
    }

    private static String normalizePlan(String markdown) {
        String normalized = Objects.requireNonNull(markdown, "planMarkdown")
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .strip();
        if (normalized.isEmpty() || normalized.length() > MAX_PLAN_LENGTH) {
            throw new IllegalArgumentException(
                    "planMarkdown must contain 1 to " + MAX_PLAN_LENGTH + " characters");
        }
        if (normalized.chars().anyMatch(character ->
                Character.isISOControl(character) && character != '\n' && character != '\t')) {
            throw new IllegalArgumentException("planMarkdown contains unsupported control characters");
        }
        return normalized;
    }

    private static String requireText(Object value, String field, int maxLength) {
        return optionalText(value, field, maxLength)
                .orElseThrow(() -> new IllegalArgumentException(field + " must not be blank"));
    }

    private static Optional<String> optionalText(Object value, String field, int maxLength) {
        if (value == null) {
            return Optional.empty();
        }
        String normalized = String.valueOf(value).strip();
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        if (normalized.length() > maxLength
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return Optional.of(normalized);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /** Runtime planning snapshot; it intentionally contains no CrewScope domain identifiers. */
    public record TaskPlanningSnapshot(
            boolean planModeActive,
            Optional<ProposedPlan> proposedPlan,
            List<TodoSnapshotItem> todos) {

        public TaskPlanningSnapshot {
            proposedPlan = Objects.requireNonNull(proposedPlan, "proposedPlan");
            todos = List.copyOf(Objects.requireNonNull(todos, "todos"));
            if (todos.size() > MAX_TODOS) {
                throw new IllegalArgumentException("Todo count exceeds the M3 limit");
            }
            long active = todos.stream()
                    .filter(todo -> todo.status() == TodoStatus.IN_PROGRESS)
                    .count();
            if (active > 1) {
                throw new IllegalArgumentException("At most one Todo may be in progress");
            }
        }
    }

    /** Normalized plan candidate. Application validation decides whether to publish PlanVersion. */
    public record ProposedPlan(Optional<String> sourcePath, String markdown, String sha256) {

        public ProposedPlan {
            sourcePath = Objects.requireNonNull(sourcePath, "sourcePath")
                    .map(path -> requireText(path, "sourcePath", 1_000));
            markdown = normalizePlan(markdown);
            String expectedHash = AgentScopeTaskPlanningSnapshotMapper.sha256(markdown);
            if (!expectedHash.equals(Objects.requireNonNull(sha256, "sha256"))) {
                throw new IllegalArgumentException("ProposedPlan SHA-256 does not match markdown");
            }
            sha256 = expectedHash;
        }
    }

    /** Safe runtime progress projection that cannot mutate CrewScope StepExecution. */
    public record TodoSnapshotItem(String content, TodoStatus status, Optional<String> priority) {

        public TodoSnapshotItem {
            content = requireText(content, "todo content", MAX_TODO_CONTENT_LENGTH);
            status = Objects.requireNonNull(status, "status");
            priority = Objects.requireNonNull(priority, "priority")
                    .map(value -> requireText(value, "todo priority", 20));
        }
    }

    public enum TodoStatus {
        PENDING,
        IN_PROGRESS,
        COMPLETED
    }
}

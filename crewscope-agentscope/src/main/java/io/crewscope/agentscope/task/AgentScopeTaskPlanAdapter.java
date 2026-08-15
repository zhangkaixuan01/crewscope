package io.crewscope.agentscope.task;

import io.crewscope.agentscope.task.AgentScopeTaskPlanningSnapshotMapper.TaskPlanningSnapshot;
import io.crewscope.agentscope.task.AgentScopeTaskPlanningSnapshotMapper.TodoSnapshotItem;
import io.crewscope.domain.task.PlanStep;
import io.crewscope.domain.task.ProposedPlan;
import io.crewscope.domain.task.TodoStatus;
import io.crewscope.domain.task.TodoSummaryItem;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Converts AgentScope Plan/Todo cognition into a still-unpublished CrewScope candidate. */
public final class AgentScopeTaskPlanAdapter {

    private static final Pattern TODO_STEP_KEY = Pattern.compile("^\\[([a-z][a-z0-9-]{0,63})]\\s+.+$");
    private final ControlledTaskPlanParser parser;

    public AgentScopeTaskPlanAdapter(ControlledTaskPlanParser parser) {
        this.parser = Objects.requireNonNull(parser, "parser");
    }

    public Candidate adapt(TaskPlanningSnapshot snapshot) {
        TaskPlanningSnapshot required = Objects.requireNonNull(snapshot, "snapshot");
        String markdown = required.proposedPlan()
                .orElseThrow(() -> new IllegalArgumentException("AgentScope has no proposed plan"))
                .markdown();
        ProposedPlan plan = parser.parse(markdown);
        Set<String> stepKeys = plan.steps().stream()
                .map(PlanStep::key)
                .collect(Collectors.toUnmodifiableSet());
        List<TodoSummaryItem> todos = required.todos().stream()
                .map(todo -> mapTodo(todo, stepKeys))
                .toList();
        return new Candidate(plan, todos);
    }

    private static TodoSummaryItem mapTodo(TodoSnapshotItem todo, Set<String> stepKeys) {
        Matcher matcher = TODO_STEP_KEY.matcher(todo.content());
        Optional<String> stepKey = matcher.matches() && stepKeys.contains(matcher.group(1))
                ? Optional.of(matcher.group(1))
                : Optional.empty();
        TodoStatus status = switch (todo.status()) {
            case PENDING -> TodoStatus.PENDING;
            case IN_PROGRESS -> TodoStatus.IN_PROGRESS;
            case COMPLETED -> TodoStatus.COMPLETED;
        };
        return new TodoSummaryItem(todo.content(), status, todo.priority(), stepKey);
    }

    /** An immutable publication input; constructing it does not change any domain fact. */
    public record Candidate(ProposedPlan plan, List<TodoSummaryItem> todos) {
        public Candidate {
            plan = Objects.requireNonNull(plan, "plan");
            todos = List.copyOf(Objects.requireNonNull(todos, "todos"));
        }
    }
}

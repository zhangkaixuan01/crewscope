package io.crewscope.infrastructure.persistence.taskruntime;

import io.crewscope.application.task.PlanVersionRepository;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.task.ExecutionCapability;
import io.crewscope.domain.task.PlanStep;
import io.crewscope.domain.task.PlanStepType;
import io.crewscope.domain.task.PlanVersion;
import io.crewscope.domain.task.PlanVersionId;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TodoStatus;
import io.crewscope.domain.task.TodoSummaryItem;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** JPA parent plus transactional JDBC children for immutable PlanVersion graphs. */
@Repository
public class JpaPlanVersionRepositoryAdapter implements PlanVersionRepository {

    private final TaskRuntimeJpaSupport support;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JpaPlanVersionRepositoryAdapter(
            TaskRuntimeJpaSupport support,
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper) {
        this.support = Objects.requireNonNull(support, "support");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    @Transactional
    public PlanVersion create(PlanVersion planVersion) {
        PlanVersion value = Objects.requireNonNull(planVersion, "planVersion");
        PlanVersionEntity row = support.mapper.toEntity(value);
        support.entityManager.persist(row);
        support.entityManager.flush();
        for (PlanStep step : value.steps()) {
            jdbc.update(
                    """
                    INSERT INTO crewscope.plan_step (
                        plan_version_id, task_execution_id, step_key, sequence, title,
                        step_type, dependency_keys, required_capabilities, required_tools, critical
                    ) VALUES (
                        :planVersionId, :executionId, :stepKey, :sequence, :title, :stepType,
                        CAST(:dependencies AS jsonb), CAST(:capabilities AS jsonb),
                        CAST(:tools AS jsonb), :critical
                    )
                    """,
                    Map.of(
                            "planVersionId", value.id().value(),
                            "executionId", value.executionId().value(),
                            "stepKey", step.key(),
                            "sequence", step.sequence(),
                            "title", step.title(),
                            "stepType", step.type().name(),
                            "dependencies", json(step.dependencyKeys().stream().sorted().toList()),
                            "capabilities", json(step.requiredCapabilities().stream()
                                    .map(Enum::name).sorted().toList()),
                            "tools", json(step.requiredTools().stream().sorted().toList()),
                            "critical", step.critical()));
        }
        int sequence = 0;
        for (TodoSummaryItem todo : value.todoSummary()) {
            java.util.HashMap<String, Object> parameters = new java.util.HashMap<>();
            parameters.put("planVersionId", value.id().value());
            parameters.put("executionId", value.executionId().value());
            parameters.put("sequence", ++sequence);
            parameters.put("content", todo.content());
            parameters.put("status", todo.status().name());
            parameters.put("priority", todo.priority().orElse(null));
            parameters.put("stepKey", todo.planStepKey().orElse(null));
            jdbc.update(
                    """
                    INSERT INTO crewscope.plan_todo_summary (
                        plan_version_id, task_execution_id, sequence, content,
                        status, priority, plan_step_key
                    ) VALUES (
                        :planVersionId, :executionId, :sequence, :content,
                        :status, :priority, :stepKey
                    )
                    """,
                    parameters);
        }
        return toDomain(row);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PlanVersion> findById(
            OrganizationId organizationId, PlanVersionId planVersionId) {
        return support.findScoped(PlanVersionEntity.class, organizationId, planVersionId.value())
                .map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PlanVersion> findByExecution(
            OrganizationId organizationId, TaskExecutionId executionId) {
        List<PlanVersionEntity> rows = support.entityManager.createQuery(
                        """
                        SELECT row FROM PlanVersionEntity row
                        WHERE row.organizationId = :organizationId
                          AND row.taskExecutionId = :executionId
                        ORDER BY row.revision
                        """,
                        PlanVersionEntity.class)
                .setParameter("organizationId", organizationId.value())
                .setParameter("executionId", executionId.value())
                .getResultList();
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<java.util.UUID, List<PlanStep>> steps = stepsByExecution(executionId);
        Map<java.util.UUID, List<TodoSummaryItem>> todos = todosByExecution(executionId);
        return rows.stream()
                .map(row -> support.mapper.toDomain(
                        row,
                        steps.getOrDefault(row.id, List.of()),
                        todos.getOrDefault(row.id, List.of())))
                .toList();
    }

    private Map<java.util.UUID, List<PlanStep>> stepsByExecution(TaskExecutionId executionId) {
        List<PlanStepRow> values = jdbc.query(
                """
                SELECT plan_version_id, step_key, sequence, title, step_type,
                       dependency_keys::text, required_capabilities::text,
                       required_tools::text, critical
                FROM crewscope.plan_step
                WHERE task_execution_id = :executionId
                ORDER BY plan_version_id, sequence
                """,
                Map.of("executionId", executionId.value()),
                (result, index) -> new PlanStepRow(
                        result.getObject("plan_version_id", java.util.UUID.class),
                        new PlanStep(
                                result.getString("step_key"), result.getInt("sequence"),
                                result.getString("title"),
                                PlanStepType.valueOf(result.getString("step_type")),
                                stringSet(result.getString("dependency_keys")),
                                stringSet(result.getString("required_capabilities")).stream()
                                        .map(ExecutionCapability::valueOf)
                                        .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                                stringSet(result.getString("required_tools")),
                                result.getBoolean("critical"))));
        Map<java.util.UUID, List<PlanStep>> grouped = new HashMap<>();
        values.forEach(value -> grouped
                .computeIfAbsent(value.planVersionId(), ignored -> new ArrayList<>())
                .add(value.step()));
        return grouped;
    }

    private Map<java.util.UUID, List<TodoSummaryItem>> todosByExecution(
            TaskExecutionId executionId) {
        List<TodoRow> values = jdbc.query(
                """
                SELECT plan_version_id, content, status, priority, plan_step_key
                FROM crewscope.plan_todo_summary
                WHERE task_execution_id = :executionId
                ORDER BY plan_version_id, sequence
                """,
                Map.of("executionId", executionId.value()),
                (result, index) -> new TodoRow(
                        result.getObject("plan_version_id", java.util.UUID.class),
                        new TodoSummaryItem(
                                result.getString("content"),
                                TodoStatus.valueOf(result.getString("status")),
                                Optional.ofNullable(result.getString("priority")),
                                Optional.ofNullable(result.getString("plan_step_key")))));
        Map<java.util.UUID, List<TodoSummaryItem>> grouped = new HashMap<>();
        values.forEach(value -> grouped
                .computeIfAbsent(value.planVersionId(), ignored -> new ArrayList<>())
                .add(value.todo()));
        return grouped;
    }

    private PlanVersion toDomain(PlanVersionEntity row) {
        List<PlanStep> steps = jdbc.query(
                """
                SELECT step_key, sequence, title, step_type,
                       dependency_keys::text, required_capabilities::text,
                       required_tools::text, critical
                FROM crewscope.plan_step
                WHERE plan_version_id = :planVersionId
                  AND task_execution_id = :executionId
                ORDER BY sequence
                """,
                Map.of("planVersionId", row.id, "executionId", row.taskExecutionId),
                (result, index) -> new PlanStep(
                        result.getString("step_key"), result.getInt("sequence"),
                        result.getString("title"), PlanStepType.valueOf(result.getString("step_type")),
                        stringSet(result.getString("dependency_keys")),
                        stringSet(result.getString("required_capabilities")).stream()
                                .map(ExecutionCapability::valueOf)
                                .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                        stringSet(result.getString("required_tools")),
                        result.getBoolean("critical")));
        List<TodoSummaryItem> todos = jdbc.query(
                """
                SELECT content, status, priority, plan_step_key
                FROM crewscope.plan_todo_summary
                WHERE plan_version_id = :planVersionId
                  AND task_execution_id = :executionId
                ORDER BY sequence
                """,
                Map.of("planVersionId", row.id, "executionId", row.taskExecutionId),
                (result, index) -> new TodoSummaryItem(
                        result.getString("content"), TodoStatus.valueOf(result.getString("status")),
                        Optional.ofNullable(result.getString("priority")),
                        Optional.ofNullable(result.getString("plan_step_key"))));
        return support.mapper.toDomain(row, steps, todos);
    }

    private String json(Object value) {
        return objectMapper.writeValueAsString(value);
    }

    @SuppressWarnings("unchecked")
    private Set<String> stringSet(String json) {
        return Set.copyOf(new LinkedHashSet<>(objectMapper.readValue(json, List.class)));
    }

    private record PlanStepRow(java.util.UUID planVersionId, PlanStep step) {}

    private record TodoRow(java.util.UUID planVersionId, TodoSummaryItem todo) {}
}

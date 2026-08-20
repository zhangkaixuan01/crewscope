package io.crewscope.infrastructure.persistence.coding;

import io.crewscope.application.coding.WorkspaceWriteBudgetContextException;
import io.crewscope.application.coding.WorkspaceWriteBudgetExceededException;
import io.crewscope.application.coding.WorkspaceWriteBudgetSnapshot;
import io.crewscope.application.coding.WorkspaceWriteBudgetStore;
import io.crewscope.domain.coding.ExecutionWorkspace;
import io.crewscope.domain.coding.ExecutionWorkspaceStatus;
import io.crewscope.domain.coding.WorkspacePolicy;
import io.crewscope.domain.task.TaskFactHash;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL pre-effect reservation ledger guarded by the current Workspace fencing epoch. */
@Repository
public class JdbcWorkspaceWriteBudgetStore implements WorkspaceWriteBudgetStore {

    private final NamedParameterJdbcTemplate jdbc;
    private final CodingPersistenceMapper mapper;

    public JdbcWorkspaceWriteBudgetStore(
            NamedParameterJdbcTemplate jdbc, CodingPersistenceMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    @Transactional
    public WorkspaceWriteBudgetSnapshot initialize(
            ExecutionWorkspace workspace,
            WorkspacePolicy policy,
            Set<String> changedPathsLowerBound,
            long writtenBytesLowerBound) {
        requireContext(workspace, policy);
        if (writtenBytesLowerBound < 0) {
            throw new IllegalArgumentException("writtenBytesLowerBound must not be negative");
        }
        requireCurrentActiveWorkspace(workspace, policy);
        jdbc.update(
                """
                INSERT INTO crewscope.workspace_write_budget_usage (
                    execution_workspace_id, workspace_policy_id, policy_hash,
                    write_operations, written_bytes, changed_paths, reservation_sequence
                ) VALUES (
                    :workspaceId, :policyId, :policyHash, 0, 0, CAST('[]' AS JSONB), 0
                ) ON CONFLICT (execution_workspace_id) DO NOTHING
                """,
                parameters(workspace, policy));
        WorkspaceWriteBudgetSnapshot current = lock(workspace, policy);
        Set<String> paths = union(current.changedPaths(), changedPathsLowerBound);
        int operations = Math.max(current.writeOperations(), paths.size());
        long bytes = Math.max(current.writtenBytes(), writtenBytesLowerBound);
        requireWithinBudget(policy, operations, bytes, paths.size());
        if (operations == current.writeOperations()
                && bytes == current.writtenBytes()
                && paths.equals(current.changedPaths())) {
            return current;
        }
        update(workspace, policy, current, operations, bytes, paths, current.reservationSequence());
        return lock(workspace, policy);
    }

    @Override
    @Transactional
    public WorkspaceWriteBudgetSnapshot reserve(
            ExecutionWorkspace workspace,
            WorkspacePolicy policy,
            Set<String> changedPaths,
            long writtenBytes) {
        requireContext(workspace, policy);
        if (writtenBytes < 0) {
            throw new IllegalArgumentException("writtenBytes must not be negative");
        }
        requireCurrentActiveWorkspace(workspace, policy);
        WorkspaceWriteBudgetSnapshot current = lock(workspace, policy);
        Set<String> paths = union(current.changedPaths(), changedPaths);
        try {
            int operations = Math.addExact(current.writeOperations(), 1);
            long bytes = Math.addExact(current.writtenBytes(), writtenBytes);
            long sequence = Math.addExact(current.reservationSequence(), 1);
            requireWithinBudget(policy, operations, bytes, paths.size());
            update(workspace, policy, current, operations, bytes, paths, sequence);
            return new WorkspaceWriteBudgetSnapshot(
                    workspace.id(), policy.policyHash(), operations, bytes, paths, sequence);
        } catch (ArithmeticException overflow) {
            throw new WorkspaceWriteBudgetExceededException();
        }
    }

    private void requireCurrentActiveWorkspace(
            ExecutionWorkspace workspace, WorkspacePolicy policy) {
        var ownership = workspace.ownership();
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                  FROM crewscope.execution_workspace workspace
                  JOIN crewscope.workspace_policy policy
                    ON policy.id = :policyId
                   AND policy.task_execution_id = workspace.task_execution_id
                   AND policy.attempt = workspace.attempt
                   AND policy.policy_hash = :policyHash
                 WHERE workspace.id = :workspaceId
                   AND workspace.status = :status
                   AND workspace.runtime_environment = :environment
                   AND workspace.runtime_id = :runtimeId
                   AND workspace.worker_id = :workerId
                   AND workspace.execution_lease_id = :leaseId
                   AND workspace.fencing_token = :fencingToken
                   AND workspace.workspace_fingerprint = :fingerprint
                """,
                parameters(workspace, policy)
                        .addValue("status", ExecutionWorkspaceStatus.ACTIVE.name())
                        .addValue("environment", ownership.environment().value())
                        .addValue("runtimeId", ownership.runtimeId().value())
                        .addValue("workerId", ownership.workerId().value())
                        .addValue("leaseId", ownership.leaseId().value())
                        .addValue("fencingToken", ownership.fencingToken().value())
                        .addValue("fingerprint", workspace.fingerprint().value()),
                Integer.class);
        if (count == null || count != 1) {
            throw new WorkspaceWriteBudgetContextException(
                    "Workspace write budget rejected stale lifecycle or fencing facts");
        }
    }

    private WorkspaceWriteBudgetSnapshot lock(
            ExecutionWorkspace workspace, WorkspacePolicy policy) {
        List<WorkspaceWriteBudgetSnapshot> rows = jdbc.query(
                """
                SELECT execution_workspace_id, policy_hash, write_operations, written_bytes,
                       changed_paths, reservation_sequence
                  FROM crewscope.workspace_write_budget_usage
                 WHERE execution_workspace_id = :workspaceId
                   AND workspace_policy_id = :policyId
                   AND policy_hash = :policyHash
                 FOR UPDATE
                """,
                parameters(workspace, policy),
                (row, ignored) -> new WorkspaceWriteBudgetSnapshot(
                        workspace.id(),
                        new TaskFactHash(row.getString("policy_hash")),
                        row.getInt("write_operations"),
                        row.getLong("written_bytes"),
                        Set.copyOf(mapper.stringList(row.getString("changed_paths"))),
                        row.getLong("reservation_sequence")));
        if (rows.size() != 1) {
            throw new WorkspaceWriteBudgetContextException(
                    "Workspace write budget is absent or belongs to another Policy");
        }
        return rows.get(0);
    }

    private void update(
            ExecutionWorkspace workspace,
            WorkspacePolicy policy,
            WorkspaceWriteBudgetSnapshot current,
            int operations,
            long bytes,
            Set<String> paths,
            long sequence) {
        int updated = jdbc.update(
                """
                UPDATE crewscope.workspace_write_budget_usage
                   SET write_operations = :operations,
                       written_bytes = :bytes,
                       changed_paths = CAST(:paths AS JSONB),
                       reservation_sequence = :sequence,
                       version = version + 1,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE execution_workspace_id = :workspaceId
                   AND workspace_policy_id = :policyId
                   AND policy_hash = :policyHash
                   AND reservation_sequence = :currentSequence
                """,
                parameters(workspace, policy)
                        .addValue("operations", operations)
                        .addValue("bytes", bytes)
                        .addValue("paths", mapper.json(paths.stream().sorted().toList()))
                        .addValue("sequence", sequence)
                        .addValue("currentSequence", current.reservationSequence()));
        if (updated != 1) {
            throw new WorkspaceWriteBudgetContextException(
                    "Workspace write budget reservation lost its current context");
        }
    }

    private static MapSqlParameterSource parameters(
            ExecutionWorkspace workspace, WorkspacePolicy policy) {
        return new MapSqlParameterSource()
                .addValue("workspaceId", workspace.id().value())
                .addValue("policyId", policy.id().value())
                .addValue("policyHash", policy.policyHash().value());
    }

    private static void requireContext(ExecutionWorkspace workspace, WorkspacePolicy policy) {
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(policy, "policy");
        if (!workspace.taskExecutionId().equals(policy.taskExecutionId())
                || workspace.attempt() != policy.attempt()
                || !workspace.codingTarget().equals(policy.codingTarget())) {
            throw new IllegalArgumentException("Workspace write budget context is inconsistent");
        }
    }

    private static void requireWithinBudget(
            WorkspacePolicy policy, int operations, long bytes, int files) {
        var budget = policy.operationBudget();
        if (operations > budget.maxWriteOperations()
                || bytes > budget.maxWrittenBytes()
                || files > budget.maxChangedFiles()) {
            throw new WorkspaceWriteBudgetExceededException();
        }
    }

    private static Set<String> union(Set<String> first, Set<String> second) {
        LinkedHashSet<String> values = new LinkedHashSet<>(first);
        values.addAll(Set.copyOf(second));
        return Set.copyOf(values);
    }
}

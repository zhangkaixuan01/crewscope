package io.crewscope.infrastructure.persistence.taskruntime;

import io.crewscope.application.runtime.RuntimeObservationQuery;
import io.crewscope.application.runtime.RuntimeObservationRepository;
import io.crewscope.application.runtime.RuntimeObservationSnapshot;
import io.crewscope.application.runtime.RuntimeWaitingExecution;
import io.crewscope.application.task.TaskRuntimeCapabilityResolver;
import io.crewscope.domain.runtime.ExecutionRuntime;
import io.crewscope.domain.runtime.ExecutionRuntimeId;
import io.crewscope.domain.runtime.RuntimeWorker;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.task.PolicySnapshot;
import io.crewscope.domain.task.TaskExecution;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Two-query JPA projection for Runtime fleet facts and Team WAITING_RUNTIME diagnostics. */
@Repository
public class JpaRuntimeObservationRepository implements RuntimeObservationRepository {

    private final EntityManager entityManager;
    private final TaskRuntimePersistenceMapper runtimeMapper;
    private final TaskRuntimeExtendedPersistenceMapper extendedMapper;

    public JpaRuntimeObservationRepository(
            EntityManager entityManager,
            TaskRuntimePersistenceMapper runtimeMapper,
            TaskRuntimeExtendedPersistenceMapper extendedMapper) {
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager");
        this.runtimeMapper = Objects.requireNonNull(runtimeMapper, "runtimeMapper");
        this.extendedMapper = Objects.requireNonNull(extendedMapper, "extendedMapper");
    }

    @Override
    @Transactional(readOnly = true)
    public RuntimeObservationSnapshot observe(RuntimeObservationQuery query) {
        RuntimeObservationQuery required = Objects.requireNonNull(query, "query");
        List<Object[]> fleetRows = entityManager.createQuery(
                        """
                        SELECT runtime, worker
                        FROM ExecutionRuntimeEntity runtime
                        LEFT JOIN RuntimeWorkerEntity worker
                          ON worker.organizationId = runtime.organizationId
                         AND worker.runtimeEnvironment = runtime.runtimeEnvironment
                         AND worker.runtimeId = runtime.id
                        WHERE runtime.organizationId = :organizationId
                          AND runtime.runtimeEnvironment = :environment
                        ORDER BY runtime.runtimeKey, runtime.id, worker.stableKey, worker.id
                        """,
                        Object[].class)
                .setParameter("organizationId", required.organizationId().value())
                .setParameter("environment", required.environment().value())
                .getResultList();

        Map<ExecutionRuntimeId, ExecutionRuntime> runtimes = new LinkedHashMap<>();
        List<RuntimeWorker> workers = new ArrayList<>();
        for (Object[] row : fleetRows) {
            ExecutionRuntime runtime = runtimeMapper.toRuntimeDomain((ExecutionRuntimeEntity) row[0]);
            runtimes.putIfAbsent(runtime.id(), runtime);
            if (row[1] instanceof RuntimeWorkerEntity worker) {
                workers.add(runtimeMapper.toWorkerDomain(worker));
            }
        }

        List<Object[]> waitingRows = entityManager.createQuery(
                        """
                        SELECT execution, policy
                        FROM TaskExecutionEntity execution, PolicySnapshotEntity policy
                        WHERE execution.organizationId = :organizationId
                          AND execution.teamId = :teamId
                          AND execution.status = 'WAITING'
                          AND execution.waitingReason = 'RUNTIME'
                          AND policy.organizationId = execution.organizationId
                          AND policy.teamId = execution.teamId
                          AND policy.workspaceId = execution.workspaceId
                          AND policy.projectId = execution.projectId
                          AND policy.taskId = execution.taskId
                          AND policy.taskExecutionId = execution.id
                          AND policy.id = execution.currentPolicySnapshotId
                        ORDER BY execution.waitingSince, execution.id
                        """,
                        Object[].class)
                .setParameter("organizationId", required.organizationId().value())
                .setParameter("teamId", required.teamId().value())
                .getResultList();
        List<RuntimeWaitingExecution> waiting = waitingRows.stream()
                .map(this::toWaitingExecution)
                .toList();
        return new RuntimeObservationSnapshot(
                List.copyOf(runtimes.values()), workers, waiting);
    }

    private RuntimeWaitingExecution toWaitingExecution(Object[] row) {
        TaskExecution execution = runtimeMapper.toExecutionDomain((TaskExecutionEntity) row[0]);
        PolicySnapshot policy = extendedMapper.toDomain((PolicySnapshotEntity) row[1]);
        var planning = execution.planningContext().orElseThrow(() -> invalidPolicy());
        if (!policy.id().equals(planning.policySnapshotId())
                || !policy.snapshotHash().equals(planning.policySnapshotHash())
                || !policy.executionId().equals(execution.id())
                || !policy.taskId().equals(execution.taskId())
                || !policy.scope().equals(execution.scope())) {
            throw invalidPolicy();
        }
        return new RuntimeWaitingExecution(
                execution, TaskRuntimeCapabilityResolver.resolve(policy));
    }

    private static DomainValidationException invalidPolicy() {
        return new DomainValidationException(
                "runtimeObservation.policySnapshot",
                "must be the current scope-closed PolicySnapshot for the waiting execution");
    }
}

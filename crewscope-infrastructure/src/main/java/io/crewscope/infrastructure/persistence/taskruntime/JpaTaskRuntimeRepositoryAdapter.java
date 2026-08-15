package io.crewscope.infrastructure.persistence.taskruntime;

import io.crewscope.application.runtime.ExecutionRuntimeRepository;
import io.crewscope.application.runtime.RuntimeWorkerRepository;
import io.crewscope.application.task.ConversationTaskLinkRepository;
import io.crewscope.application.task.TaskExecutionRepository;
import io.crewscope.application.task.TaskRepository;
import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.runtime.ExecutionRuntime;
import io.crewscope.domain.runtime.ExecutionRuntimeId;
import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.runtime.RuntimeWorker;
import io.crewscope.domain.runtime.RuntimeWorkerId;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.AggregateId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.task.ConversationTaskLink;
import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.task.TaskResponsibilitySnapshotEntry;
import io.crewscope.domain.workitem.WorkItemId;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JPA adapter for mutable M3 Task, TaskExecution, Runtime and Worker facts.
 *
 * <p>Every read carries an Organization predicate. Mutable writes load the committed row, compare
 * the domain's previous version and let JPA increment the mapped {@code @Version} exactly once.
 */
@Repository
public class JpaTaskRuntimeRepositoryAdapter implements
        TaskRepository,
        TaskExecutionRepository,
        ConversationTaskLinkRepository,
        ExecutionRuntimeRepository,
        RuntimeWorkerRepository {

    private final EntityManager entityManager;
    private final TaskRuntimePersistenceMapper mapper;

    public JpaTaskRuntimeRepositoryAdapter(
            EntityManager entityManager, TaskRuntimePersistenceMapper mapper) {
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    @Transactional
    public Task create(Task task) {
        Task required = requireNew(task, "task.version");
        TaskResponsibilitySnapshotEntity snapshot = mapper.toSnapshotEntity(required);
        entityManager.persist(snapshot);
        entityManager.flush();
        persistResponsibilityEntries(snapshot.id, required);
        TaskEntity row = mapper.toTaskEntity(required);
        entityManager.persist(row);
        entityManager.flush();
        return toTaskDomain(row);
    }

    @Override
    @Transactional
    public Task update(Task task) {
        Task required = Objects.requireNonNull(task, "task");
        long expected = expectedVersion(required.version(), "task.version");
        TaskEntity row = findTaskEntity(required.scope().organizationId(), required.id())
                .orElseThrow(() -> new AggregateNotFoundException("Task", required.id()));
        requireVersion("Task", required.id(), expected, row.version);
        mapper.copyTaskState(row, required);
        entityManager.flush();
        return toTaskDomain(row);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Task> findById(OrganizationId organizationId, TaskId taskId) {
        return findTaskEntity(required(organizationId), required(taskId)).map(this::toTaskDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Task> findByWorkItem(OrganizationId organizationId, WorkItemId workItemId) {
        return entityManager.createQuery(
                        """
                        SELECT task FROM TaskEntity task
                        WHERE task.organizationId = :organizationId
                          AND task.workItemId = :workItemId
                        ORDER BY task.createdAt, task.id
                        """,
                        TaskEntity.class)
                .setParameter("organizationId", required(organizationId).value())
                .setParameter("workItemId", required(workItemId).value())
                .getResultList().stream().map(this::toTaskDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Task> findByConversation(
            OrganizationId organizationId, ConversationId conversationId) {
        return entityManager.createQuery(
                        """
                        SELECT task FROM TaskEntity task, ConversationTaskLinkEntity link
                        WHERE task.organizationId = :organizationId
                          AND link.organizationId = task.organizationId
                          AND link.taskId = task.id
                          AND link.conversationId = :conversationId
                        ORDER BY task.createdAt, task.id
                        """,
                        TaskEntity.class)
                .setParameter("organizationId", required(organizationId).value())
                .setParameter("conversationId", required(conversationId).value())
                .getResultList().stream().map(this::toTaskDomain).toList();
    }

    @Override
    @Transactional
    public TaskExecution create(TaskExecution execution) {
        TaskExecution required = requireNew(execution, "taskExecution.version");
        TaskExecutionEntity row = mapper.toExecutionEntity(required);
        entityManager.persist(row);
        entityManager.flush();
        return mapper.toExecutionDomain(row);
    }

    @Override
    @Transactional
    public TaskExecution update(TaskExecution execution) {
        TaskExecution required = Objects.requireNonNull(execution, "execution");
        long expected = expectedVersion(required.version(), "taskExecution.version");
        TaskExecutionEntity row = findExecutionEntity(
                        required.scope().organizationId(), required.id())
                .orElseThrow(() -> new AggregateNotFoundException(
                        "TaskExecution", required.id()));
        requireVersion("TaskExecution", required.id(), expected, row.version);
        mapper.copyExecutionState(row, required);
        entityManager.flush();
        return mapper.toExecutionDomain(row);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TaskExecution> findById(
            OrganizationId organizationId, TaskExecutionId executionId) {
        return findExecutionEntity(required(organizationId), required(executionId))
                .map(mapper::toExecutionDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TaskExecution> findByTask(OrganizationId organizationId, TaskId taskId) {
        return entityManager.createQuery(
                        """
                        SELECT execution FROM TaskExecutionEntity execution
                        WHERE execution.organizationId = :organizationId
                          AND execution.taskId = :taskId
                        ORDER BY execution.attempt
                        """,
                        TaskExecutionEntity.class)
                .setParameter("organizationId", required(organizationId).value())
                .setParameter("taskId", required(taskId).value())
                .getResultList().stream().map(mapper::toExecutionDomain).toList();
    }

    @Override
    @Transactional
    public List<TaskExecution> findRecoveringForUpdate(
            OrganizationId organizationId, int limit) {
        if (limit < 1 || limit > 10_000) {
            throw new IllegalArgumentException("limit must be between 1 and 10000");
        }
        return entityManager.createQuery(
                        """
                        SELECT execution FROM TaskExecutionEntity execution
                        WHERE execution.organizationId = :organizationId
                          AND execution.status = 'RECOVERING'
                        ORDER BY execution.updatedAt, execution.id
                        """,
                        TaskExecutionEntity.class)
                .setParameter("organizationId", required(organizationId).value())
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .setMaxResults(limit)
                .getResultList().stream().map(mapper::toExecutionDomain).toList();
    }

    @Override
    @Transactional
    public ConversationTaskLink create(ConversationTaskLink link) {
        ConversationTaskLink required = Objects.requireNonNull(link, "link");
        ConversationTaskLinkEntity row = mapper.toLinkEntity(required);
        entityManager.persist(row);
        entityManager.flush();
        return mapper.toLinkDomain(row);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ConversationTaskLink> find(
            OrganizationId organizationId, ConversationId conversationId, TaskId taskId) {
        return entityManager.createQuery(
                        """
                        SELECT link FROM ConversationTaskLinkEntity link
                        WHERE link.organizationId = :organizationId
                          AND link.conversationId = :conversationId
                          AND link.taskId = :taskId
                        """,
                        ConversationTaskLinkEntity.class)
                .setParameter("organizationId", required(organizationId).value())
                .setParameter("conversationId", required(conversationId).value())
                .setParameter("taskId", required(taskId).value())
                .getResultStream().findFirst().map(mapper::toLinkDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConversationTaskLink> findLinksByConversation(
            OrganizationId organizationId, ConversationId conversationId) {
        return findLinks(
                required(organizationId), "link.conversationId", required(conversationId).value());
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConversationTaskLink> findLinksByTask(
            OrganizationId organizationId, TaskId taskId) {
        return findLinks(required(organizationId), "link.taskId", required(taskId).value());
    }

    @Override
    @Transactional
    public ExecutionRuntime create(ExecutionRuntime runtime) {
        ExecutionRuntime required = requireNew(runtime, "executionRuntime.version");
        ExecutionRuntimeEntity row = mapper.toRuntimeEntity(required);
        entityManager.persist(row);
        entityManager.flush();
        return mapper.toRuntimeDomain(row);
    }

    @Override
    @Transactional
    public ExecutionRuntime update(ExecutionRuntime runtime) {
        ExecutionRuntime required = Objects.requireNonNull(runtime, "runtime");
        long expected = expectedVersion(required.version(), "executionRuntime.version");
        ExecutionRuntimeEntity row = findRuntimeEntity(
                        required.organizationId(), required.environment(), required.id())
                .orElseThrow(() -> new AggregateNotFoundException(
                        "ExecutionRuntime", required.id()));
        requireVersion("ExecutionRuntime", required.id(), expected, row.version);
        mapper.copyRuntimeState(row, required);
        entityManager.flush();
        return mapper.toRuntimeDomain(row);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ExecutionRuntime> findById(
            OrganizationId organizationId,
            RuntimeEnvironment environment,
            ExecutionRuntimeId runtimeId) {
        return findRuntimeEntity(required(organizationId), required(environment), required(runtimeId))
                .map(mapper::toRuntimeDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ExecutionRuntime> findByKey(
            OrganizationId organizationId, RuntimeEnvironment environment, String runtimeKey) {
        return entityManager.createQuery(
                        """
                        SELECT runtime FROM ExecutionRuntimeEntity runtime
                        WHERE runtime.organizationId = :organizationId
                          AND runtime.runtimeEnvironment = :environment
                          AND runtime.runtimeKey = :runtimeKey
                        """,
                        ExecutionRuntimeEntity.class)
                .setParameter("organizationId", required(organizationId).value())
                .setParameter("environment", required(environment).value())
                .setParameter("runtimeKey", Objects.requireNonNull(runtimeKey, "runtimeKey"))
                .getResultStream().findFirst().map(mapper::toRuntimeDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ExecutionRuntime> findByEnvironment(
            OrganizationId organizationId, RuntimeEnvironment environment) {
        return entityManager.createQuery(
                        """
                        SELECT runtime FROM ExecutionRuntimeEntity runtime
                        WHERE runtime.organizationId = :organizationId
                          AND runtime.runtimeEnvironment = :environment
                        ORDER BY runtime.runtimeKey, runtime.id
                        """,
                        ExecutionRuntimeEntity.class)
                .setParameter("organizationId", required(organizationId).value())
                .setParameter("environment", required(environment).value())
                .getResultList().stream().map(mapper::toRuntimeDomain).toList();
    }

    @Override
    @Transactional
    public RuntimeWorker create(RuntimeWorker worker) {
        RuntimeWorker required = requireNew(worker, "runtimeWorker.version");
        RuntimeWorkerEntity row = mapper.toWorkerEntity(required);
        entityManager.persist(row);
        entityManager.flush();
        return mapper.toWorkerDomain(row);
    }

    @Override
    @Transactional
    public RuntimeWorker update(RuntimeWorker worker) {
        RuntimeWorker required = Objects.requireNonNull(worker, "worker");
        long expected = expectedVersion(required.version(), "runtimeWorker.version");
        RuntimeWorkerEntity row = findWorkerEntity(
                        required.organizationId(), required.environment(), required.id())
                .orElseThrow(() -> new AggregateNotFoundException("RuntimeWorker", required.id()));
        requireVersion("RuntimeWorker", required.id(), expected, row.version);
        mapper.copyWorkerState(row, required);
        entityManager.flush();
        return mapper.toWorkerDomain(row);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RuntimeWorker> findById(
            OrganizationId organizationId,
            RuntimeEnvironment environment,
            RuntimeWorkerId workerId) {
        return findWorkerEntity(required(organizationId), required(environment), required(workerId))
                .map(mapper::toWorkerDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RuntimeWorker> findByStableKey(
            OrganizationId organizationId,
            RuntimeEnvironment environment,
            ExecutionRuntimeId runtimeId,
            String stableKey) {
        return entityManager.createQuery(
                        """
                        SELECT worker FROM RuntimeWorkerEntity worker
                        WHERE worker.organizationId = :organizationId
                          AND worker.runtimeEnvironment = :environment
                          AND worker.runtimeId = :runtimeId
                          AND worker.stableKey = :stableKey
                        """,
                        RuntimeWorkerEntity.class)
                .setParameter("organizationId", required(organizationId).value())
                .setParameter("environment", required(environment).value())
                .setParameter("runtimeId", required(runtimeId).value())
                .setParameter("stableKey", Objects.requireNonNull(stableKey, "stableKey"))
                .getResultStream().findFirst().map(mapper::toWorkerDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RuntimeWorker> findByRuntime(
            OrganizationId organizationId,
            RuntimeEnvironment environment,
            ExecutionRuntimeId runtimeId) {
        return entityManager.createQuery(
                        """
                        SELECT worker FROM RuntimeWorkerEntity worker
                        WHERE worker.organizationId = :organizationId
                          AND worker.runtimeEnvironment = :environment
                          AND worker.runtimeId = :runtimeId
                        ORDER BY worker.stableKey, worker.id
                        """,
                        RuntimeWorkerEntity.class)
                .setParameter("organizationId", required(organizationId).value())
                .setParameter("environment", required(environment).value())
                .setParameter("runtimeId", required(runtimeId).value())
                .getResultList().stream().map(mapper::toWorkerDomain).toList();
    }

    private void persistResponsibilityEntries(UUID snapshotId, Task task) {
        for (TaskResponsibilitySnapshotEntry entry : task.responsibilitySnapshot().entries()) {
            entityManager.createNativeQuery(
                            """
                            INSERT INTO crewscope.task_responsibility_snapshot_entry (
                                snapshot_id, organization_id, team_id, workspace_id, project_id,
                                work_item_id, assignment_id, assignment_version, role,
                                principal_id, principal_type, member_id, assigned_at, accepted_at
                            ) VALUES (
                                :snapshotId, :organizationId, :teamId, :workspaceId, :projectId,
                                :workItemId, :assignmentId, :assignmentVersion, :role,
                                :principalId, :principalType, :memberId, :assignedAt, :acceptedAt
                            )
                            """)
                    .setParameter("snapshotId", snapshotId)
                    .setParameter("organizationId", task.scope().organizationId().value())
                    .setParameter("teamId", task.scope().teamId().value())
                    .setParameter("workspaceId", task.scope().workspaceId().value())
                    .setParameter("projectId", task.scope().projectId().value())
                    .setParameter("workItemId", task.workItemId().value())
                    .setParameter("assignmentId", entry.assignmentId().value())
                    .setParameter("assignmentVersion", entry.assignmentVersion())
                    .setParameter("role", entry.role().name())
                    .setParameter("principalId", entry.principalId().value())
                    .setParameter("principalType", entry.principalType().name())
                    .setParameter("memberId", entry.memberId().map(value -> value.value()).orElse(null))
                    .setParameter("assignedAt", entry.assignedAt().value())
                    .setParameter("acceptedAt", entry.acceptedAt().value())
                    .executeUpdate();
        }
    }

    private Task toTaskDomain(TaskEntity row) {
        TaskResponsibilitySnapshotEntity snapshot = entityManager.find(
                TaskResponsibilitySnapshotEntity.class, row.responsibilitySnapshotId);
        if (snapshot == null || !snapshot.organizationId.equals(row.organizationId)) {
            throw new IllegalStateException("Task responsibility snapshot is missing or cross-scope");
        }
        @SuppressWarnings("unchecked")
        List<Object[]> values = entityManager.createNativeQuery(
                        """
                        SELECT assignment_id, assignment_version, role, principal_id,
                               principal_type, member_id, assigned_at, accepted_at
                        FROM crewscope.task_responsibility_snapshot_entry
                        WHERE snapshot_id = :snapshotId
                        ORDER BY role, assignment_id
                        """)
                .setParameter("snapshotId", snapshot.id)
                .getResultList();
        List<TaskResponsibilitySnapshotEntry> entries = values.stream()
                .map(mapper::responsibilityEntry)
                .toList();
        return mapper.toTaskDomain(row, snapshot, entries);
    }

    private List<ConversationTaskLink> findLinks(
            OrganizationId organizationId, String field, UUID value) {
        return entityManager.createQuery(
                        "SELECT link FROM ConversationTaskLinkEntity link "
                                + "WHERE link.organizationId = :organizationId AND "
                                + field + " = :value ORDER BY link.createdAt, link.id",
                        ConversationTaskLinkEntity.class)
                .setParameter("organizationId", organizationId.value())
                .setParameter("value", value)
                .getResultList().stream().map(mapper::toLinkDomain).toList();
    }

    private Optional<TaskEntity> findTaskEntity(OrganizationId organizationId, TaskId taskId) {
        return findOne(TaskEntity.class, organizationId.value(), taskId.value());
    }

    private Optional<TaskExecutionEntity> findExecutionEntity(
            OrganizationId organizationId, TaskExecutionId executionId) {
        return findOne(TaskExecutionEntity.class, organizationId.value(), executionId.value());
    }

    private Optional<ExecutionRuntimeEntity> findRuntimeEntity(
            OrganizationId organizationId,
            RuntimeEnvironment environment,
            ExecutionRuntimeId runtimeId) {
        return entityManager.createQuery(
                        """
                        SELECT runtime FROM ExecutionRuntimeEntity runtime
                        WHERE runtime.organizationId = :organizationId
                          AND runtime.runtimeEnvironment = :environment
                          AND runtime.id = :id
                        """,
                        ExecutionRuntimeEntity.class)
                .setParameter("organizationId", organizationId.value())
                .setParameter("environment", environment.value())
                .setParameter("id", runtimeId.value())
                .getResultStream().findFirst();
    }

    private Optional<RuntimeWorkerEntity> findWorkerEntity(
            OrganizationId organizationId,
            RuntimeEnvironment environment,
            RuntimeWorkerId workerId) {
        return entityManager.createQuery(
                        """
                        SELECT worker FROM RuntimeWorkerEntity worker
                        WHERE worker.organizationId = :organizationId
                          AND worker.runtimeEnvironment = :environment
                          AND worker.id = :id
                        """,
                        RuntimeWorkerEntity.class)
                .setParameter("organizationId", organizationId.value())
                .setParameter("environment", environment.value())
                .setParameter("id", workerId.value())
                .getResultStream().findFirst();
    }

    private <T> Optional<T> findOne(Class<T> entityType, UUID organizationId, UUID id) {
        String name = entityType.getSimpleName();
        return entityManager.createQuery(
                        "SELECT row FROM " + name + " row "
                                + "WHERE row.organizationId = :organizationId AND row.id = :id",
                        entityType)
                .setParameter("organizationId", organizationId)
                .setParameter("id", id)
                .getResultStream().findFirst();
    }

    private static long expectedVersion(long committedVersion, String field) {
        if (committedVersion < 1) {
            throw new DomainValidationException(field, "must contain one uncommitted mutation");
        }
        return committedVersion - 1;
    }

    private static void requireVersion(
            String aggregate, AggregateId id, long expected, long actual) {
        if (actual != expected) {
            throw new OptimisticLockConflictException(aggregate, id, expected, actual);
        }
    }

    private static <T> T requireNew(T value, String field) {
        Objects.requireNonNull(value, field);
        long version;
        if (value instanceof Task task) version = task.version();
        else if (value instanceof TaskExecution execution) version = execution.version();
        else if (value instanceof ExecutionRuntime runtime) version = runtime.version();
        else if (value instanceof RuntimeWorker worker) version = worker.version();
        else throw new IllegalArgumentException("Unsupported aggregate: " + value.getClass());
        if (version != 0) {
            throw new DomainValidationException(field, "must be zero when created");
        }
        return value;
    }

    private static <T> T required(T value) {
        return Objects.requireNonNull(value, "value");
    }
}

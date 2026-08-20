package io.crewscope.application.coding;

import io.crewscope.application.artifact.ArtifactAccessContext;
import io.crewscope.application.task.TaskExecutionRepository;
import io.crewscope.application.task.TaskRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.domain.coding.CommandEvidence;
import io.crewscope.domain.coding.CommandEvidenceId;
import io.crewscope.domain.coding.DiffArtifact;
import io.crewscope.domain.coding.TestEvidence;
import io.crewscope.domain.coding.TestEvidenceId;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskId;
import java.util.Objects;
import java.util.Set;

/** Authorizes Task-scoped Patch, build-log and test-report content reads. */
public final class CodingArtifactAccessService {

    private final WorkItemAccessPolicy accessPolicy;
    private final TaskRepository taskRepository;
    private final TaskExecutionRepository executionRepository;
    private final DiffArtifactRepository diffArtifactRepository;
    private final CommandEvidenceRepository commandEvidenceRepository;
    private final TestEvidenceRepository testEvidenceRepository;
    private final CodingArtifactContentPort contentPort;
    private final TransactionExecutor transactionExecutor;

    public CodingArtifactAccessService(
            WorkItemAccessPolicy accessPolicy,
            TaskRepository taskRepository,
            TaskExecutionRepository executionRepository,
            DiffArtifactRepository diffArtifactRepository,
            CommandEvidenceRepository commandEvidenceRepository,
            TestEvidenceRepository testEvidenceRepository,
            CodingArtifactContentPort contentPort,
            TransactionExecutor transactionExecutor) {
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
        this.taskRepository = Objects.requireNonNull(taskRepository, "taskRepository");
        this.executionRepository = Objects.requireNonNull(executionRepository, "executionRepository");
        this.diffArtifactRepository = Objects.requireNonNull(diffArtifactRepository, "diffArtifactRepository");
        this.commandEvidenceRepository = Objects.requireNonNull(commandEvidenceRepository, "commandEvidenceRepository");
        this.testEvidenceRepository = Objects.requireNonNull(testEvidenceRepository, "testEvidenceRepository");
        this.contentPort = Objects.requireNonNull(contentPort, "contentPort");
        this.transactionExecutor = Objects.requireNonNull(transactionExecutor, "transactionExecutor");
    }

    public CodingArtifactContent openPatch(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            TaskId taskId,
            TaskExecutionId executionId,
            CodingArtifactRangeSelection selection) {
        return transactionExecutor.required(() -> {
            AuthorizedAttempt attempt = authorize(context, organizationId, teamId, taskId, executionId);
            DiffArtifact artifact = diffArtifactRepository
                    .findByTaskExecution(
                            organizationId,
                            teamId,
                            attempt.task().scope().projectId(),
                            executionId)
                    .filter(value -> belongsTo(value, attempt))
                    .orElseThrow(() -> new AggregateNotFoundException("DiffArtifact", executionId));
            return contentPort.readPatch(
                    artifact,
                    artifactAccess(context, attempt.task()),
                    requireSelection(selection).resolve(artifact.patchArtifact().sizeBytes()));
        });
    }

    public CodingArtifactContent openBuildLog(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            TaskId taskId,
            TaskExecutionId executionId,
            CommandEvidenceId evidenceId,
            CodingArtifactRangeSelection selection) {
        return transactionExecutor.required(() -> {
            AuthorizedAttempt attempt = authorize(context, organizationId, teamId, taskId, executionId);
            CommandEvidence evidence = commandEvidenceRepository
                    .findById(
                            organizationId,
                            teamId,
                            attempt.task().scope().projectId(),
                            Objects.requireNonNull(evidenceId, "evidenceId"))
                    .filter(value -> belongsTo(value, attempt))
                    .orElseThrow(() -> new AggregateNotFoundException("CommandEvidence", evidenceId));
            return contentPort.readBuildLog(
                    evidence,
                    artifactAccess(context, attempt.task()),
                    requireSelection(selection).resolve(evidence.commandLog().sizeBytes()));
        });
    }

    public CodingArtifactContent openTestReport(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            TaskId taskId,
            TaskExecutionId executionId,
            TestEvidenceId evidenceId,
            CodingArtifactRangeSelection selection) {
        return transactionExecutor.required(() -> {
            AuthorizedAttempt attempt = authorize(context, organizationId, teamId, taskId, executionId);
            TestEvidence evidence = testEvidenceRepository
                    .findById(
                            organizationId,
                            teamId,
                            attempt.task().scope().projectId(),
                            Objects.requireNonNull(evidenceId, "evidenceId"))
                    .filter(value -> belongsTo(value, attempt))
                    .orElseThrow(() -> new AggregateNotFoundException("TestEvidence", evidenceId));
            long size = evidence.testReport()
                    .orElseThrow(() -> new AggregateNotFoundException("TestReport", evidenceId))
                    .sizeBytes();
            return contentPort.readTestReport(
                    evidence,
                    artifactAccess(context, attempt.task()),
                    requireSelection(selection).resolve(size));
        });
    }

    private AuthorizedAttempt authorize(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            TaskId taskId,
            TaskExecutionId executionId) {
        TeamAccessContext access = Objects.requireNonNull(context, "context");
        accessPolicy.requireVisibleTeam(access, organizationId, teamId);
        Task task = taskRepository.findById(organizationId, taskId)
                .filter(value -> value.scope().teamId().equals(teamId))
                .orElseThrow(() -> new AggregateNotFoundException("Task", taskId));
        TaskExecution execution = executionRepository.findById(organizationId, executionId)
                .filter(value -> value.taskId().equals(task.id()) && value.scope().equals(task.scope()))
                .orElseThrow(() -> new AggregateNotFoundException("TaskExecution", executionId));
        return new AuthorizedAttempt(task, execution);
    }

    private static ArtifactAccessContext artifactAccess(TeamAccessContext context, Task task) {
        return new ArtifactAccessContext(
                task.scope().organizationId(),
                context.actor().id(),
                Set.of(task.scope().teamId()),
                Set.of(task.scope().workspaceId()));
    }

    private static boolean belongsTo(DiffArtifact artifact, AuthorizedAttempt attempt) {
        return artifact.scope().equals(attempt.task().scope())
                && artifact.taskId().equals(attempt.task().id())
                && artifact.taskExecutionId().equals(attempt.execution().id());
    }

    private static boolean belongsTo(CommandEvidence evidence, AuthorizedAttempt attempt) {
        return evidence.scope().equals(attempt.task().scope())
                && evidence.taskId().equals(attempt.task().id())
                && evidence.taskExecutionId().equals(attempt.execution().id());
    }

    private static boolean belongsTo(TestEvidence evidence, AuthorizedAttempt attempt) {
        return evidence.scope().equals(attempt.task().scope())
                && evidence.taskId().equals(attempt.task().id())
                && evidence.taskExecutionId().equals(attempt.execution().id());
    }

    private static CodingArtifactRangeSelection requireSelection(CodingArtifactRangeSelection selection) {
        return Objects.requireNonNull(selection, "selection");
    }

    private record AuthorizedAttempt(Task task, TaskExecution execution) {}
}

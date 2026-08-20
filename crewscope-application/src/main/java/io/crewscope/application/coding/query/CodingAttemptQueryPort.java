package io.crewscope.application.coding.query;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.List;
import java.util.Optional;

/** Read-model Port kept separate from aggregate repositories and optimized for public DTOs. */
public interface CodingAttemptQueryPort {

    List<CodingAttemptProjection> findByTask(
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId projectId,
            TaskId taskId);

    Optional<CodingAttemptProjection> findByExecution(
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId projectId,
            TaskId taskId,
            TaskExecutionId executionId);

    CodingEvidencePage<CommandEvidenceProjection> findCommands(
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId projectId,
            TaskId taskId,
            TaskExecutionId executionId,
            Optional<CodingEvidenceCursor> cursor,
            int limit);

    CodingEvidencePage<TestEvidenceProjection> findTestEvidence(
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId projectId,
            TaskId taskId,
            TaskExecutionId executionId,
            Optional<CodingEvidenceCursor> cursor,
            int limit);
}

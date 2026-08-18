package io.crewscope.application.coding;

import io.crewscope.domain.coding.CommandEvidence;
import io.crewscope.domain.coding.CommandEvidenceId;
import io.crewscope.domain.coding.CommandEvidenceSequenceConflictException;
import io.crewscope.domain.coding.ExecutionWorkspaceId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.List;
import java.util.Optional;

/** Persistence Port for immutable command evidence ordered within an ExecutionWorkspace. */
public interface CommandEvidenceRepository {

    /** Atomically enforces Workspace plus EvidenceSequence uniqueness. */
    CommandEvidence create(CommandEvidence evidence) throws CommandEvidenceSequenceConflictException;

    Optional<CommandEvidence> findById(
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId workProjectId,
            CommandEvidenceId evidenceId);

    /** Returns evidence in strictly increasing sequence order. */
    List<CommandEvidence> findByTaskExecution(
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId workProjectId,
            TaskExecutionId taskExecutionId);

    /** Returns evidence in strictly increasing sequence order. */
    List<CommandEvidence> findByWorkspace(
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId workProjectId,
            ExecutionWorkspaceId workspaceId);
}

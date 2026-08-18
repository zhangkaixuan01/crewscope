package io.crewscope.application.coding;

import io.crewscope.domain.coding.ExecutionWorkspaceId;
import io.crewscope.domain.coding.TestEvidence;
import io.crewscope.domain.coding.TestEvidenceId;
import io.crewscope.domain.coding.TestEvidenceSequenceConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.List;
import java.util.Optional;

/** Persistence Port for immutable test and acceptance evidence publications. */
public interface TestEvidenceRepository {

    /** Atomically enforces Workspace plus EvidenceSequence uniqueness. */
    TestEvidence create(TestEvidence evidence) throws TestEvidenceSequenceConflictException;

    Optional<TestEvidence> findById(
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId workProjectId,
            TestEvidenceId evidenceId);

    /** Returns publications in strictly increasing sequence order. */
    List<TestEvidence> findByTaskExecution(
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId workProjectId,
            TaskExecutionId taskExecutionId);

    /** Returns publications in strictly increasing sequence order. */
    List<TestEvidence> findByWorkspace(
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId workProjectId,
            ExecutionWorkspaceId workspaceId);
}

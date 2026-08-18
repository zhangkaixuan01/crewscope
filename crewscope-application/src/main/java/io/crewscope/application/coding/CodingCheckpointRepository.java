package io.crewscope.application.coding;

import io.crewscope.domain.coding.CodingCheckpoint;
import io.crewscope.domain.coding.CodingCheckpointId;
import io.crewscope.domain.coding.CodingCheckpointSequenceConflictException;
import io.crewscope.domain.coding.ExecutionWorkspaceId;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.Optional;

/** Scope-bound persistence Port for immutable monotonic CodingCheckpoint metadata. */
public interface CodingCheckpointRepository {

    /** Atomically enforces Workspace plus CheckpointSequence uniqueness. */
    CodingCheckpoint append(CodingCheckpoint checkpoint)
            throws CodingCheckpointSequenceConflictException;

    Optional<CodingCheckpoint> findById(
            OrganizationId organizationId, CodingCheckpointId checkpointId);

    Optional<CodingCheckpoint> findLatestByWorkspace(
            OrganizationId organizationId, ExecutionWorkspaceId executionWorkspaceId);
}

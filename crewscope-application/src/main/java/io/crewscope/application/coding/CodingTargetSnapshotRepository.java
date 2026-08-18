package io.crewscope.application.coding;

import io.crewscope.domain.coding.CodingTargetSnapshot;
import io.crewscope.domain.coding.CodingTargetSnapshotId;
import io.crewscope.domain.coding.CodingTargetSnapshotRevisionConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.List;
import java.util.Optional;

/** Persistence Port for optional immutable CodingTargetSnapshot revisions attached to a Task. */
public interface CodingTargetSnapshotRepository {

    /**
     * Inserts one immutable revision and atomically rejects a duplicate Task revision with
     * {@link CodingTargetSnapshotRevisionConflictException}.
     */
    CodingTargetSnapshot create(CodingTargetSnapshot snapshot);

    Optional<CodingTargetSnapshot> findById(
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId workProjectId,
            CodingTargetSnapshotId snapshotId);

    /** Empty means the Task is a compatible non-Coding Task. */
    Optional<CodingTargetSnapshot> findLatestByTask(
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId workProjectId,
            TaskId taskId);

    List<CodingTargetSnapshot> findByTask(
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId workProjectId,
            TaskId taskId);
}

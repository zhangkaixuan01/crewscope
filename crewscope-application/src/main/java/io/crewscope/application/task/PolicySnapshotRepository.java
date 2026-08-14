package io.crewscope.application.task;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.task.PolicySnapshot;
import io.crewscope.domain.task.PolicySnapshotId;
import io.crewscope.domain.task.TaskExecutionId;
import java.util.List;
import java.util.Optional;

/** Persistence Port for immutable TaskExecution policy snapshot revisions. */
public interface PolicySnapshotRepository {
    PolicySnapshot create(PolicySnapshot snapshot);

    Optional<PolicySnapshot> findById(
            OrganizationId organizationId, PolicySnapshotId snapshotId);

    List<PolicySnapshot> findByExecution(
            OrganizationId organizationId, TaskExecutionId executionId);
}

package io.crewscope.application.coding;

import io.crewscope.domain.coding.WorkspacePolicy;
import io.crewscope.domain.coding.WorkspacePolicyId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.Optional;

/** Persistence Port for one immutable WorkspacePolicy per TaskExecution attempt. */
public interface WorkspacePolicyRepository {

    WorkspacePolicy create(WorkspacePolicy policy);

    Optional<WorkspacePolicy> findById(
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId workProjectId,
            WorkspacePolicyId policyId);

    Optional<WorkspacePolicy> findByTaskExecution(
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId workProjectId,
            TaskExecutionId taskExecutionId);
}

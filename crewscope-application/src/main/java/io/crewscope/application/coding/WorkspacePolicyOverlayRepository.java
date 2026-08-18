package io.crewscope.application.coding;

import io.crewscope.domain.coding.WorkspacePolicyId;
import io.crewscope.domain.coding.WorkspacePolicyOverlay;
import io.crewscope.domain.coding.WorkspacePolicyOverlayId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.Optional;

/** Append-only runtime overlay Port with explicit current-hash compare-and-set. */
public interface WorkspacePolicyOverlayRepository {

    WorkspacePolicyOverlay create(WorkspacePolicyOverlay overlay);

    WorkspacePolicyOverlay appendSuccessor(
            WorkspacePolicyOverlay overlay, TaskFactHash expectedCurrentOverlayHash);

    Optional<WorkspacePolicyOverlay> findCurrentByPolicy(
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId workProjectId,
            WorkspacePolicyId policyId);

    Optional<WorkspacePolicyOverlay> findByIdAndVersion(
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId workProjectId,
            WorkspacePolicyOverlayId overlayId,
            long version);
}

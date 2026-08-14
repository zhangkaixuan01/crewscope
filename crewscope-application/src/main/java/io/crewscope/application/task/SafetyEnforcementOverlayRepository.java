package io.crewscope.application.task;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.task.SafetyEnforcementOverlay;
import io.crewscope.domain.task.SafetyEnforcementOverlayId;
import io.crewscope.domain.task.TaskExecutionId;
import java.util.List;
import java.util.Optional;

/** Persistence Port for append-only real-time safety restriction versions. */
public interface SafetyEnforcementOverlayRepository {
    SafetyEnforcementOverlay create(SafetyEnforcementOverlay overlay);

    Optional<SafetyEnforcementOverlay> findByIdAndVersion(
            OrganizationId organizationId, SafetyEnforcementOverlayId overlayId, long version);

    List<SafetyEnforcementOverlay> findByExecution(
            OrganizationId organizationId, TaskExecutionId executionId);
}

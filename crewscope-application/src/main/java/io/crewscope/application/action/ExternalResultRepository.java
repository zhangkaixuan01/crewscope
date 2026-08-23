package io.crewscope.application.action;

import io.crewscope.domain.action.ExternalResult;
import io.crewscope.domain.action.ExternalResultId;
import io.crewscope.domain.action.ExternalResultIdentity;
import io.crewscope.domain.action.PlannedActionId;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.Optional;

/** Persistence Port for the single merged state of each Connection-scoped external object. */
public interface ExternalResultRepository {

    ExternalResult insert(ExternalResult result);

    ExternalResult update(ExternalResult result);

    Optional<ExternalResult> findById(OrganizationId organizationId, ExternalResultId id);

    Optional<ExternalResult> findByIdentity(
            OrganizationId organizationId, ExternalResultIdentity identity);

    Optional<ExternalResult> findByAction(
            OrganizationId organizationId, PlannedActionId actionId);
}

package io.crewscope.application.action;

import io.crewscope.domain.action.ActionBundleId;
import io.crewscope.domain.action.ActionDispatch;
import io.crewscope.domain.action.ActionDispatchId;
import io.crewscope.domain.action.PlannedActionId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.List;
import java.util.Optional;

/** Durable queue Port; all claims and updates use database locks and optimistic versions. */
public interface ActionDispatchRepository {

    List<ActionDispatch> insertAll(List<ActionDispatch> dispatches);

    ActionDispatch update(ActionDispatch dispatch);

    Optional<ActionDispatch> findById(OrganizationId organizationId, ActionDispatchId id);

    Optional<ActionDispatch> findByAction(
            OrganizationId organizationId, PlannedActionId actionId);

    List<ActionDispatch> findByBundle(
            OrganizationId organizationId, ActionBundleId bundleId);

    /** Locks a bounded candidate batch with FOR UPDATE SKIP LOCKED for an outer claim transaction. */
    List<ActionDispatch> lockClaimable(
            OrganizationId organizationId, UtcTimestamp authoritativeNow, int limit);
}

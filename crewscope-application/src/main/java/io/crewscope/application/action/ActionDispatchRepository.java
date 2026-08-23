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

    /** Finds bounded tenants with READY execution work; reconciliation has a separate M5-I12 path. */
    List<OrganizationId> findClaimableOrganizations(UtcTimestamp authoritativeNow, int limit);

    /** Locks bounded READY rows with FOR UPDATE SKIP LOCKED for an outer claim transaction. */
    List<ActionDispatch> lockClaimable(
            OrganizationId organizationId, UtcTimestamp authoritativeNow, int limit);

    /** Finds tenants with due UNKNOWN work or an expired RUNNING/RECONCILING lease. */
    List<OrganizationId> findReconciliationOrganizations(
            UtcTimestamp authoritativeNow, int limit);

    /** Locks due UNKNOWN and expired leased rows for fenced reconciliation takeover. */
    List<ActionDispatch> lockReconciliationCandidates(
            OrganizationId organizationId, UtcTimestamp authoritativeNow, int limit);

    /** Returns the stable oldest-first human queue for a tenant. */
    List<ActionDispatch> findManualReview(OrganizationId organizationId, int limit);

    /** Returns aggregate queue health with no tenant or Action identifiers. */
    ActionReconciliationHealth reconciliationHealth();
}

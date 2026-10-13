package io.crewscope.application.organization.governance;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;

/**
 * Execution Port of the six frozen deletion steps (S01 freeze 3.4). countStep observes the live
 * row set a step would touch — create pins that observation as the item's expected count, process
 * re-observes it and settles a drift as CONFLICT instead of running a blind write against stale
 * facts. executeStep performs the idempotent step action and reports the exact number of affected
 * rows, so a re-run after a restored backup converges as a no-op DONE.
 */
public interface AccountDeletionStepExecutor {

    /**
     * Live count of rows the step would currently touch for the target principal, scoped to the
     * step's targetRef (a table name for DELETE_DERIVED_ROWS, a fixed ref otherwise).
     */
    long countStep(
            OrganizationId organizationId,
            PrincipalId targetPrincipalId,
            DeletionStepKey stepKey,
            String targetRef);

    /** Runs the idempotent step action; returns the exact number of affected rows. */
    int executeStep(
            OrganizationId organizationId,
            PrincipalId targetPrincipalId,
            DeletionStepKey stepKey,
            String targetRef);
}

package io.crewscope.application.action;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;

/** Team-scoped read Port for member-safe Action delivery queue health. */
public interface TeamActionReconciliationHealthRepository {

    /** Returns aggregate counters without Action, Worker, Lease or Provider identities. */
    ActionReconciliationHealth reconciliationHealth(
            OrganizationId organizationId, TeamId teamId);
}

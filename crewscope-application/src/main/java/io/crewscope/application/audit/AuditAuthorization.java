package io.crewscope.application.audit;

import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;

/** Current Team permission boundary for Audit read and governance export. */
public interface AuditAuthorization {

    void requireRead(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            UtcTimestamp occurredAt);

    void requireExport(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            UtcTimestamp occurredAt);
}

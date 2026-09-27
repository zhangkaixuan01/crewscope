package io.crewscope.application.audit;

import io.crewscope.domain.audit.AuditEventId;
import io.crewscope.domain.audit.AuditQueryEvent;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.util.Optional;

/** Persistence boundary for scope-complete keyset queries and bounded export reads. */
public interface AuditQueryPort {

    AuditPage find(AuditQuery query);

    AuditExportBatch export(AuditExportRequest request);

    /** Point read of one stored event; empty means no such event is visible in this Team Scope. */
    Optional<AuditQueryEvent> findEvent(
            OrganizationId organizationId, TeamId teamId, AuditEventId eventId);
}

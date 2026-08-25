package io.crewscope.application.audit;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.util.Objects;

/** Complete tenant and filter scope cryptographically bound by an Audit cursor codec. */
public record AuditCursorScope(
        OrganizationId organizationId,
        TeamId teamId,
        AuditFilterFingerprint filterFingerprint) {

    public AuditCursorScope {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        teamId = Objects.requireNonNull(teamId, "teamId");
        filterFingerprint = Objects.requireNonNull(filterFingerprint, "filterFingerprint");
    }

    public static AuditCursorScope of(
            OrganizationId organizationId, TeamId teamId, AuditQueryFilter filter) {
        return new AuditCursorScope(
                organizationId,
                teamId,
                Objects.requireNonNull(filter, "filter").fingerprint());
    }
}

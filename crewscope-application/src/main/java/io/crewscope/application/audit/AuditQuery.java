package io.crewscope.application.audit;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.util.Objects;
import java.util.Optional;

/** Scope-complete newest-first Audit query with a bounded page size. */
public record AuditQuery(
        AuditCursorScope cursorScope,
        AuditQueryFilter filter,
        Optional<AuditCursor> after,
        int limit) {

    public static final int MAX_LIMIT = 200;

    public AuditQuery {
        cursorScope = Objects.requireNonNull(cursorScope, "cursorScope");
        filter = Objects.requireNonNull(filter, "filter");
        after = Objects.requireNonNull(after, "after");
        if (!cursorScope.filterFingerprint().equals(filter.fingerprint())) {
            throw new IllegalArgumentException(
                    "Audit query filter must match the cursor scope fingerprint");
        }
        if (after.isPresent()) {
            after.orElseThrow().requireScope(cursorScope);
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException(
                    "Audit query limit must be between 1 and " + MAX_LIMIT);
        }
    }

    public static AuditQuery create(
            OrganizationId organizationId,
            TeamId teamId,
            AuditQueryFilter filter,
            Optional<AuditCursor> after,
            int limit) {
        return new AuditQuery(
                AuditCursorScope.of(organizationId, teamId, filter),
                filter,
                after,
                limit);
    }
}

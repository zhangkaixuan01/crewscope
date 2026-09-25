package io.crewscope.application.workitem;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.Objects;
import java.util.Optional;

/**
 * Scope-complete WorkItem list query with a bounded page size (M9b-A06).
 *
 * <p>The scope record is the single source of tenant, project, ordering and filter identity; the
 * compact constructor rejects a continuation cursor minted for any other combination before the
 * query reaches the persistence Port.
 */
public record WorkItemQuery(
        WorkItemCursorScope cursorScope,
        WorkItemFilter filter,
        Optional<WorkItemCursor> after,
        int limit) {

    public static final int MAX_LIMIT = 100;

    public WorkItemQuery {
        cursorScope = Objects.requireNonNull(cursorScope, "cursorScope");
        filter = Objects.requireNonNull(filter, "filter");
        after = Objects.requireNonNull(after, "after");
        if (!cursorScope.filterFingerprint().equals(filter.fingerprint())) {
            throw new IllegalArgumentException(
                    "WorkItem query filter must match the cursor scope fingerprint");
        }
        if (after.isPresent()) {
            after.orElseThrow().requireScope(cursorScope);
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException(
                    "WorkItem query limit must be between 1 and " + MAX_LIMIT);
        }
    }

    public static WorkItemQuery create(
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId projectId,
            PrincipalId viewerPrincipalId,
            WorkItemFilter filter,
            WorkItemSort sort,
            Optional<WorkItemCursor> after,
            int limit) {
        return new WorkItemQuery(
                WorkItemCursorScope.of(
                        organizationId, teamId, projectId, viewerPrincipalId, sort, filter),
                filter,
                after,
                limit);
    }

    public OrganizationId organizationId() {
        return cursorScope.organizationId();
    }

    public TeamId teamId() {
        return cursorScope.teamId();
    }

    public WorkProjectId projectId() {
        return cursorScope.projectId();
    }

    public PrincipalId viewerPrincipalId() {
        return cursorScope.viewerPrincipalId();
    }

    public WorkItemSort sort() {
        return cursorScope.sort();
    }
}

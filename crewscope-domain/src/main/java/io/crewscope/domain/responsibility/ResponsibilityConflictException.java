package io.crewscope.domain.responsibility;

import io.crewscope.domain.shared.error.DomainError;
import io.crewscope.domain.shared.error.DomainErrorCode;
import io.crewscope.domain.shared.error.DomainException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.workitem.WorkItemId;
import java.util.Map;
import java.util.Objects;

/** Reports a concurrent attempt to occupy an already active responsibility slot. */
public final class ResponsibilityConflictException extends DomainException {

    public ResponsibilityConflictException(
            WorkItemId workItemId, ResponsibilityRole role, PrincipalId actorPrincipalId) {
        super(new DomainError(
                DomainErrorCode.RESPONSIBILITY_CONFLICT,
                "WorkItem %s already has a conflicting active %s responsibility"
                        .formatted(
                                Objects.requireNonNull(workItemId, "workItemId"),
                                Objects.requireNonNull(role, "role")),
                Map.of(
                        "aggregateType", "ResponsibilityAssignment",
                        "workItemId", workItemId.toString(),
                        "role", role.name(),
                        "actorPrincipalId",
                                Objects.requireNonNull(actorPrincipalId, "actorPrincipalId")
                                        .toString())));
    }
}

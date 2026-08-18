package io.crewscope.domain.coding;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.workitem.WorkItemScope;
import java.util.Objects;

/** Scope guard for immutable CodingTarget facts; role checks remain in the application layer. */
final class CodingTargetActorPolicy {

    private CodingTargetActorPolicy() {}

    static PrincipalId requireActiveInScope(Principal actor, WorkItemScope scope, String field) {
        Principal requiredActor = Objects.requireNonNull(actor, "actor");
        WorkItemScope requiredScope = Objects.requireNonNull(scope, "scope");
        boolean outsideTeam = requiredActor.scope().teamId().isPresent()
                && requiredActor.scope().teamId().filter(requiredScope.teamId()::equals).isEmpty();
        if (!requiredActor.canAct()
                || !requiredActor.scope().organizationId().equals(requiredScope.organizationId())
                || outsideTeam) {
            throw new DomainValidationException(
                    field,
                    "must reference an active Principal in the same Organization and Team scope");
        }
        return requiredActor.id();
    }
}

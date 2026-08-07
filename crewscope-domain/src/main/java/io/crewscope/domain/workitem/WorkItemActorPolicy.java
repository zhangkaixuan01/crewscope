package io.crewscope.domain.workitem;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import java.util.Objects;

/** Shared scope guard; role authorization remains an application-layer decision. */
final class WorkItemActorPolicy {

    private WorkItemActorPolicy() {}

    static PrincipalId requireActiveInScope(
            Principal actor,
            OrganizationId organizationId,
            TeamId teamId,
            String field) {
        Principal requiredActor = Objects.requireNonNull(actor, "actor");
        if (!requiredActor.canAct()) {
            throw new DomainValidationException(field, "must reference an active Principal");
        }
        boolean outsideTeam = requiredActor.scope().teamId().isPresent()
                && requiredActor.scope().teamId().filter(teamId::equals).isEmpty();
        if (!requiredActor.scope().organizationId().equals(organizationId) || outsideTeam) {
            throw new DomainValidationException(
                    field, "must reference a Principal in the same Organization and Team scope");
        }
        return requiredActor.id();
    }
}

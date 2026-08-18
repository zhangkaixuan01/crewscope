package io.crewscope.domain.coding;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.PrincipalId;
import java.util.Objects;

/** Shared aggregate guard; Team-role authorization remains an application-layer decision. */
final class RepositoryBindingActorPolicy {

    private RepositoryBindingActorPolicy() {}

    static PrincipalId requireActiveInScope(
            Principal actor, RepositoryBindingScope scope, String field) {
        Principal requiredActor = Objects.requireNonNull(actor, "actor");
        RepositoryBindingScope requiredScope = Objects.requireNonNull(scope, "scope");
        boolean outsideTeam = requiredActor.scope().teamId().isPresent()
                && requiredActor.scope().teamId().filter(requiredScope.teamId()::equals).isEmpty();
        if (!requiredActor.canAct()
                || !requiredActor.scope()
                        .organizationId()
                        .equals(requiredScope.organizationId())
                || outsideTeam) {
            throw new DomainValidationException(
                    field,
                    "must reference an active Principal in the same Organization and Team scope");
        }
        return requiredActor.id();
    }
}

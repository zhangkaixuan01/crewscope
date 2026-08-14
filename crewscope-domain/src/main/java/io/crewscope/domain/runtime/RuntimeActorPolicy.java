package io.crewscope.domain.runtime;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import java.util.Objects;

/** Trust boundary for administrative runtime registry mutations. */
final class RuntimeActorPolicy {

    private RuntimeActorPolicy() {}

    static PrincipalId requireActiveInOrganization(
            Principal actor, OrganizationId organizationId, String field) {
        Principal required = Objects.requireNonNull(actor, "actor");
        if (!required.canAct()
                || !required.scope().organizationId().equals(
                        Objects.requireNonNull(organizationId, "organizationId"))) {
            throw new DomainValidationException(
                    field, "must reference an active Principal in the Runtime Organization");
        }
        return required.id();
    }
}

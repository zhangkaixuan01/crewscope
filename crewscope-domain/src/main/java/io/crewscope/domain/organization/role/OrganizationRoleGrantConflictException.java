package io.crewscope.domain.organization.role;

import io.crewscope.domain.shared.error.DomainError;
import io.crewscope.domain.shared.error.DomainErrorCode;
import io.crewscope.domain.shared.error.DomainException;
import java.util.Map;

/** Reports a duplicate ACTIVE grant for the same (organization, principal, role) triple. */
public final class OrganizationRoleGrantConflictException extends DomainException {

    public OrganizationRoleGrantConflictException() {
        super(new DomainError(
                DomainErrorCode.ORGANIZATION_ROLE_GRANT_CONFLICT,
                "Organization role grant conflicts with an existing active grant",
                Map.of()));
    }
}

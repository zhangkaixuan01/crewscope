package io.crewscope.domain.organization.role;

import io.crewscope.domain.shared.error.DomainError;
import io.crewscope.domain.shared.error.DomainErrorCode;
import io.crewscope.domain.shared.error.DomainException;
import io.crewscope.domain.shared.id.PrincipalId;
import java.util.Map;
import java.util.Objects;

/**
 * Reports that an organization-role command would leave the organization without an ACTIVE
 * ORG_OWNER (S01 freeze section 3.6, M9b last-owner precedent).
 */
public final class LastOrganizationOwnerProtectionException extends DomainException {

    public LastOrganizationOwnerProtectionException(
            String organizationId, PrincipalId principalId) {
        super(new DomainError(
                DomainErrorCode.LAST_OWNER_PROTECTION,
                "Organization %s would have no active ORG_OWNER after principal %s changes"
                        .formatted(
                                requireIdentifier(organizationId, "organizationId"),
                                Objects.requireNonNull(principalId, "principalId")),
                Map.of(
                        "organizationId", requireIdentifier(organizationId, "organizationId"),
                        "principalId", principalId.toString())));
    }

    private static String requireIdentifier(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.strip();
    }
}

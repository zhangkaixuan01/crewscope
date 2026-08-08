package io.crewscope.domain.provider;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamStatus;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Exact USER, TEAM or ORGANIZATION ownership shape for Provider authorization facts. */
public record ProviderOwner(
        OrganizationId organizationId,
        ProviderOwnerType type,
        UUID ownerId,
        Optional<TeamId> teamId,
        Optional<PrincipalId> userPrincipalId) {

    public ProviderOwner {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        type = Objects.requireNonNull(type, "type");
        ownerId = Objects.requireNonNull(ownerId, "ownerId");
        teamId = Objects.requireNonNull(teamId, "teamId");
        userPrincipalId = Objects.requireNonNull(userPrincipalId, "userPrincipalId");
        boolean valid = switch (type) {
            case ORGANIZATION -> ownerId.equals(organizationId.value())
                    && teamId.isEmpty()
                    && userPrincipalId.isEmpty();
            case TEAM -> teamId.isPresent()
                    && teamId.orElseThrow().value().equals(ownerId)
                    && userPrincipalId.isEmpty();
            case USER -> userPrincipalId.isPresent()
                    && userPrincipalId.orElseThrow().value().equals(ownerId)
                    && teamId.isEmpty();
        };
        if (!valid) {
            throw new DomainValidationException(
                    "providerOwner", "has an invalid owner shape");
        }
    }

    public static ProviderOwner organization(OrganizationId organizationId) {
        OrganizationId required = Objects.requireNonNull(organizationId, "organizationId");
        return new ProviderOwner(
                required,
                ProviderOwnerType.ORGANIZATION,
                required.value(),
                Optional.empty(),
                Optional.empty());
    }

    public static ProviderOwner team(Team team) {
        Team required = Objects.requireNonNull(team, "team");
        if (required.status() != TeamStatus.ACTIVE) {
            throw new DomainValidationException("providerOwner.teamId", "must be an active Team");
        }
        return new ProviderOwner(
                required.organizationId(),
                ProviderOwnerType.TEAM,
                required.id().value(),
                Optional.of(required.id()),
                Optional.empty());
    }

    public static ProviderOwner user(Principal user) {
        Principal required = Objects.requireNonNull(user, "user");
        if (required.type() != PrincipalType.USER || !required.canAct()) {
            throw new DomainValidationException(
                    "providerOwner.userPrincipalId", "must be an active USER Principal");
        }
        return new ProviderOwner(
                required.scope().organizationId(),
                ProviderOwnerType.USER,
                required.id().value(),
                Optional.empty(),
                Optional.of(required.id()));
    }

    /** Limits delegation to the same owner, or from an Organization to its narrower owners. */
    public boolean canGrantTo(ProviderOwner grantee) {
        ProviderOwner required = Objects.requireNonNull(grantee, "grantee");
        if (!organizationId.equals(required.organizationId)) {
            return false;
        }
        return equals(required)
                || type == ProviderOwnerType.ORGANIZATION;
    }
}

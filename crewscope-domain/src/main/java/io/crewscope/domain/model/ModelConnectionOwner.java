package io.crewscope.domain.model;

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

/** Exact USER, TEAM or ORGANIZATION owner of one model connection. */
public record ModelConnectionOwner(
        OrganizationId organizationId,
        ModelConnectionOwnerType type,
        UUID ownerId,
        Optional<TeamId> teamId,
        Optional<PrincipalId> userPrincipalId) {

    public ModelConnectionOwner {
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
                    "modelConnection.owner", "has an invalid ownership shape");
        }
    }

    public static ModelConnectionOwner organization(OrganizationId organizationId) {
        OrganizationId required = Objects.requireNonNull(organizationId, "organizationId");
        return new ModelConnectionOwner(
                required,
                ModelConnectionOwnerType.ORGANIZATION,
                required.value(),
                Optional.empty(),
                Optional.empty());
    }

    public static ModelConnectionOwner team(Team team) {
        Team required = Objects.requireNonNull(team, "team");
        if (required.status() != TeamStatus.ACTIVE) {
            throw new DomainValidationException(
                    "modelConnection.owner.teamId", "must be an active Team");
        }
        return new ModelConnectionOwner(
                required.organizationId(),
                ModelConnectionOwnerType.TEAM,
                required.id().value(),
                Optional.of(required.id()),
                Optional.empty());
    }

    public static ModelConnectionOwner user(Principal principal) {
        Principal required = Objects.requireNonNull(principal, "principal");
        if (required.type() != PrincipalType.USER || !required.canAct()) {
            throw new DomainValidationException(
                    "modelConnection.owner.userPrincipalId",
                    "must be an active USER Principal");
        }
        return new ModelConnectionOwner(
                required.scope().organizationId(),
                ModelConnectionOwnerType.USER,
                required.id().value(),
                Optional.empty(),
                Optional.of(required.id()));
    }
}

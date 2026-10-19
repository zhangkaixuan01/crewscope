package io.crewscope.domain.model;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Exact principal, team or organization a model connection is scoped to — either as the subject
 * credentials are bound for or as the subject usage is billed to (M13-C06).
 *
 * <p>The two positions share one shape by construction; the connection field that carries this
 * value names the position, so a single type keeps both sides from drifting apart.
 */
public record ModelSubject(
        OrganizationId organizationId,
        ModelSubjectType type,
        UUID subjectId,
        Optional<TeamId> teamId,
        Optional<PrincipalId> principalId) {

    public ModelSubject {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        type = Objects.requireNonNull(type, "type");
        subjectId = Objects.requireNonNull(subjectId, "subjectId");
        teamId = Objects.requireNonNull(teamId, "teamId");
        principalId = Objects.requireNonNull(principalId, "principalId");
        requireValidShape(organizationId, type, subjectId, teamId, principalId);
    }

    public static ModelSubject organization(OrganizationId organizationId) {
        OrganizationId required = Objects.requireNonNull(organizationId, "organizationId");
        return new ModelSubject(
                required,
                ModelSubjectType.ORGANIZATION,
                required.value(),
                Optional.empty(),
                Optional.empty());
    }

    public static ModelSubject team(OrganizationId organizationId, TeamId teamId) {
        TeamId requiredTeam = Objects.requireNonNull(teamId, "teamId");
        return new ModelSubject(
                organizationId,
                ModelSubjectType.TEAM,
                requiredTeam.value(),
                Optional.of(requiredTeam),
                Optional.empty());
    }

    public static ModelSubject principal(
            OrganizationId organizationId, PrincipalId principalId) {
        PrincipalId requiredPrincipal = Objects.requireNonNull(principalId, "principalId");
        return new ModelSubject(
                organizationId,
                ModelSubjectType.PRINCIPAL,
                requiredPrincipal.value(),
                Optional.empty(),
                Optional.of(requiredPrincipal));
    }

    public boolean isAllowedFor(ModelConnectionOwner owner) {
        ModelConnectionOwner required = Objects.requireNonNull(owner, "owner");
        if (!organizationId.equals(required.organizationId())) {
            return false;
        }
        return switch (required.type()) {
            case USER -> type == ModelSubjectType.PRINCIPAL
                    && required.userPrincipalId().equals(principalId);
            case TEAM -> type == ModelSubjectType.ORGANIZATION
                    || (type == ModelSubjectType.TEAM
                            && required.teamId().equals(teamId));
            case ORGANIZATION -> type == ModelSubjectType.ORGANIZATION;
        };
    }

    private static void requireValidShape(
            OrganizationId organizationId,
            ModelSubjectType type,
            UUID subjectId,
            Optional<TeamId> teamId,
            Optional<PrincipalId> principalId) {
        boolean valid = switch (type) {
            case ORGANIZATION -> subjectId.equals(organizationId.value())
                    && teamId.isEmpty()
                    && principalId.isEmpty();
            case TEAM -> teamId.isPresent()
                    && teamId.orElseThrow().value().equals(subjectId)
                    && principalId.isEmpty();
            case PRINCIPAL -> principalId.isPresent()
                    && principalId.orElseThrow().value().equals(subjectId)
                    && teamId.isEmpty();
        };
        if (!valid) {
            throw new DomainValidationException(
                    "modelConnection.subject", "has an invalid subject shape");
        }
    }
}

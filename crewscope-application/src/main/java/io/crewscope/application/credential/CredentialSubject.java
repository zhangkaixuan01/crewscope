package io.crewscope.application.credential;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Organization-scoped owner whose exact shape is also enforced by the database. */
public record CredentialSubject(
        OrganizationId organizationId,
        CredentialSubjectType type,
        UUID subjectId,
        Optional<TeamId> teamId,
        Optional<PrincipalId> principalId) {

    public CredentialSubject {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(type, "type");
        subjectId = Objects.requireNonNull(subjectId, "subjectId");
        teamId = Objects.requireNonNull(teamId, "teamId");
        principalId = Objects.requireNonNull(principalId, "principalId");
        if (subjectId.equals(new UUID(0L, 0L))) {
            throw new IllegalArgumentException("subjectId must not use the nil UUID");
        }
        validateShape(organizationId, type, subjectId, teamId, principalId);
    }

    public static CredentialSubject organization(OrganizationId organizationId) {
        OrganizationId organization = Objects.requireNonNull(organizationId, "organizationId");
        return new CredentialSubject(
                organization,
                CredentialSubjectType.ORGANIZATION,
                organization.value(),
                Optional.empty(),
                Optional.empty());
    }

    public static CredentialSubject team(OrganizationId organizationId, TeamId teamId) {
        TeamId team = Objects.requireNonNull(teamId, "teamId");
        return new CredentialSubject(
                organizationId,
                CredentialSubjectType.TEAM,
                team.value(),
                Optional.of(team),
                Optional.empty());
    }

    public static CredentialSubject principal(
            OrganizationId organizationId, PrincipalId principalId) {
        PrincipalId principal = Objects.requireNonNull(principalId, "principalId");
        return new CredentialSubject(
                organizationId,
                CredentialSubjectType.PRINCIPAL,
                principal.value(),
                Optional.empty(),
                Optional.of(principal));
    }

    private static void validateShape(
            OrganizationId organizationId,
            CredentialSubjectType type,
            UUID subjectId,
            Optional<TeamId> teamId,
            Optional<PrincipalId> principalId) {
        boolean valid = switch (type) {
            case ORGANIZATION -> subjectId.equals(organizationId.value())
                    && teamId.isEmpty()
                    && principalId.isEmpty();
            case TEAM -> teamId.map(value -> value.value().equals(subjectId)).orElse(false)
                    && principalId.isEmpty();
            case PRINCIPAL -> principalId
                            .map(value -> value.value().equals(subjectId))
                            .orElse(false)
                    && teamId.isEmpty();
        };
        if (!valid) {
            throw new IllegalArgumentException("Credential subject shape is invalid");
        }
    }
}

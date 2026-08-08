package io.crewscope.domain.provider;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.id.CredentialId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamInitialization;
import java.util.Optional;

final class ProviderDomainFixture {

    static final UtcTimestamp T0 = UtcTimestamp.parse("2026-08-08T14:00:00Z");
    static final UtcTimestamp T1 = UtcTimestamp.parse("2026-08-08T14:01:00Z");
    static final UtcTimestamp T2 = UtcTimestamp.parse("2026-08-08T14:02:00Z");
    static final UtcTimestamp T3 = UtcTimestamp.parse("2026-08-08T14:03:00Z");

    final OrganizationId organizationId;
    final Principal owner;
    final TeamInitialization team;
    final ProviderDefinition sourceCodeDefinition;
    final ProviderImplementation githubImplementation;

    private ProviderDomainFixture(
            OrganizationId organizationId,
            Principal owner,
            TeamInitialization team,
            ProviderDefinition sourceCodeDefinition,
            ProviderImplementation githubImplementation) {
        this.organizationId = organizationId;
        this.owner = owner;
        this.team = team;
        this.sourceCodeDefinition = sourceCodeDefinition;
        this.githubImplementation = githubImplementation;
    }

    static ProviderDomainFixture create() {
        OrganizationId organizationId = OrganizationId.generate();
        Principal owner = activeUser(organizationId, "Owner");
        TeamInitialization team = TeamInitialization.create(owner, "Platform", T0);
        ProviderDefinition definition = ProviderDefinition.create(
                ProviderDefinitionId.generate(),
                organizationId,
                "source-code",
                ProviderType.SOURCE_CODE,
                "1.0.0",
                "Source Code",
                ProviderCapabilities.of("source.read", "source.write", "pull-request.create"),
                owner,
                T0);
        ProviderImplementation implementation = ProviderImplementation.create(
                ProviderImplementationId.generate(),
                definition,
                "github-source-code",
                "1.0.0",
                definition.capabilities(),
                ProviderConnectionRequirement.REQUIRED,
                Optional.of("github"),
                owner,
                T0);
        return new ProviderDomainFixture(
                organizationId, owner, team, definition, implementation);
    }

    Connection teamConnection(Optional<UtcTimestamp> expiresAt) {
        return connection(ProviderOwner.team(team.team()), expiresAt);
    }

    Connection connection(ProviderOwner owner, Optional<UtcTimestamp> expiresAt) {
        return Connection.authorize(
                ConnectionId.generate(),
                owner,
                "github",
                "github-account-42",
                CredentialId.generate(),
                expiresAt,
                this.owner,
                T0);
    }

    ConnectionGrant grant(
            Connection connection,
            ProviderOwner grantee,
            ProviderAccessScope access,
            Optional<UtcTimestamp> expiresAt) {
        return ConnectionGrant.grant(
                ConnectionGrantId.generate(),
                connection,
                grantee,
                access,
                T0,
                expiresAt,
                owner,
                T0);
    }

    static ProviderAccessScope access(
            ProviderCapabilities capabilities, String... resources) {
        return new ProviderAccessScope(
                capabilities, ProviderResourceScope.of(resources));
    }

    static Principal activeUser(OrganizationId organizationId, String displayName) {
        return Principal.create(
                PrincipalId.generate(),
                PrincipalScope.organization(organizationId),
                PrincipalType.USER,
                Optional.empty(),
                displayName,
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                T0);
    }
}

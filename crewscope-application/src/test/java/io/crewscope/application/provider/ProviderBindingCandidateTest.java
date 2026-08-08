package io.crewscope.application.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.provider.Connection;
import io.crewscope.domain.provider.ConnectionGrant;
import io.crewscope.domain.provider.ConnectionGrantId;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.provider.ProviderAccessScope;
import io.crewscope.domain.provider.ProviderBinding;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.provider.ProviderBindingTarget;
import io.crewscope.domain.provider.ProviderCapabilities;
import io.crewscope.domain.provider.ProviderConnectionRequirement;
import io.crewscope.domain.provider.ProviderDefinition;
import io.crewscope.domain.provider.ProviderDefinitionId;
import io.crewscope.domain.provider.ProviderImplementation;
import io.crewscope.domain.provider.ProviderImplementationId;
import io.crewscope.domain.provider.ProviderOwner;
import io.crewscope.domain.provider.ProviderResourceScope;
import io.crewscope.domain.provider.ProviderType;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.CredentialId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamInitialization;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProviderBindingCandidateTest {

    private static final UtcTimestamp T0 = UtcTimestamp.parse("2026-08-08T15:00:00Z");
    private static final UtcTimestamp T1 = UtcTimestamp.parse("2026-08-08T15:01:00Z");

    @Test
    void derivesRuntimeDescriptorFromCompatibleRegistryFacts() {
        Context context = Context.create();

        ProviderDescriptor descriptor = ProviderDescriptor.from(
                context.definition, context.implementation);

        assertEquals(ProviderType.SOURCE_CODE, descriptor.type());
        assertEquals("github-source-code", descriptor.implementationId());
        assertEquals("1.0.0", descriptor.interfaceVersion());
        assertEquals("Source Code", descriptor.displayName());
    }

    @Test
    void closesActiveCandidateAndFailsAfterGrantRevocation() {
        Context context = Context.create();

        ProviderBindingCandidate candidate = ProviderBindingCandidate.resolve(
                context.binding,
                context.definition,
                context.implementation,
                Optional.of(context.connection),
                Optional.of(context.grant),
                T1);
        ConnectionGrant revoked = context.grant.revoke(
                0, context.owner, "Revoked", T1);

        assertEquals(
                ProviderCapabilities.of("source.read"),
                candidate.effectiveAccess().capabilities());
        assertThrows(
                DomainValidationException.class,
                () -> ProviderBindingCandidate.resolve(
                        context.binding,
                        context.definition,
                        context.implementation,
                        Optional.of(context.connection),
                        Optional.of(revoked),
                        T1));
    }

    private record Context(
            Principal owner,
            ProviderDefinition definition,
            ProviderImplementation implementation,
            Connection connection,
            ConnectionGrant grant,
            ProviderBinding binding) {

        static Context create() {
            OrganizationId organizationId = OrganizationId.generate();
            Principal owner = Principal.create(
                    PrincipalId.generate(),
                    PrincipalScope.organization(organizationId),
                    PrincipalType.USER,
                    Optional.empty(),
                    "Owner",
                    Optional.empty(),
                    PrincipalVisibility.ORGANIZATION,
                    T0);
            TeamInitialization team = TeamInitialization.create(owner, "Platform", T0);
            ProviderDefinition definition = ProviderDefinition.create(
                    ProviderDefinitionId.generate(),
                    organizationId,
                    "source-code",
                    ProviderType.SOURCE_CODE,
                    "1.0.0",
                    "Source Code",
                    ProviderCapabilities.of("source.read", "source.write"),
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
            ProviderOwner providerOwner = ProviderOwner.team(team.team());
            Connection connection = Connection.authorize(
                    ConnectionId.generate(),
                    providerOwner,
                    "github",
                    "github-account",
                    CredentialId.generate(),
                    Optional.empty(),
                    owner,
                    T0);
            ProviderAccessScope grantAccess = new ProviderAccessScope(
                    ProviderCapabilities.of("source.read"),
                    ProviderResourceScope.of("repository:crewscope"));
            ConnectionGrant grant = ConnectionGrant.grant(
                    ConnectionGrantId.generate(),
                    connection,
                    providerOwner,
                    grantAccess,
                    T0,
                    Optional.empty(),
                    owner,
                    T0);
            ProviderBinding binding = ProviderBinding.bind(
                    ProviderBindingId.generate(),
                    ProviderBindingTarget.workspace(team.defaultWorkspace()),
                    providerOwner,
                    definition,
                    implementation,
                    Optional.of(connection),
                    Optional.of(grant),
                    new ProviderAccessScope(
                            ProviderCapabilities.of("source.read", "source.write"),
                            ProviderResourceScope.of("repository:crewscope")),
                    true,
                    owner,
                    T0);
            return new Context(owner, definition, implementation, connection, grant, binding);
        }
    }
}

package io.crewscope.domain.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.CredentialId;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.workitem.WorkProject;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.domain.workitem.WorkProjectKey;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProviderBindingTest {

    @Test
    void bindsExternalImplementationToExactGrantIntersection() {
        Context context = Context.external();

        ProviderBinding binding = context.binding();

        assertEquals(ProviderType.SOURCE_CODE, binding.providerType());
        assertEquals(context.definition.id(), binding.definitionId());
        assertEquals(context.implementation.id(), binding.implementationId());
        assertEquals(context.connection.id(), binding.connectionId().orElseThrow());
        assertEquals(context.grant.id(), binding.connectionGrantId().orElseThrow());
        assertEquals(
                ProviderExecutionIdentity.TEAM_SERVICE_ACCOUNT,
                binding.executionIdentity().orElseThrow());
        assertEquals(
                ProviderCapabilities.of("source.read"),
                binding.effectiveAccess().capabilities());
        assertEquals(
                ProviderResourceScope.of("repository:crewscope"),
                binding.effectiveAccess().resources());
        assertTrue(binding.currentAccess(
                        context.definition,
                        context.implementation,
                        Optional.of(context.connection),
                        Optional.of(context.grant),
                        ProviderDomainFixture.T1)
                .isPresent());
    }

    @Test
    void supportsConnectionlessNativeProviderBinding() {
        ProviderDomainFixture fixture = ProviderDomainFixture.create();
        ProviderDefinition definition = ProviderDefinition.create(
                ProviderDefinitionId.generate(),
                fixture.organizationId,
                "work-item",
                ProviderType.WORK_ITEM,
                "1.0.0",
                "Work Item",
                ProviderCapabilities.of("workitem.read", "workitem.write"),
                fixture.owner,
                ProviderDomainFixture.T0);
        ProviderImplementation implementation = ProviderImplementation.create(
                ProviderImplementationId.generate(),
                definition,
                "native-work-item",
                "1.0.0",
                definition.capabilities(),
                ProviderConnectionRequirement.NONE,
                Optional.empty(),
                fixture.owner,
                ProviderDomainFixture.T0);
        ProviderAccessScope access = ProviderDomainFixture.access(
                definition.capabilities(),
                "workspace:" + fixture.team.defaultWorkspace().id());

        ProviderBinding binding = ProviderBinding.bind(
                ProviderBindingId.generate(),
                ProviderBindingTarget.workspace(fixture.team.defaultWorkspace()),
                ProviderOwner.team(fixture.team.team()),
                definition,
                implementation,
                Optional.empty(),
                Optional.empty(),
                access,
                true,
                fixture.owner,
                ProviderDomainFixture.T0);

        assertTrue(binding.connectionId().isEmpty());
        assertTrue(binding.connectionGrantId().isEmpty());
        assertTrue(binding.executionIdentity().isEmpty());
        assertEquals(access, binding.effectiveAccess());
    }

    @Test
    void supportsWorkProjectSpecificTarget() {
        Context context = Context.external();
        WorkProject project = WorkProject.create(
                WorkProjectId.generate(),
                new WorkProjectKey("PRV"),
                "Provider",
                context.fixture.team.team(),
                context.fixture.team.defaultWorkspace(),
                context.fixture.owner,
                ProviderDomainFixture.T0);

        ProviderBinding binding = ProviderBinding.bind(
                ProviderBindingId.generate(),
                ProviderBindingTarget.workProject(project),
                context.owner,
                context.definition,
                context.implementation,
                Optional.of(context.connection),
                Optional.of(context.grant),
                context.requested,
                false,
                context.fixture.owner,
                ProviderDomainFixture.T0);

        assertEquals(ProviderBindingTargetType.WORK_PROJECT, binding.target().type());
        assertEquals(project.id(), binding.target().workProjectId().orElseThrow());
    }

    @Test
    void rejectsTeamOwnerFromAnotherTargetTeam() {
        ProviderDomainFixture fixture = ProviderDomainFixture.create();
        TeamInitialization otherTeam = TeamInitialization.create(
                fixture.owner, "Other", ProviderDomainFixture.T0);

        assertThrows(
                DomainValidationException.class,
                () -> ProviderBinding.bind(
                        ProviderBindingId.generate(),
                        ProviderBindingTarget.workspace(fixture.team.defaultWorkspace()),
                        ProviderOwner.team(otherTeam.team()),
                        fixture.sourceCodeDefinition,
                        fixture.githubImplementation,
                        Optional.empty(),
                        Optional.empty(),
                        ProviderDomainFixture.access(
                                ProviderCapabilities.of("source.read"),
                                "repository:crewscope"),
                        false,
                        fixture.owner,
                        ProviderDomainFixture.T0));
    }

    @Test
    void rejectsMissingOrMismatchedConnectionFacts() {
        Context context = Context.external();
        Connection wrongConnector = Connection.authorize(
                ConnectionId.generate(),
                context.owner,
                "lark",
                "lark-account",
                CredentialId.generate(),
                Optional.empty(),
                context.fixture.owner,
                ProviderDomainFixture.T0);
        ConnectionGrant wrongGrant = ConnectionGrant.grant(
                ConnectionGrantId.generate(),
                wrongConnector,
                context.owner,
                context.requested,
                ProviderDomainFixture.T0,
                Optional.empty(),
                context.fixture.owner,
                ProviderDomainFixture.T0);

        assertThrows(
                DomainValidationException.class,
                () -> ProviderBinding.bind(
                        ProviderBindingId.generate(),
                        context.target,
                        context.owner,
                        context.definition,
                        context.implementation,
                        Optional.empty(),
                        Optional.empty(),
                        context.requested,
                        false,
                        context.fixture.owner,
                        ProviderDomainFixture.T0));
        assertThrows(
                DomainValidationException.class,
                () -> ProviderBinding.bind(
                        ProviderBindingId.generate(),
                        context.target,
                        context.owner,
                        context.definition,
                        context.implementation,
                        Optional.of(wrongConnector),
                        Optional.of(wrongGrant),
                        context.requested,
                        false,
                        context.fixture.owner,
                        ProviderDomainFixture.T0));
    }

    @Test
    void revokedOrVersionChangedFactsInvalidateCurrentAccess() {
        Context context = Context.external();
        ProviderBinding binding = context.binding();
        ConnectionGrant revoked = context.grant.revoke(
                0,
                context.fixture.owner,
                "Revoked",
                ProviderDomainFixture.T1);
        ProviderDefinition disabled = context.definition.disable(
                0, context.fixture.owner, ProviderDomainFixture.T1);
        ProviderBinding forgedIdentity = ProviderBinding.reconstitute(
                binding.id(),
                binding.organizationId(),
                binding.target(),
                binding.owner(),
                binding.definitionId(),
                binding.definitionVersion(),
                binding.providerType(),
                binding.implementationId(),
                binding.implementationVersion(),
                binding.connectionId(),
                binding.connectionVersion(),
                binding.connectionGrantId(),
                binding.connectionGrantVersion(),
                Optional.of(ProviderExecutionIdentity.DELEGATED_USER),
                binding.effectiveAccess(),
                binding.defaultUsage(),
                binding.status(),
                binding.version(),
                binding.audit());

        assertTrue(binding.currentAccess(
                        context.definition,
                        context.implementation,
                        Optional.of(context.connection),
                        Optional.of(revoked),
                        ProviderDomainFixture.T1)
                .isEmpty());
        assertTrue(binding.currentAccess(
                        disabled,
                        context.implementation,
                        Optional.of(context.connection),
                        Optional.of(context.grant),
                        ProviderDomainFixture.T1)
                .isEmpty());
        assertTrue(forgedIdentity.currentAccess(
                        context.definition,
                        context.implementation,
                        Optional.of(context.connection),
                        Optional.of(context.grant),
                        ProviderDomainFixture.T1)
                .isEmpty());
    }

    @Test
    void bindingLifecycleUsesOptimisticVersionAndArchiveIsTerminal() {
        Context context = Context.external();
        ProviderBinding active = context.binding();
        ProviderBinding disabled = active.disable(
                0, context.fixture.owner, ProviderDomainFixture.T1);
        ProviderBinding reactivated = disabled.activate(
                1, context.fixture.owner, ProviderDomainFixture.T2);
        ProviderBinding archived = reactivated.archive(
                2, context.fixture.owner, ProviderDomainFixture.T3);

        assertEquals(3, archived.version());
        assertEquals(ProviderRegistrationStatus.ARCHIVED, archived.status());
        assertTrue(disabled.currentAccess(
                        context.definition,
                        context.implementation,
                        Optional.of(context.connection),
                        Optional.of(context.grant),
                        ProviderDomainFixture.T1)
                .isEmpty());
        assertThrows(
                OptimisticLockConflictException.class,
                () -> active.disable(1, context.fixture.owner, ProviderDomainFixture.T1));
        assertThrows(
                InvalidStateTransitionException.class,
                () -> archived.activate(3, context.fixture.owner, ProviderDomainFixture.T3));
    }

    private record Context(
            ProviderDomainFixture fixture,
            ProviderDefinition definition,
            ProviderImplementation implementation,
            ProviderBindingTarget target,
            ProviderOwner owner,
            Connection connection,
            ConnectionGrant grant,
            ProviderAccessScope requested) {

        static Context external() {
            ProviderDomainFixture fixture = ProviderDomainFixture.create();
            ProviderBindingTarget target = ProviderBindingTarget.workspace(
                    fixture.team.defaultWorkspace());
            ProviderOwner owner = ProviderOwner.team(fixture.team.team());
            Connection connection = fixture.teamConnection(Optional.empty());
            ProviderAccessScope granted = ProviderDomainFixture.access(
                    ProviderCapabilities.of("source.read", "source.write"),
                    "repository:crewscope",
                    "repository:other");
            ConnectionGrant grant = fixture.grant(
                    connection, owner, granted, Optional.empty());
            ProviderAccessScope requested = ProviderDomainFixture.access(
                    ProviderCapabilities.of("source.read", "pull-request.create"),
                    "repository:crewscope",
                    "repository:forbidden");
            return new Context(
                    fixture,
                    fixture.sourceCodeDefinition,
                    fixture.githubImplementation,
                    target,
                    owner,
                    connection,
                    grant,
                    requested);
        }

        ProviderBinding binding() {
            return ProviderBinding.bind(
                    ProviderBindingId.generate(),
                    target,
                    owner,
                    definition,
                    implementation,
                    Optional.of(connection),
                    Optional.of(grant),
                    requested,
                    true,
                    fixture.owner,
                    ProviderDomainFixture.T0);
        }
    }
}

package io.crewscope.domain.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProviderDefinitionImplementationTest {

    @Test
    void createsCompatibleVersionedProviderContractAndImplementation() {
        ProviderDomainFixture fixture = ProviderDomainFixture.create();

        assertEquals(ProviderType.SOURCE_CODE, fixture.sourceCodeDefinition.type());
        assertEquals("1.0.0", fixture.sourceCodeDefinition.interfaceVersion());
        assertEquals("github-source-code", fixture.githubImplementation.key());
        assertEquals(
                ProviderConnectionRequirement.REQUIRED,
                fixture.githubImplementation.connectionRequirement());
        assertTrue(fixture.githubImplementation.supports(
                fixture.sourceCodeDefinition,
                ProviderCapabilities.of("source.read", "pull-request.create")));
    }

    @Test
    void rejectsImplementationCapabilityExpansionAndInvalidConnectorShape() {
        ProviderDomainFixture fixture = ProviderDomainFixture.create();

        assertThrows(
                DomainValidationException.class,
                () -> ProviderImplementation.create(
                        ProviderImplementationId.generate(),
                        fixture.sourceCodeDefinition,
                        "expanded",
                        "1.0.0",
                        ProviderCapabilities.of("source.read", "admin.override"),
                        ProviderConnectionRequirement.REQUIRED,
                        Optional.of("github"),
                        fixture.owner,
                        ProviderDomainFixture.T0));
        assertThrows(
                DomainValidationException.class,
                () -> ProviderImplementation.create(
                        ProviderImplementationId.generate(),
                        fixture.sourceCodeDefinition,
                        "missing-connector",
                        "1.0.0",
                        ProviderCapabilities.of("source.read"),
                        ProviderConnectionRequirement.REQUIRED,
                        Optional.empty(),
                        fixture.owner,
                        ProviderDomainFixture.T0));
        assertThrows(
                DomainValidationException.class,
                () -> ProviderImplementation.create(
                        ProviderImplementationId.generate(),
                        fixture.sourceCodeDefinition,
                        "native-with-connector",
                        "1.0.0",
                        ProviderCapabilities.of("source.read"),
                        ProviderConnectionRequirement.NONE,
                        Optional.of("github"),
                        fixture.owner,
                        ProviderDomainFixture.T0));
    }

    @Test
    void registryLifecycleUsesVersionsAndArchiveIsTerminal() {
        ProviderDomainFixture fixture = ProviderDomainFixture.create();

        ProviderDefinition disabled = fixture.sourceCodeDefinition.disable(
                0, fixture.owner, ProviderDomainFixture.T1);
        ProviderDefinition active = disabled.activate(
                1, fixture.owner, ProviderDomainFixture.T2);
        ProviderDefinition archived = active.archive(
                2, fixture.owner, ProviderDomainFixture.T3);

        assertEquals(3, archived.version());
        assertFalse(archived.isActive());
        assertThrows(
                OptimisticLockConflictException.class,
                () -> fixture.sourceCodeDefinition.disable(
                        1, fixture.owner, ProviderDomainFixture.T1));
        assertThrows(
                InvalidStateTransitionException.class,
                () -> archived.activate(3, fixture.owner, ProviderDomainFixture.T3));
        assertFalse(fixture.githubImplementation.supports(
                disabled, ProviderCapabilities.of("source.read")));
    }
}

package io.crewscope.application.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.projection.ProjectionCanonicalHash;
import io.crewscope.domain.projection.ProjectionDefinition;
import io.crewscope.domain.projection.ProjectionDefinitionVersion;
import io.crewscope.domain.projection.ProjectionGeneration;
import io.crewscope.domain.projection.ProjectionGenerationState;
import io.crewscope.domain.projection.ProjectionGenerationStatus;
import io.crewscope.domain.projection.ProjectionLifecycleEvent;
import io.crewscope.domain.projection.ProjectionLifecycleEventType;
import io.crewscope.domain.projection.ProjectionName;
import io.crewscope.domain.projection.ProjectionPointer;
import io.crewscope.domain.projection.ProjectionRebuildJob;
import io.crewscope.domain.projection.ProjectionRebuildJobId;
import io.crewscope.domain.projection.ProjectionRebuildStart;
import io.crewscope.domain.projection.ProjectionSnapshot;
import io.crewscope.domain.projection.ProjectionValidationPlan;
import io.crewscope.domain.shared.error.IdempotencyConflictException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ProjectionAdministrationServiceM6D07Test {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-25T14:00:00Z");
    private static final ProjectionCanonicalHash HASH_A =
            new ProjectionCanonicalHash("a".repeat(64));
    private static final ProjectionCanonicalHash HASH_B =
            new ProjectionCanonicalHash("b".repeat(64));

    private OrganizationId organizationId;
    private ProjectionDefinition definition;
    private Principal actor;
    private TeamAccessContext access;
    private ProjectionGenerationState active;
    private ProjectionPointer pointer;
    private ProjectionAdministration administration;
    private ProjectionAdministrationRepository repository;
    private ProjectionSnapshotVerifier verifier;
    private ProjectionAdministrationService service;

    @BeforeEach
    void setUp() {
        organizationId = OrganizationId.generate();
        definition = new ProjectionDefinition(
                new ProjectionName("team-activity"),
                ProjectionDefinitionVersion.V1,
                SchemaVersion.V1,
                "activity.canonical-v1",
                "activity.validator-v1");
        actor = Principal.create(
                PrincipalId.generate(),
                PrincipalScope.organization(organizationId),
                PrincipalType.USER,
                Optional.empty(),
                "Administrator",
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                NOW);
        access = new TeamAccessContext(actor, true);
        active = ProjectionGenerationState.active(
                organizationId, definition, ProjectionGeneration.FIRST, NOW);
        pointer = ProjectionPointer.initialize(active, NOW);
        administration = mock(ProjectionAdministration.class);
        repository = mock(ProjectionAdministrationRepository.class);
        verifier = mock(ProjectionSnapshotVerifier.class);
        service = new ProjectionAdministrationService(
                administration, repository, verifier, new DirectTransactions(), fixedTime());
    }

    @Test
    void authorizationFailurePerformsNoRegistryOrVerificationWork() {
        StartProjectionRebuildCommand command = startCommand(
                ProjectionAdministrationCommandId.generate(), 0);
        doThrow(new IllegalStateException("denied"))
                .when(administration)
                .requireOrganizationAdministrator(organizationId, access, NOW);

        assertThrows(IllegalStateException.class, () -> service.start(command));

        verifyNoInteractions(repository, verifier);
    }

    @Test
    void startsShadowAndPersistsOnlySafeLifecycleCoordinates() {
        StartProjectionRebuildCommand command = startCommand(
                ProjectionAdministrationCommandId.generate(), 0);
        when(repository.findReceipt(organizationId, command.commandId()))
                .thenReturn(Optional.empty());
        when(repository.loadForUpdate(organizationId, definition.name()))
                .thenReturn(registry(List.of(active), List.of()));
        when(repository.createRebuild(any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(2));

        ProjectionAdministrationResult result = service.start(command);

        ArgumentCaptor<ProjectionRebuildStart> start =
                ArgumentCaptor.forClass(ProjectionRebuildStart.class);
        ArgumentCaptor<ProjectionLifecycleEvent> event =
                ArgumentCaptor.forClass(ProjectionLifecycleEvent.class);
        verify(repository).createRebuild(start.capture(), event.capture(), any());
        assertEquals(ProjectionGenerationStatus.BUILDING, result.generationStatus());
        assertEquals(new ProjectionGeneration(2), result.generation());
        assertEquals(ProjectionLifecycleEventType.REBUILD_STARTED, event.getValue().eventType());
        assertEquals(actor.id(), event.getValue().actorId());
        assertTrue(event.getValue().failureCode().isEmpty());
        assertTrue(Arrays.stream(ProjectionLifecycleEvent.class.getRecordComponents())
                .map(component -> component.getName().toLowerCase())
                .noneMatch(name -> name.contains("payload")
                        || name.contains("secret")
                        || name.contains("credential")
                        || name.contains("exception")));
    }

    @Test
    void exactCommandReplayReturnsReceiptAndSemanticReuseConflicts() {
        ProjectionAdministrationCommandId commandId = ProjectionAdministrationCommandId.generate();
        StartProjectionRebuildCommand command = startCommand(commandId, 0);
        when(repository.findReceipt(organizationId, commandId)).thenReturn(Optional.empty());
        when(repository.loadForUpdate(organizationId, definition.name()))
                .thenReturn(registry(List.of(active), List.of()));
        ArgumentCaptor<ProjectionCommandReceipt> receipt =
                ArgumentCaptor.forClass(ProjectionCommandReceipt.class);
        when(repository.createRebuild(any(), any(), receipt.capture()))
                .thenAnswer(invocation -> invocation.getArgument(2));
        ProjectionAdministrationResult first = service.start(command);

        ProjectionCommandReceipt committed = receipt.getValue();
        reset(repository, verifier);
        when(repository.findReceipt(organizationId, commandId))
                .thenReturn(Optional.of(committed));

        assertEquals(first, service.start(command));
        assertThrows(
                IdempotencyConflictException.class,
                () -> service.start(startCommand(commandId, 1)));
        verify(repository, never()).loadForUpdate(any(), any());
        verifyNoInteractions(verifier);
    }

    @Test
    void stalePointerUsesStableOptimisticConflictBeforeMutation() {
        StartProjectionRebuildCommand command = startCommand(
                ProjectionAdministrationCommandId.generate(), 4);
        when(repository.findReceipt(organizationId, command.commandId()))
                .thenReturn(Optional.empty());
        when(repository.loadForUpdate(organizationId, definition.name()))
                .thenReturn(registry(List.of(active), List.of()));

        OptimisticLockConflictException failure = assertThrows(
                OptimisticLockConflictException.class, () -> service.start(command));

        assertEquals("4", failure.error().details().get("expectedVersion"));
        assertEquals("0", failure.error().details().get("actualVersion"));
        verify(repository, never()).createRebuild(any(), any(), any());
        verifyNoInteractions(verifier);
    }

    @Test
    void staleTerminationVersionUsesStableConflictBeforeMutation() {
        ProjectionRebuildStart start = ProjectionRebuildStart.start(
                organizationId, definition, pointer, List.of(active),
                ProjectionRebuildJobId.generate(), Optional.empty(), actor.id(), NOW);
        TerminateProjectionRebuildCommand command = new TerminateProjectionRebuildCommand(
                ProjectionAdministrationCommandId.generate(), organizationId, definition.name(),
                start.generation().key().generation(), start.job().id(), 1, 0,
                ProjectionAdministrationAction.CANCEL_REBUILD, Optional.empty(), access,
                ProjectionStrongConfirmation.confirm(
                        ProjectionAdministrationAction.CANCEL_REBUILD,
                        definition.name(), Optional.of(start.generation().key().generation())));
        when(repository.findReceipt(organizationId, command.commandId()))
                .thenReturn(Optional.empty());
        when(repository.loadForUpdate(organizationId, definition.name()))
                .thenReturn(registry(List.of(active, start.generation()), List.of(start.job())));

        OptimisticLockConflictException failure = assertThrows(
                OptimisticLockConflictException.class, () -> service.terminate(command));

        assertEquals("1", failure.error().details().get("expectedVersion"));
        assertEquals("0", failure.error().details().get("actualVersion"));
        verify(repository, never()).terminateRebuild(any(), any(), any());
        verifyNoInteractions(verifier);
    }

    @Test
    void failedCanonicalComparisonIsRecordedWithoutBecomingSwitchable() {
        ProjectionRebuildStart start = ProjectionRebuildStart.start(
                organizationId, definition, pointer, List.of(active),
                ProjectionRebuildJobId.generate(), Optional.empty(), actor.id(), NOW);
        ValidateProjectionGenerationCommand command = validateCommand(start, 0, 0);
        ProjectionSnapshot expected = healthy(3, HASH_A);
        ProjectionSnapshot actual = healthy(3, HASH_B);
        when(repository.findReceipt(organizationId, command.commandId()))
                .thenReturn(Optional.empty());
        when(repository.loadForUpdate(organizationId, definition.name()))
                .thenReturn(registry(List.of(active, start.generation()), List.of(start.job())));
        when(verifier.verify(definition, start.generation()))
                .thenReturn(new ProjectionVerificationSnapshots(expected, actual));
        when(repository.saveValidation(any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(2));

        ProjectionAdministrationResult result = service.validate(command);

        ArgumentCaptor<ProjectionLifecycleEvent> event =
                ArgumentCaptor.forClass(ProjectionLifecycleEvent.class);
        verify(repository).saveValidation(any(), event.capture(), any());
        assertEquals(ProjectionGenerationStatus.BUILDING, result.generationStatus());
        assertEquals(ProjectionLifecycleEventType.VALIDATION_FAILED, event.getValue().eventType());
    }

    @Test
    void switchRejectsStaleSnapshotBeforeRepositoryMutation() {
        ProjectionRebuildStart start = ProjectionRebuildStart.start(
                organizationId, definition, pointer, List.of(active),
                ProjectionRebuildJobId.generate(), Optional.empty(), actor.id(), NOW);
        ProjectionSnapshot validated = healthy(2, HASH_A);
        ProjectionValidationPlan validation = ProjectionValidationPlan.validate(
                definition, start.generation(), start.job(), 0, 0,
                validated, validated, actor.id(), NOW);
        SwitchProjectionGenerationCommand command = new SwitchProjectionGenerationCommand(
                ProjectionAdministrationCommandId.generate(), organizationId, definition.name(),
                definition.version(), active.key().generation(),
                validation.generation().key().generation(), validation.job().id(),
                pointer.version(), active.version(), validation.generation().version(),
                validation.job().version(), access,
                ProjectionStrongConfirmation.confirm(
                        ProjectionAdministrationAction.SWITCH_GENERATION,
                        definition.name(), Optional.of(validation.generation().key().generation())));
        when(repository.findReceipt(organizationId, command.commandId()))
                .thenReturn(Optional.empty());
        when(repository.loadForSwitch(
                        organizationId, definition.name(), command.targetGeneration()))
                .thenReturn(registry(
                        List.of(active, validation.generation()), List.of(validation.job())));
        when(verifier.current(definition, validation.generation()))
                .thenReturn(healthy(3, HASH_B));

        assertThrows(IllegalStateException.class, () -> service.switchGeneration(command));

        verify(repository, never()).switchGeneration(any(), any(), any());
    }

    private StartProjectionRebuildCommand startCommand(
            ProjectionAdministrationCommandId commandId, long expectedPointerVersion) {
        return new StartProjectionRebuildCommand(
                commandId,
                organizationId,
                definition.name(),
                definition.version(),
                expectedPointerVersion,
                access,
                ProjectionStrongConfirmation.confirm(
                        ProjectionAdministrationAction.START_REBUILD,
                        definition.name(),
                        Optional.empty()));
    }

    private ValidateProjectionGenerationCommand validateCommand(
            ProjectionRebuildStart start,
            long generationVersion,
            long jobVersion) {
        return new ValidateProjectionGenerationCommand(
                ProjectionAdministrationCommandId.generate(), organizationId, definition.name(),
                definition.version(), start.generation().key().generation(), start.job().id(),
                generationVersion, jobVersion, access,
                ProjectionStrongConfirmation.confirm(
                        ProjectionAdministrationAction.VALIDATE_GENERATION,
                        definition.name(), Optional.of(start.generation().key().generation())));
    }

    private ProjectionRegistrySnapshot registry(
            List<ProjectionGenerationState> generations,
            List<ProjectionRebuildJob> jobs) {
        return new ProjectionRegistrySnapshot(definition, pointer, generations, jobs);
    }

    private static ProjectionSnapshot healthy(long count, ProjectionCanonicalHash hash) {
        return new ProjectionSnapshot(count, hash, 0, List.of());
    }

    private static TimeProvider fixedTime() {
        return () -> NOW;
    }

    private static final class DirectTransactions implements TransactionExecutor {
        @Override
        public <T> T required(Supplier<T> operation) {
            return operation.get();
        }
    }
}

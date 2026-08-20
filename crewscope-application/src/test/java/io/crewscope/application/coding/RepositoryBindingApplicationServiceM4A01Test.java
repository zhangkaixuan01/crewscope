package io.crewscope.application.coding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.command.CommandReservation;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.coding.RepositoryBinding;
import io.crewscope.domain.coding.RepositoryBindingId;
import io.crewscope.domain.coding.RepositoryBindingStatus;
import io.crewscope.domain.coding.RepositoryBranchName;
import io.crewscope.domain.coding.RepositoryCommitId;
import io.crewscope.domain.coding.RepositoryKey;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.workitem.WorkProject;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.domain.workitem.WorkProjectKey;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RepositoryBindingApplicationServiceM4A01Test {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-19T14:00:00Z");

    private final OrganizationId organizationId = OrganizationId.generate();
    private final Principal actor = Principal.create(
            PrincipalId.generate(),
            PrincipalScope.organization(organizationId),
            PrincipalType.USER,
            Optional.empty(),
            "Owner",
            Optional.empty(),
            PrincipalVisibility.ORGANIZATION,
            NOW);
    private final TeamInitialization initialization = TeamInitialization.create(actor, "Team", NOW);
    private final WorkProject project = WorkProject.create(
            WorkProjectId.generate(),
            new WorkProjectKey("CODE"),
            "Code",
            initialization.team(),
            initialization.defaultWorkspace(),
            actor,
            NOW);

    private RepositoryBindingRepository repository;
    private RepositoryBindingAccessPolicy accessPolicy;
    private RepositoryBindingPreflightPort preflight;
    private DomainEventStore events;
    private OutboxRepository outbox;
    private CommandReceiptStore receipts;
    private RepositoryBindingApplicationService service;

    @BeforeEach
    void setUp() {
        repository = mock(RepositoryBindingRepository.class);
        accessPolicy = mock(RepositoryBindingAccessPolicy.class);
        preflight = mock(RepositoryBindingPreflightPort.class);
        events = mock(DomainEventStore.class);
        outbox = mock(OutboxRepository.class);
        receipts = mock(CommandReceiptStore.class);
        when(accessPolicy.requireAdministrator(any(), any(), any(), any(), any()))
                .thenReturn(project);
        when(accessPolicy.requireVisibleProject(any(), any(), any(), any())).thenReturn(project);
        when(receipts.reserve(any())).thenReturn(CommandReservation.newlyAcquired());
        when(repository.findByKey(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(repository.create(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(preflight.preflight(any(), any())).thenReturn(new RepositoryBindingPreflightResult(
                new RepositoryKey("crewscope"),
                new RepositoryBranchName("main"),
                new RepositoryCommitId("0123456789abcdef0123456789abcdef01234567")));
        service = new RepositoryBindingApplicationService(
                repository,
                accessPolicy,
                preflight,
                events,
                outbox,
                receipts,
                new DirectTransactionExecutor(),
                () -> NOW);
    }

    @Test
    void createsOnlyAfterPreflightAndCommitsEventOutboxAndReceipt() {
        CommandExecution<RepositoryBinding> execution = service.create(
                commandContext("register-repository-1"),
                initialization.team().id(),
                project.id(),
                new CreateRepositoryBindingCommand("crewscope", "main"));

        RepositoryBinding binding = execution.result().orElseThrow();
        assertEquals("crewscope", binding.repositoryKey().value());
        assertEquals(RepositoryBindingStatus.ACTIVE, binding.status());
        verify(preflight).preflight(binding, binding.defaultBranch());
        verify(repository).create(binding);
        verify(events).append(any());
        verify(outbox).enqueue(any());
        verify(receipts).complete(
                organizationId,
                IdempotencyKey.from("register-repository-1"),
                execution.receipt(),
                NOW);
    }

    @Test
    void replaysTheOriginalReceiptWithoutRepeatingPreflightOrPersistence() {
        CommandReceipt receipt =
                new CommandReceipt(UUID.randomUUID(), UUID.randomUUID(), 0, UUID.randomUUID());
        when(receipts.reserve(any())).thenReturn(CommandReservation.replay(receipt));

        CommandExecution<RepositoryBinding> replay = service.create(
                commandContext("register-repository-replay"),
                initialization.team().id(),
                project.id(),
                new CreateRepositoryBindingCommand("crewscope", "main"));

        assertTrue(replay.replayed());
        assertEquals(receipt, replay.receipt());
        verify(preflight, never()).preflight(any(), any());
        verify(repository, never()).create(any());
    }

    @Test
    void revalidatesCurrentAdministratorAuthorityBeforeReceiptReplay() {
        when(receipts.reserve(any())).thenReturn(CommandReservation.replay(new CommandReceipt(
                UUID.randomUUID(), UUID.randomUUID(), 0, UUID.randomUUID())));
        when(accessPolicy.requireAdministrator(any(), any(), any(), any(), any()))
                .thenThrow(new PolicyDeniedException("manage repositories in this Team"));

        assertThrows(PolicyDeniedException.class, () -> service.create(
                commandContext("register-repository-revoked"),
                initialization.team().id(),
                project.id(),
                new CreateRepositoryBindingCommand("crewscope", "main")));

        verify(receipts, never()).reserve(any());
        verify(preflight, never()).preflight(any(), any());
        verify(repository, never()).create(any());
    }

    @Test
    void listsAndLoadsOnlyAfterTheExactProjectScopeIsAuthorized() {
        RepositoryBinding binding = activeBinding();
        when(repository.findByWorkProject(
                        organizationId, initialization.team().id(), project.id()))
                .thenReturn(List.of(binding));
        when(repository.findById(
                        organizationId, initialization.team().id(), project.id(), binding.id()))
                .thenReturn(Optional.of(binding));

        assertEquals(
                List.of(binding),
                service.list(access(), organizationId, initialization.team().id(), project.id()));
        assertEquals(
                binding,
                service.get(
                        access(),
                        organizationId,
                        initialization.team().id(),
                        project.id(),
                        binding.id()));
        verify(accessPolicy, org.mockito.Mockito.times(2))
                .requireVisibleProject(
                        access(), organizationId, initialization.team().id(), project.id());
    }

    @Test
    void disablesWithExpectedVersionAndPreflightsBeforeReactivation() {
        AtomicReference<RepositoryBinding> current = new AtomicReference<>(activeBinding());
        when(repository.findById(any(), any(), any(), any()))
                .thenAnswer(invocation -> Optional.of(current.get()));
        when(repository.update(any())).thenAnswer(invocation -> {
            RepositoryBinding updated = invocation.getArgument(0);
            current.set(updated);
            return updated;
        });

        RepositoryBinding disabled = service.disable(
                        commandContext("disable-repository-1"),
                        initialization.team().id(),
                        project.id(),
                        current.get().id(),
                        0)
                .result()
                .orElseThrow();
        assertEquals(RepositoryBindingStatus.DISABLED, disabled.status());
        assertEquals(1, disabled.version());

        RepositoryBinding activated = service.activate(
                        commandContext("activate-repository-1"),
                        initialization.team().id(),
                        project.id(),
                        disabled.id(),
                        1)
                .result()
                .orElseThrow();
        assertEquals(RepositoryBindingStatus.ACTIVE, activated.status());
        assertEquals(2, activated.version());

        ArgumentCaptor<RepositoryBinding> candidate =
                ArgumentCaptor.forClass(RepositoryBinding.class);
        verify(preflight).preflight(candidate.capture(), any());
        assertTrue(candidate.getValue().acceptsNewTargets());
    }

    @Test
    void preservesOptimisticConflictForTheStableApiEnvelope() {
        RepositoryBinding binding = activeBinding();
        when(repository.findById(any(), any(), any(), any())).thenReturn(Optional.of(binding));

        assertThrows(
                OptimisticLockConflictException.class,
                () -> service.disable(
                        commandContext("disable-repository-stale"),
                        initialization.team().id(),
                        project.id(),
                        binding.id(),
                        7));
        verify(repository, never()).update(any());
    }

    @Test
    void draftPreflightUsesOnlyPathFreeKeyAndRefFacts() {
        RepositoryBindingPreflightResult result = service.preflightDraft(
                access(),
                organizationId,
                initialization.team().id(),
                project.id(),
                new RepositoryKey("crewscope"),
                new RepositoryBranchName("main"));

        assertEquals("crewscope", result.repositoryKey().value());
        assertFalse(result.baselineCommit().value().isBlank());
    }

    private RepositoryBinding activeBinding() {
        return RepositoryBinding.registerLocalManaged(
                RepositoryBindingId.generate(),
                project,
                new RepositoryKey("crewscope"),
                new RepositoryBranchName("main"),
                actor,
                NOW);
    }

    private TeamAccessContext access() {
        return new TeamAccessContext(actor, false);
    }

    private TeamCommandContext commandContext(String key) {
        return new TeamCommandContext(
                access(), IdempotencyKey.from(key), UUID.randomUUID(), Optional.empty());
    }

    private static final class DirectTransactionExecutor implements TransactionExecutor {

        @Override
        public <T> T required(Supplier<T> operation) {
            return operation.get();
        }
    }
}

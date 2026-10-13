package io.crewscope.application.organization.governance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.command.CommandReservation;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.identity.AccountOrganizationBindingRepository;
import io.crewscope.application.identity.UserAccountRepository;
import io.crewscope.application.organization.OrganizationAccessContext;
import io.crewscope.application.organization.OrganizationAuthorization;
import io.crewscope.application.organization.OrganizationCommandContext;
import io.crewscope.application.organization.OrganizationRoleGrantRepository;
import io.crewscope.application.responsibility.ResponsibilityAssignmentRepository;
import io.crewscope.application.task.TaskExecutionRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.audit.AuditOutcome;
import io.crewscope.domain.identity.AccountOrganizationBinding;
import io.crewscope.domain.identity.AccountOrganizationBindingId;
import io.crewscope.domain.identity.AccountOrganizationBindingStatus;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.identity.UserAccount;
import io.crewscope.domain.identity.UserAccountId;
import io.crewscope.domain.shared.audit.LifecycleMetadata;
import io.crewscope.domain.organization.governance.AccountDeletionItem;
import io.crewscope.domain.organization.governance.AccountDeletionJob;
import io.crewscope.domain.organization.governance.AccountDeletionJobId;
import io.crewscope.domain.organization.governance.AccountDeletionJobStatus;
import io.crewscope.domain.organization.role.OrganizationRole;
import io.crewscope.domain.organization.role.OrganizationRoleGrant;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

/**
 * Deletion workflow guardrails: hard preconditions, the two-step username-echo confirmation,
 * the re-observed step settlement (DONE vs CONFLICT), the constant backup-retention step, and
 * the direct audit fact behind every settlement.
 */
class OrganizationAccountDeletionApplicationServiceTest {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-10-12T08:00:00Z");

    private final OrganizationId organizationId = OrganizationId.generate();
    private final UserAccountId accountId = UserAccountId.generate();
    private final PrincipalId targetId = PrincipalId.generate();
    private final PrincipalId actorId = PrincipalId.generate();
    private final Principal actor = Principal.create(
            actorId,
            PrincipalScope.organization(organizationId),
            PrincipalType.USER,
            Optional.empty(),
            "Governor",
            Optional.empty(),
            PrincipalVisibility.ORGANIZATION,
            NOW);

    private OrganizationAccountDeletionJobRepository jobs;
    private AccountDeletionStepExecutor steps;
    private TaskExecutionRepository taskExecutions;
    private ResponsibilityAssignmentRepository responsibilities;
    private GovernanceAuditRecorder governanceAudit;
    private CommandReceiptStore receipts;
    private OrganizationAccountDeletionApplicationService service;

    private final AtomicReference<AccountDeletionJob> storedJob = new AtomicReference<>();
    private final Map<UUID, AccountDeletionItem> itemStore = new LinkedHashMap<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        OrganizationRoleGrantRepository grants = mock(OrganizationRoleGrantRepository.class);
        when(grants.findActiveByPrincipal(organizationId, actorId))
                .thenReturn(List.of(OrganizationRoleGrant.grant(
                        organizationId, actorId, OrganizationRole.ORG_OWNER, actorId, null, NOW)));
        jobs = mock(OrganizationAccountDeletionJobRepository.class);
        steps = mock(AccountDeletionStepExecutor.class);
        taskExecutions = mock(TaskExecutionRepository.class);
        responsibilities = mock(ResponsibilityAssignmentRepository.class);
        governanceAudit = mock(GovernanceAuditRecorder.class);
        receipts = mock(CommandReceiptStore.class);
        when(receipts.reserve(any())).thenReturn(CommandReservation.newlyAcquired());
        when(receipts.findCompleted(any(), any(), any(), any())).thenReturn(Optional.empty());
        AccountOrganizationBindingRepository bindings =
                mock(AccountOrganizationBindingRepository.class);
        when(bindings.findByAccountId(accountId)).thenReturn(List.of(
                AccountOrganizationBinding.reconstitute(
                        AccountOrganizationBindingId.generate(),
                        accountId,
                        organizationId,
                        targetId,
                        AccountOrganizationBindingStatus.ACTIVE,
                        0,
                        LifecycleMetadata.createdAt(NOW))));
        UserAccountRepository userAccounts = mock(UserAccountRepository.class);
        when(userAccounts.findById(accountId)).thenReturn(Optional.of(
                UserAccount.register(accountId, "operator-echo", "op@example.com", "Echo", NOW)));
        when(taskExecutions.countInFlightByPrincipals(any(), any())).thenReturn(0L);
        when(responsibilities.countActiveByPrincipal(any(), any())).thenReturn(0L);
        when(steps.countStep(any(), any(), any(), any())).thenReturn(0L);
        when(steps.executeStep(any(), any(), any(), any())).thenReturn(0);
        when(jobs.createJob(any(), any())).thenAnswer(invocation -> {
            storedJob.set(invocation.getArgument(0));
            for (AccountDeletionItem item : (List<AccountDeletionItem>) invocation.getArgument(1)) {
                itemStore.put(item.id().value(), item);
            }
            return storedJob.get();
        });
        when(jobs.lockJobById(any(), any()))
                .thenAnswer(invocation -> Optional.ofNullable(storedJob.get()));
        when(jobs.findJobById(any(), any()))
                .thenAnswer(invocation -> Optional.ofNullable(storedJob.get()));
        when(jobs.updateJob(any())).thenAnswer(invocation -> {
            storedJob.set(invocation.getArgument(0));
            return storedJob.get();
        });
        when(jobs.findItems(any(), any())).thenAnswer(invocation -> itemStore.values().stream()
                .sorted(java.util.Comparator.comparing(AccountDeletionItem::stepKey)
                        .thenComparing(AccountDeletionItem::targetRef))
                .toList());
        when(jobs.lockItemById(any(), any()))
                .thenAnswer(invocation -> Optional.ofNullable(
                        itemStore.get(((io.crewscope.domain.organization.governance
                                        .AccountDeletionItemId) invocation.getArgument(1))
                                .value())));
        when(jobs.updateItem(any())).thenAnswer(invocation -> {
            AccountDeletionItem item = invocation.getArgument(0);
            itemStore.put(item.id().value(), item);
            return item;
        });
        service = new OrganizationAccountDeletionApplicationService(
                jobs,
                new OrganizationAuthorization(grants),
                steps,
                bindings,
                userAccounts,
                taskExecutions,
                responsibilities,
                receipts,
                mock(DomainEventStore.class),
                mock(OutboxRepository.class),
                governanceAudit,
                straightThrough(),
                () -> NOW,
                new ObjectMapper());
    }

    @Test
    void precheckReportsTheBlockingCounts() {
        when(taskExecutions.countInFlightByPrincipals(any(), any())).thenReturn(3L);
        when(responsibilities.countActiveByPrincipal(any(), any())).thenReturn(2L);

        var view = service.precheck(access(), organizationId, accountId);

        assertEquals(accountId, view.accountId());
        assertEquals(3L, view.inFlightTaskExecutions());
        assertEquals(2L, view.activeResponsibilities());
        assertNull(view.openReservations());
    }

    @Test
    void createRejectsWhenPreconditionsAreNonZero() {
        when(taskExecutions.countInFlightByPrincipals(any(), any())).thenReturn(1L);

        assertThrows(
                DomainValidationException.class,
                () -> service.create(command(), organizationId, accountId, deliberateReason()));
        verify(jobs, never()).createJob(any(), any());
    }

    @Test
    void createPinsTheThirteenStepChecklistBehindConfirmation() {
        var execution = service.create(command(), organizationId, accountId, deliberateReason());

        assertFalse(execution.replayed());
        AccountDeletionJob job = execution.result().orElseThrow().job();
        assertEquals(AccountDeletionJobStatus.PENDING_CONFIRMATION, job.status());
        assertEquals(13, execution.result().orElseThrow().items().size());
        assertEquals(64, job.confirmationTokenHash().length());
        assertTrue(job.reservationCheck().contains("\"openReservations\":null"));
        assertTrue(job.backupRetentionStatement().contains("retain-backups"));
        assertEquals(
                DeletionStepKey.BACKUP_RETENTION_STATEMENT, job.backupRetentionStatement());
        // token hash=sha256 of the target username — the confirm step proves the pairing below.
        assertTrue(job.matchesConfirmationToken(sha256Hex("operator-echo")));
        verify(steps, times(13)).countStep(any(), any(), any(), any());
    }

    @Test
    void createDemandsADeliberateReason() {
        assertThrows(
                DomainValidationException.class,
                () -> service.create(command(), organizationId, accountId, "too short"));
    }

    @Test
    void replayedReservationReturnsTheOriginalReceiptWithoutNewJob() {
        CommandReceiptStore receipts = mock(CommandReceiptStore.class);
        when(receipts.reserve(any())).thenReturn(CommandReservation.replay(
                new io.crewscope.application.command.CommandReceipt(
                        UUID.randomUUID(), UUID.randomUUID(), 0L, UUID.randomUUID())));
        rebuildWithReceipts(receipts);

        var execution = service.create(command(), organizationId, accountId, deliberateReason());

        assertTrue(execution.replayed());
        verify(jobs, never()).createJob(any(), any());
    }

    @Test
    void confirmChecksTheVersionAndTheUsernameEcho() {
        service.create(command(), organizationId, accountId, deliberateReason());

        assertThrows(
                OptimisticLockConflictException.class,
                () -> service.confirm(
                        access(), organizationId, storedJob.get().id(), "operator-echo", 7L,
                        UUID.randomUUID()));
        assertThrows(
                DomainValidationException.class,
                () -> service.confirm(
                        access(), organizationId, storedJob.get().id(), "wrong-echo", 0L,
                        UUID.randomUUID()));

        var view = service.confirm(
                access(), organizationId, storedJob.get().id(), "operator-echo", 0L,
                UUID.randomUUID());

        assertEquals(AccountDeletionJobStatus.CONFIRMED, view.job().status());
    }

    @Test
    void processRefusesAnUnconfirmedJob() {
        service.create(command(), organizationId, accountId, deliberateReason());

        assertThrows(
                DomainValidationException.class,
                () -> service.process(
                        access(), organizationId, storedJob.get().id(), UUID.randomUUID()));
        verify(steps, never()).executeStep(any(), any(), any(), any());
    }

    @Test
    void processSettlesEveryStepAuditsEachAndSealsCompleted() {
        service.create(command(), organizationId, accountId, deliberateReason());
        service.confirm(
                access(), organizationId, storedJob.get().id(), "operator-echo", 0L,
                UUID.randomUUID());

        var view = service.process(
                access(), organizationId, storedJob.get().id(), UUID.randomUUID());

        assertEquals(AccountDeletionJobStatus.COMPLETED, view.job().status());
        assertTrue(view.items().stream().allMatch(item -> !item.isPending()));
        assertTrue(view.items().stream().allMatch(item ->
                item.state().name().equals("DONE")));
        // The backup statement is frozen onto the job row; the step touches no rows itself.
        verify(steps, never()).executeStep(
                any(), any(), eq(DeletionStepKey.RECORD_BACKUP_RETENTION), any());
        verify(steps, times(12)).executeStep(any(), any(), any(), any());
        verify(governanceAudit, times(13)).record(any());
    }

    @Test
    void driftedCountsSettleAsConflictAndTheJobStillSeals() {
        when(steps.countStep(any(), any(), eq(DeletionStepKey.REVOKE_TASK_CREDENTIAL_GRANTS),
                anyString())).thenReturn(2L, 1L);
        service.create(command(), organizationId, accountId, deliberateReason());
        service.confirm(
                access(), organizationId, storedJob.get().id(), "operator-echo", 0L,
                UUID.randomUUID());

        var view = service.process(
                access(), organizationId, storedJob.get().id(), UUID.randomUUID());

        assertEquals(AccountDeletionJobStatus.COMPLETED, view.job().status());
        AccountDeletionItem revoke = view.items().stream()
                .filter(item -> item.stepKey().equals("REVOKE_TASK_CREDENTIAL_GRANTS"))
                .findFirst()
                .orElseThrow();
        assertEquals("CONFLICT", revoke.state().name());
        assertEquals("EXPECTED_ROWS_ABSENT", revoke.errorCode().orElseThrow());
        assertEquals(2, revoke.expectedCount());
        verify(steps, never()).executeStep(
                any(), any(), eq(DeletionStepKey.REVOKE_TASK_CREDENTIAL_GRANTS), any());
        ArgumentCaptor<GovernanceAuditRecord> recorded =
                ArgumentCaptor.forClass(GovernanceAuditRecord.class);
        verify(governanceAudit, times(13)).record(recorded.capture());
        assertTrue(recorded.getAllValues().stream().anyMatch(record ->
                record.outcome() == AuditOutcome.FAILED
                        && record.targetRef().endsWith(":REVOKE_TASK_CREDENTIAL_GRANTS:TASK_CREDENTIAL_GRANT")));
        assertTrue(recorded.getAllValues().stream().anyMatch(record ->
                record.outcome() == AuditOutcome.SUCCEEDED));
    }

    @Test
    void cancelKeepsTheJobShortOfProcessing() {
        service.create(command(), organizationId, accountId, deliberateReason());

        var view = service.cancel(
                access(), organizationId, storedJob.get().id(), UUID.randomUUID());

        assertEquals(AccountDeletionJobStatus.CANCELLED, view.job().status());
        assertThrows(
                DomainValidationException.class,
                () -> service.cancel(
                        access(), organizationId, storedJob.get().id(), UUID.randomUUID()));
        verify(steps, never()).executeStep(any(), any(), any(), any());
    }

    @Test
    void deniedWriteCommandsAreAuditedAsGovernanceDenials() {
        // Denials surface before any job/binding lookup, so bare mocks carry the walk home.
        service = new OrganizationAccountDeletionApplicationService(
                jobs,
                denialAuthorization(),
                steps,
                mock(AccountOrganizationBindingRepository.class),
                mock(UserAccountRepository.class),
                taskExecutions,
                responsibilities,
                receipts,
                mock(DomainEventStore.class),
                mock(OutboxRepository.class),
                governanceAudit,
                straightThrough(),
                () -> NOW,
                new ObjectMapper());

        assertThrows(
                PolicyDeniedException.class,
                () -> service.create(command(), organizationId, accountId, deliberateReason()));
        assertThrows(
                PolicyDeniedException.class,
                () -> service.confirm(
                        access(), organizationId, AccountDeletionJobId.generate(),
                        "operator-echo", 0L, UUID.randomUUID()));
        assertThrows(
                PolicyDeniedException.class,
                () -> service.process(
                        access(), organizationId, AccountDeletionJobId.generate(),
                        UUID.randomUUID()));
        assertThrows(
                PolicyDeniedException.class,
                () -> service.cancel(
                        access(), organizationId, AccountDeletionJobId.generate(),
                        UUID.randomUUID()));

        ArgumentCaptor<GovernanceAuditRecord> recorded =
                ArgumentCaptor.forClass(GovernanceAuditRecord.class);
        verify(governanceAudit, times(4)).record(recorded.capture());
        assertTrue(recorded.getAllValues().stream().allMatch(record ->
                record.operation() == GovernanceAuditRecord.Operation.GOVERNANCE_DENIED
                        && record.outcome() == AuditOutcome.DENIED
                        && "GOVERNANCE_DELETION".equals(record.targetRef())
                        && record.rowCount() == 0
                        && record.detail() != null));
        // Read faces stay silent: denied precheck/GET record nothing beyond the four writes.
        assertThrows(
                PolicyDeniedException.class,
                () -> service.precheck(access(), organizationId, accountId));
        assertThrows(
                PolicyDeniedException.class,
                () -> service.get(
                        access(), organizationId, AccountDeletionJobId.generate()));
        verify(governanceAudit, times(4)).record(any());
    }

    private OrganizationAuthorization denialAuthorization() {
        OrganizationRoleGrantRepository empty = mock(OrganizationRoleGrantRepository.class);
        when(empty.findActiveByPrincipal(any(), any())).thenReturn(List.of());
        return new OrganizationAuthorization(empty);
    }

    private void rebuildWithReceipts(CommandReceiptStore receipts) {
        OrganizationRoleGrantRepository grants = mock(OrganizationRoleGrantRepository.class);
        when(grants.findActiveByPrincipal(organizationId, actorId))
                .thenReturn(List.of(OrganizationRoleGrant.grant(
                        organizationId, actorId, OrganizationRole.ORG_OWNER, actorId, null, NOW)));
        AccountOrganizationBindingRepository bindings =
                mock(AccountOrganizationBindingRepository.class);
        when(bindings.findByAccountId(accountId)).thenReturn(List.of(
                AccountOrganizationBinding.reconstitute(
                        AccountOrganizationBindingId.generate(),
                        accountId,
                        organizationId,
                        targetId,
                        AccountOrganizationBindingStatus.ACTIVE,
                        0,
                        LifecycleMetadata.createdAt(NOW))));
        UserAccountRepository userAccounts = mock(UserAccountRepository.class);
        when(userAccounts.findById(accountId)).thenReturn(Optional.of(
                UserAccount.register(accountId, "operator-echo", "op@example.com", "Echo", NOW)));
        service = new OrganizationAccountDeletionApplicationService(
                jobs,
                new OrganizationAuthorization(grants),
                steps,
                bindings,
                userAccounts,
                taskExecutions,
                responsibilities,
                receipts,
                mock(DomainEventStore.class),
                mock(OutboxRepository.class),
                governanceAudit,
                straightThrough(),
                () -> NOW,
                new ObjectMapper());
    }

    private static TransactionExecutor straightThrough() {
        return new TransactionExecutor() {
            @Override
            public <T> T required(java.util.function.Supplier<T> operation) {
                return operation.get();
            }
        };
    }

    private static String deliberateReason() {
        return "offboarding after the audit finding closed";
    }

    private static String sha256Hex(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }

    private OrganizationAccessContext access() {
        return new OrganizationAccessContext(actor);
    }

    private OrganizationCommandContext command() {
        return new OrganizationCommandContext(
                access(), IdempotencyKey.from("deletions-http-1"), UUID.randomUUID(),
                Optional.empty());
    }
}

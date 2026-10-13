package io.crewscope.application.organization.governance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
import io.crewscope.application.organization.OrganizationAccessContext;
import io.crewscope.application.organization.OrganizationAuthorization;
import io.crewscope.application.organization.OrganizationCommandContext;
import io.crewscope.application.organization.OrganizationRoleGrantRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.audit.AuditOutcome;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.organization.governance.DataExportItem;
import io.crewscope.domain.organization.governance.DataExportJob;
import io.crewscope.domain.organization.governance.DataExportJobId;
import io.crewscope.domain.organization.governance.DataExportJobStatus;
import io.crewscope.domain.organization.role.OrganizationRole;
import io.crewscope.domain.organization.role.OrganizationRoleGrant;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

/**
 * Export workflow guardrails: family pinning at create, the page-to-artifact settlement, the
 * SKIPPED vector degradation, manifest freezing exactly at completion, and the audited
 * download face including denials.
 */
class OrganizationExportApplicationServiceTest {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-10-12T08:00:00Z");

    private final OrganizationId organizationId = OrganizationId.generate();
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

    private OrganizationDataExportJobRepository jobs;
    private OrganizationExportResourceReader reader;
    private CommandReceiptStore receipts;
    private DomainEventStore eventStore;
    private GovernanceAuditRecorder governanceAudit;
    private OrganizationExportApplicationService service;

    private final AtomicReference<DataExportJob> storedJob = new AtomicReference<>();
    private final Map<UUID, DataExportItem> itemStore = new LinkedHashMap<>();
    private final AtomicLong createdArtifacts = new AtomicLong();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        OrganizationRoleGrantRepository grants = mock(OrganizationRoleGrantRepository.class);
        when(grants.findActiveByPrincipal(organizationId, actorId))
                .thenReturn(List.of(OrganizationRoleGrant.grant(
                        organizationId, actorId, OrganizationRole.ORG_OWNER, actorId, null, NOW)));
        jobs = mock(OrganizationDataExportJobRepository.class);
        reader = mock(OrganizationExportResourceReader.class);
        receipts = mock(CommandReceiptStore.class);
        eventStore = mock(DomainEventStore.class);
        governanceAudit = mock(GovernanceAuditRecorder.class);
        when(receipts.reserve(any())).thenReturn(CommandReservation.newlyAcquired());
        when(receipts.findCompleted(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(reader.countFamily(any(), any())).thenReturn(1L);
        when(reader.vectorStoreEnabled()).thenReturn(true);
        when(reader.readFamily(any(), any(), any(), anyInt()))
                .thenAnswer(invocation -> new OrganizationExportResourceReader.ExportResourcePage(
                        List.of("{\"row\":1}"), null));
        when(jobs.createJob(any(), any())).thenAnswer(invocation -> {
            storedJob.set(invocation.getArgument(0));
            for (DataExportItem item : (List<DataExportItem>) invocation.getArgument(1)) {
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
                .sorted(Comparator.comparing(DataExportItem::resourceFamily))
                .toList());
        when(jobs.lockItemById(any(), any()))
                .thenAnswer(invocation -> Optional.ofNullable(
                        itemStore.get(((io.crewscope.domain.organization.governance.DataExportItemId)
                                        invocation.getArgument(1))
                                .value())));
        when(jobs.updateItem(any())).thenAnswer(invocation -> {
            DataExportItem item = invocation.getArgument(0);
            itemStore.put(item.id().value(), item);
            return item;
        });
        when(jobs.createArtifact(any())).thenAnswer(invocation -> {
            createdArtifacts.incrementAndGet();
            return invocation.getArgument(0);
        });
        when(jobs.findArtifacts(any(), any())).thenReturn(List.of());
        service = new OrganizationExportApplicationService(
                jobs,
                new OrganizationAuthorization(grants),
                reader,
                receipts,
                eventStore,
                mock(OutboxRepository.class),
                governanceAudit,
                new TransactionExecutor() {
                    @Override
                    public <T> T required(java.util.function.Supplier<T> operation) {
                        return operation.get();
                    }
                },
                () -> NOW,
                new ObjectMapper());
    }

    @Test
    void createPinsOneItemPerFamilyAndSkipsVectorsUnlessRequested() {
        var withoutVectors = service.create(command(), organizationId, false);

        assertFalse(withoutVectors.replayed());
        assertEquals(22, withoutVectors.result().orElseThrow().items().size());
        assertTrue(withoutVectors.result().orElseThrow().items().stream()
                .noneMatch(item -> ExportResourceFamily.valueOf(item.resourceFamily()).isVector()));
        verify(reader, never()).readFamily(any(), any(), any(), anyInt());

        itemStore.clear();
        var withVectors = service.create(command(), organizationId, true);

        assertEquals(24, withVectors.result().orElseThrow().items().size());
        assertEquals(
                1,
                withVectors.result().orElseThrow().items().stream()
                        .filter(item -> item.resourceFamily().equals("ORGANIZATION"))
                        .findFirst()
                        .orElseThrow()
                        .rowCount());
        assertEquals(DataExportJobStatus.PENDING, storedJob.get().status());
        verify(eventStore, times(2)).append(any());
    }

    @Test
    void replayedReservationReturnsTheOriginalReceiptWithoutNewJob() {
        when(receipts.reserve(any())).thenReturn(CommandReservation.replay(
                new io.crewscope.application.command.CommandReceipt(
                        UUID.randomUUID(), UUID.randomUUID(), 0L, UUID.randomUUID())));

        var execution = service.create(command(), organizationId, false);

        assertTrue(execution.replayed());
        assertTrue(execution.result().isEmpty());
        verify(jobs, never()).createJob(any(), any());
        verify(eventStore, never()).append(any());
    }

    @Test
    void processSettlesEveryItemFreezesTheManifestAndSealsCompleted() {
        service.create(command(), organizationId, false);

        var view = service.process(access(), organizationId, storedJob.get().id(), UUID.randomUUID());

        assertEquals(DataExportJobStatus.COMPLETED, view.job().status());
        assertTrue(view.items().stream().allMatch(item -> !item.isPending()));
        assertTrue(view.items().stream().allMatch(item -> item.checksum().isPresent()));
        assertEquals(22L, createdArtifacts.get());
        assertTrue(view.job().manifest().isPresent());
        assertTrue(view.job().manifest().orElseThrow().contains("\"families\""));
        assertTrue(view.job().manifest().orElseThrow().contains("\"encoding\":\"base64-le-float32\""));
        verify(eventStore, times(2)).append(any());
    }

    @Test
    void vectorFamiliesDegradeToSkippedWhenTheStoreIsDisabled() {
        when(reader.vectorStoreEnabled()).thenReturn(false);
        service.create(command(), organizationId, true);

        var view = service.process(access(), organizationId, storedJob.get().id(), UUID.randomUUID());

        List<DataExportItem> vectorItems = view.items().stream()
                .filter(item -> ExportResourceFamily.valueOf(item.resourceFamily()).isVector())
                .toList();
        assertEquals(2, vectorItems.size());
        assertTrue(vectorItems.stream().allMatch(item -> !item.isPending()));
        assertTrue(vectorItems.stream().allMatch(item ->
                item.errorCode().orElse("").equals("VECTOR_STORE_DISABLED")));
        assertTrue(vectorItems.stream().noneMatch(item -> item.checksum().isPresent()));
        assertEquals(DataExportJobStatus.COMPLETED, view.job().status());
        assertEquals(22L, createdArtifacts.get());
        assertTrue(view.job().manifest().orElseThrow().contains("VECTOR_STORE_DISABLED"));
    }

    @Test
    void processIsANoOpForTerminalJobs() {
        service.create(command(), organizationId, false);
        service.process(access(), organizationId, storedJob.get().id(), UUID.randomUUID());
        long settledArtifacts = createdArtifacts.get();

        var again = service.process(
                access(), organizationId, storedJob.get().id(), UUID.randomUUID());

        assertEquals(DataExportJobStatus.COMPLETED, again.job().status());
        assertEquals(settledArtifacts, createdArtifacts.get());
        verify(eventStore, times(2)).append(any());
    }

    @Test
    void cancelStopsAnUnsettledJobAndKeepsSettledArtifacts() {
        service.create(command(), organizationId, false);

        var view = service.cancel(access(), organizationId, storedJob.get().id(), UUID.randomUUID());

        assertEquals(DataExportJobStatus.CANCELLED, view.job().status());
        verify(eventStore, times(2)).append(any());
        assertThrows(
                DomainValidationException.class,
                () -> service.cancel(
                        access(), organizationId, storedJob.get().id(), UUID.randomUUID()));
    }

    @Test
    void artifactsRequireACompletedJob() {
        service.create(command(), organizationId, false);

        assertThrows(
                DomainValidationException.class,
                () -> service.fetchArtifacts(
                        access(), organizationId, storedJob.get().id(), UUID.randomUUID()));
        verify(governanceAudit, never()).record(any());
    }

    @Test
    void everyDownloadAttemptLandsAsAGovernanceAuditFact() {
        service.create(command(), organizationId, false);
        service.process(access(), organizationId, storedJob.get().id(), UUID.randomUUID());

        var download = service.fetchArtifacts(
                access(), organizationId, storedJob.get().id(), UUID.randomUUID());

        assertEquals(DataExportJobStatus.COMPLETED, download.job().status());
        ArgumentCaptor<GovernanceAuditRecord> recorded =
                ArgumentCaptor.forClass(GovernanceAuditRecord.class);
        verify(governanceAudit).record(recorded.capture());
        assertEquals(GovernanceAuditRecord.Operation.EXPORT_DOWNLOAD,
                recorded.getValue().operation());
        assertEquals(AuditOutcome.SUCCEEDED, recorded.getValue().outcome());
    }

    @Test
    void deniedDownloadsAreAuditedBeforeTheDenialSurfaces() {
        service.create(command(), organizationId, false);
        service.process(access(), organizationId, storedJob.get().id(), UUID.randomUUID());
        service = new OrganizationExportApplicationService(
                jobs,
                denialAuthorization(),
                reader,
                receipts,
                eventStore,
                mock(OutboxRepository.class),
                governanceAudit,
                straightThrough(),
                () -> NOW,
                new ObjectMapper());

        assertThrows(
                PolicyDeniedException.class,
                () -> service.fetchArtifacts(
                        access(), organizationId, storedJob.get().id(), UUID.randomUUID()));

        ArgumentCaptor<GovernanceAuditRecord> recorded =
                ArgumentCaptor.forClass(GovernanceAuditRecord.class);
        verify(governanceAudit).record(recorded.capture());
        assertEquals(AuditOutcome.DENIED, recorded.getValue().outcome());
        assertEquals(
                storedJob.get().id().toString(), recorded.getValue().targetRef());
    }

    @Test
    void readersWithoutTheExportCapabilityAreDenied() {
        service = new OrganizationExportApplicationService(
                jobs,
                denialAuthorization(),
                reader,
                receipts,
                eventStore,
                mock(OutboxRepository.class),
                governanceAudit,
                straightThrough(),
                () -> NOW,
                new ObjectMapper());

        assertThrows(
                PolicyDeniedException.class,
                () -> service.create(command(), organizationId, false));
        assertThrows(
                PolicyDeniedException.class,
                () -> service.get(access(), organizationId, DataExportJobId.generate()));
    }

    private OrganizationAuthorization denialAuthorization() {
        OrganizationRoleGrantRepository empty = mock(OrganizationRoleGrantRepository.class);
        when(empty.findActiveByPrincipal(any(), any())).thenReturn(List.of());
        return new OrganizationAuthorization(empty);
    }

    private static TransactionExecutor straightThrough() {
        return new TransactionExecutor() {
            @Override
            public <T> T required(java.util.function.Supplier<T> operation) {
                return operation.get();
            }
        };
    }

    private OrganizationAccessContext access() {
        return new OrganizationAccessContext(actor);
    }

    private OrganizationCommandContext command() {
        return new OrganizationCommandContext(
                access(), IdempotencyKey.from("exports-http-1"), UUID.randomUUID(), Optional.empty());
    }
}

package io.crewscope.application.organization.governance;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.command.CommandRequestHash;
import io.crewscope.application.command.CommandReservation;
import io.crewscope.application.command.CommandReservationRequest;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.event.PendingOutboxEvent;
import io.crewscope.application.organization.OrganizationAccessContext;
import io.crewscope.application.organization.OrganizationAuthorization;
import io.crewscope.application.organization.OrganizationCommandContext;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.audit.AuditOutcome;
import io.crewscope.domain.organization.governance.DataExportItem;
import io.crewscope.domain.organization.governance.DataExportItemId;
import io.crewscope.domain.organization.governance.DataExportJob;
import io.crewscope.domain.organization.governance.DataExportJobId;
import io.crewscope.domain.organization.governance.DataExportJobStatus;
import io.crewscope.domain.organization.governance.event.OrganizationDataExportCancelled;
import io.crewscope.domain.organization.governance.event.OrganizationDataExportCompleted;
import io.crewscope.domain.organization.governance.event.OrganizationDataExportCreated;
import io.crewscope.domain.organization.role.OrganizationCapability;
import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.event.AggregateReference;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.event.EventActor;
import io.crewscope.domain.shared.event.EventActorType;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Controlled organization data export (M12-A01, S01 freeze 3.4).
 *
 * <p>create pins one item per resource family with the live row count; process settles each item
 * in its own short transaction (page the family by keyset, emit bounded JSON artifacts, freeze
 * the family checksum); the job freezes the reconciliation manifest — families x counts x
 * checksums x skipped reasons — exactly when every item is terminal. There is no FAILED status:
 * an unexpected error leaves items PENDING and the next process call resumes at the first one
 * (handover re-entry contract). Every artifact download is a direct governance audit fact,
 * including denials.
 */
public final class OrganizationExportApplicationService {

    private static final String CREATE_COMMAND_TYPE = "organization.create-data-export";
    private static final String AGGREGATE_TYPE = "ORGANIZATION_DATA_EXPORT_JOB";
    private static final String VECTOR_ENCODING = "base64-le-float32";

    private final OrganizationDataExportJobRepository jobs;
    private final OrganizationAuthorization authorization;
    private final OrganizationExportResourceReader reader;
    private final CommandReceiptStore receipts;
    private final DomainEventStore eventStore;
    private final OutboxRepository outboxRepository;
    private final GovernanceAuditRecorder governanceAudit;
    private final TransactionExecutor transactions;
    private final TimeProvider timeProvider;
    private final tools.jackson.databind.ObjectMapper objectMapper;

    public OrganizationExportApplicationService(
            OrganizationDataExportJobRepository jobs,
            OrganizationAuthorization authorization,
            OrganizationExportResourceReader reader,
            CommandReceiptStore receipts,
            DomainEventStore eventStore,
            OutboxRepository outboxRepository,
            GovernanceAuditRecorder governanceAudit,
            TransactionExecutor transactions,
            TimeProvider timeProvider,
            tools.jackson.databind.ObjectMapper objectMapper) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.reader = Objects.requireNonNull(reader, "reader");
        this.receipts = Objects.requireNonNull(receipts, "receipts");
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore");
        this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository");
        this.governanceAudit = Objects.requireNonNull(governanceAudit, "governanceAudit");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /** Queues an export; vector families join only when explicitly requested. */
    public CommandExecution<ExportJobView> create(
            OrganizationCommandContext context, OrganizationId organizationId, boolean includeVectors) {
        Objects.requireNonNull(context, "context");
        authorization.requireCapability(
                context.access(), organizationId, OrganizationCapability.GOVERNANCE_EXPORT);
        CommandRequestHash hash = CommandRequestHash.sha256(
                CREATE_COMMAND_TYPE, organizationId.toString(), Boolean.toString(includeVectors));
        Optional<CommandReceipt> completed =
                receipts.findCompleted(organizationId, context.idempotencyKey(), CREATE_COMMAND_TYPE, hash);
        if (completed.isPresent()) {
            return CommandExecution.replayed(completed.orElseThrow());
        }
        return transactions.required(
                () -> executeCreate(context, organizationId, includeVectors, hash));
    }

    /**
     * Processes every PENDING item in its own short transaction and freezes the manifest once
     * every item reached a terminal state; interrupted runs resume at the first PENDING item.
     */
    public ExportJobView process(
            OrganizationAccessContext access,
            OrganizationId organizationId,
            DataExportJobId jobId,
            UUID correlationId) {
        Objects.requireNonNull(access, "access");
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(correlationId, "correlationId");
        authorization.requireCapability(
                access, organizationId, OrganizationCapability.GOVERNANCE_EXPORT);
        DataExportJob job = transactions.required(
                () -> {
                    DataExportJob locked = requireJob(organizationId, jobId);
                    if (locked.isTerminal()) {
                        return locked;
                    }
                    return jobs.updateJob(
                            locked.markRunning(access.actor().id(), timeProvider.now()));
                });
        if (job.isTerminal()) {
            return view(organizationId, job);
        }
        for (DataExportItem item : jobs.findItems(organizationId, jobId)) {
            if (!item.isPending()) {
                continue;
            }
            processOneItem(organizationId, job, item, access.actor().id());
        }
        DataExportJob completed = transactions.required(
                () -> {
                    DataExportJob locked = requireJob(organizationId, jobId);
                    List<DataExportItem> items = jobs.findItems(organizationId, jobId);
                    if (locked.isTerminal() || items.stream().anyMatch(DataExportItem::isPending)) {
                        return locked;
                    }
                    String manifest = buildManifest(items);
                    DataExportJob sealed = jobs.updateJob(
                            locked.markCompleted(manifest, access.actor().id(), timeProvider.now()));
                    appendFact(
                            access.actor().id(),
                            organizationId,
                            sealed,
                            EventType.from("ORGANIZATION_DATA_EXPORT_COMPLETED"),
                            correlationId,
                            new OrganizationDataExportCompleted(
                                    organizationId.value(),
                                    sealed.id().value(),
                                    sha256Hex(manifest),
                                    items.size()));
                    return sealed;
                });
        return view(organizationId, completed);
    }

    /** Stops a job that still has unprocessed items; already DONE artifacts stay for audit. */
    public ExportJobView cancel(
            OrganizationAccessContext access,
            OrganizationId organizationId,
            DataExportJobId jobId,
            UUID correlationId) {
        Objects.requireNonNull(access, "access");
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(correlationId, "correlationId");
        authorization.requireCapability(
                access, organizationId, OrganizationCapability.GOVERNANCE_EXPORT);
        return transactions.required(
                () -> {
                    DataExportJob job = requireJob(organizationId, jobId);
                    if (job.isTerminal()) {
                        throw new DomainValidationException(
                                "dataExportJob.status", "job already reached a terminal state");
                    }
                    DataExportJob cancelled =
                            jobs.updateJob(job.markCancelled(access.actor().id(), timeProvider.now()));
                    appendFact(
                            access.actor().id(),
                            organizationId,
                            cancelled,
                            EventType.from("ORGANIZATION_DATA_EXPORT_CANCELLED"),
                            correlationId,
                            new OrganizationDataExportCancelled(
                                    organizationId.value(), cancelled.id().value()));
                    return view(organizationId, cancelled);
                });
    }

    /** Read face for the reconciliation surface: job + items (+ manifest once completed). */
    public ExportJobView get(
            OrganizationAccessContext access, OrganizationId organizationId, DataExportJobId jobId) {
        Objects.requireNonNull(access, "access");
        Objects.requireNonNull(jobId, "jobId");
        authorization.requireCapability(
                access, organizationId, OrganizationCapability.AUDIT_READ_ORG);
        return view(organizationId, jobs.findJobById(organizationId, jobId)
                .orElseThrow(() -> new AggregateNotFoundException(AGGREGATE_TYPE, jobId)));
    }

    /**
     * Download face of a completed export. Every attempt — SUCCEEDED and DENIED alike — lands
     * as a direct governance audit fact before the artifacts leave the service.
     */
    public ExportArtifactsView fetchArtifacts(
            OrganizationAccessContext access,
            OrganizationId organizationId,
            DataExportJobId jobId,
            UUID correlationId) {
        Objects.requireNonNull(access, "access");
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(correlationId, "correlationId");
        try {
            authorization.requireCapability(
                    access, organizationId, OrganizationCapability.GOVERNANCE_EXPORT);
        } catch (PolicyDeniedException denied) {
            governanceAudit.record(new GovernanceAuditRecord(
                    GovernanceAuditRecord.Operation.EXPORT_DOWNLOAD,
                    organizationId,
                    access.actor(),
                    correlationId,
                    AuditOutcome.DENIED,
                    jobId.toString(),
                    0,
                    denied.getMessage(),
                    timeProvider.now()));
            throw denied;
        }
        DataExportJob job = jobs.findJobById(organizationId, jobId)
                .orElseThrow(() -> new AggregateNotFoundException(AGGREGATE_TYPE, jobId));
        if (job.status() != DataExportJobStatus.COMPLETED) {
            throw new DomainValidationException(
                    "dataExportJob.status", "artifacts are only downloadable after completion");
        }
        List<DataExportArtifact> artifacts = jobs.findArtifacts(organizationId, jobId);
        governanceAudit.record(new GovernanceAuditRecord(
                GovernanceAuditRecord.Operation.EXPORT_DOWNLOAD,
                organizationId,
                access.actor(),
                correlationId,
                AuditOutcome.SUCCEEDED,
                jobId.toString(),
                artifacts.size(),
                null,
                timeProvider.now()));
        return new ExportArtifactsView(job, jobs.findItems(organizationId, jobId), artifacts);
    }

    private CommandExecution<ExportJobView> executeCreate(
            OrganizationCommandContext context,
            OrganizationId organizationId,
            boolean includeVectors,
            CommandRequestHash hash) {
        UtcTimestamp now = timeProvider.now();
        UUID commandId = UUID.randomUUID();
        CommandReservation reservation = receipts.reserve(new CommandReservationRequest(
                organizationId,
                context.idempotencyKey(),
                CREATE_COMMAND_TYPE,
                hash,
                commandId,
                context.correlationId(),
                now));
        if (!reservation.acquired()) {
            return CommandExecution.replayed(reservation.receipt().orElseThrow());
        }
        DataExportJobId jobId = DataExportJobId.generate();
        DataExportJob job = DataExportJob.create(
                jobId,
                organizationId,
                context.access().actor().id(),
                commandId.toString(),
                includeVectors,
                now);
        List<DataExportItem> items = new ArrayList<>();
        for (ExportResourceFamily family : ExportResourceFamily.values()) {
            if (family.isVector() && !includeVectors) {
                continue;
            }
            long count = reader.countFamily(organizationId, family);
            items.add(DataExportItem.create(
                    DataExportItemId.generate(),
                    jobId,
                    organizationId,
                    family.name(),
                    0,
                    Optional.empty(),
                    (int) Math.min(count, Integer.MAX_VALUE),
                    context.access().actor().id(),
                    now));
        }
        DataExportJob stored = jobs.createJob(job, items);
        UUID eventId = appendFact(
                context.access().actor().id(),
                organizationId,
                stored,
                EventType.from("ORGANIZATION_DATA_EXPORT_CREATED"),
                context.correlationId(),
                new OrganizationDataExportCreated(
                        organizationId.value(), stored.id().value(), includeVectors, items.size()));
        CommandReceipt receipt =
                new CommandReceipt(commandId, eventId, stored.version(), context.correlationId());
        receipts.complete(organizationId, context.idempotencyKey(), receipt, now);
        return CommandExecution.completed(new ExportJobView(stored, items), receipt);
    }

    /**
     * One item = one family = one short transaction: page the family by keyset until the pinned
     * row count or EOF, accumulate bounded JSON artifacts, freeze the family checksum, settle
     * the item. Vector families of a disabled store settle as SKIPPED/VECTOR_STORE_DISABLED.
     */
    private void processOneItem(
            OrganizationId organizationId, DataExportJob job, DataExportItem item, PrincipalId actor) {
        transactions.required(
                () -> {
                    DataExportItem locked = jobs
                            .lockItemById(organizationId, item.id())
                            .orElseThrow(
                                    () -> new AggregateNotFoundException("DATA_EXPORT_ITEM", item.id()));
                    if (!locked.isPending()) {
                        return (Void) null;
                    }
                    ExportResourceFamily family =
                            ExportResourceFamily.valueOf(locked.resourceFamily());
                    if (family.isVector() && !reader.vectorStoreEnabled()) {
                        jobs.updateItem(locked.markSkipped(
                                "VECTOR_STORE_DISABLED", actor, timeProvider.now()));
                        return (Void) null;
                    }
                    String checksum = exportFamily(organizationId, job, locked, family, actor);
                    jobs.updateItem(locked.markDone(checksum, actor, timeProvider.now()));
                    return (Void) null;
                });
    }

    private String exportFamily(
            OrganizationId organizationId,
            DataExportJob job,
            DataExportItem item,
            ExportResourceFamily family,
            PrincipalId actor) {
        MessageDigest familyDigest = sha256Digest();
        String cursor = item.sortCursor().orElse(null);
        long remaining = item.rowCount();
        int artifactSequence = 0;
        int artifactRows = 0;
        ByteArrayOutputStream artifactBuffer = new ByteArrayOutputStream();
        MessageDigest artifactDigest = sha256Digest();
        while (remaining > 0) {
            OrganizationExportResourceReader.ExportResourcePage page =
                    reader.readFamily(organizationId, family, cursor, family.rowsPerBatch());
            if (page.rows().isEmpty()) {
                break;
            }
            for (String row : page.rows()) {
                byte[] framed = (row + "\n").getBytes(StandardCharsets.UTF_8);
                familyDigest.update(framed);
                artifactDigest.update(framed);
                artifactBuffer.writeBytes(framed);
                artifactRows++;
                remaining--;
                if (artifactBuffer.size() >= DataExportArtifact.MAXIMUM_BYTES) {
                    jobs.createArtifact(artifactOf(
                            organizationId, job, item, artifactSequence++, artifactBuffer,
                            artifactDigest, artifactRows, actor));
                    artifactBuffer = new ByteArrayOutputStream();
                    artifactDigest = sha256Digest();
                    artifactRows = 0;
                }
                if (remaining == 0) {
                    break;
                }
            }
            cursor = page.hasNext() ? page.nextCursor() : null;
            if (cursor == null) {
                break;
            }
        }
        if (artifactRows > 0) {
            jobs.createArtifact(artifactOf(
                    organizationId, job, item, artifactSequence++, artifactBuffer,
                    artifactDigest, artifactRows, actor));
        }
        return HexFormat.of().formatHex(familyDigest.digest());
    }

    private DataExportArtifact artifactOf(
            OrganizationId organizationId,
            DataExportJob job,
            DataExportItem item,
            int sequence,
            ByteArrayOutputStream buffer,
            MessageDigest digest,
            int rows,
            PrincipalId actor) {
        byte[] content = buffer.toByteArray();
        if (content.length < 1 || content.length > DataExportArtifact.MAXIMUM_BYTES) {
            throw new DomainValidationException(
                    "dataExportArtifact.byteSize", "is outside the bounded range");
        }
        return new DataExportArtifact(
                UUID.randomUUID(),
                organizationId,
                job.id(),
                item.id(),
                sequence,
                new String(content, StandardCharsets.UTF_8),
                content.length,
                HexFormat.of().formatHex(digest.digest()),
                actor,
                timeProvider.now());
    }

    private String buildManifest(List<DataExportItem> items) {
        List<Map<String, Object>> families = new ArrayList<>();
        for (ExportResourceFamily family : ExportResourceFamily.values()) {
            List<DataExportItem> ofFamily = items.stream()
                    .filter(item -> item.resourceFamily().equals(family.name()))
                    .toList();
            if (ofFamily.isEmpty()) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("family", family.name());
            entry.put("itemCount", ofFamily.size());
            entry.put("pinnedRowCount", ofFamily.stream().mapToLong(DataExportItem::rowCount).sum());
            entry.put("skipped", ofFamily.stream()
                    .filter(item -> item.errorCode().isPresent())
                    .map(item -> item.errorCode().orElseThrow())
                    .toList());
            entry.put("checksum", familyChecksum(ofFamily));
            families.add(entry);
        }
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("generatedAt", timeProvider.now().value().toString());
        manifest.put("families", families);
        manifest.put("vectors", Map.of("encoding", VECTOR_ENCODING));
        return objectMapper.writeValueAsString(manifest);
    }

    private static String familyChecksum(List<DataExportItem> ofFamily) {
        String concatenated = ofFamily.stream()
                .map(item -> item.checksum().orElse(""))
                .reduce("", (left, right) -> left.isEmpty() ? right : left + "\n" + right);
        return ofFamily.size() == 1 ? concatenated : sha256Hex(concatenated);
    }

    private ExportJobView view(OrganizationId organizationId, DataExportJob job) {
        return new ExportJobView(job, jobs.findItems(organizationId, job.id()));
    }

    private DataExportJob requireJob(OrganizationId organizationId, DataExportJobId jobId) {
        return jobs.lockJobById(organizationId, jobId)
                .orElseThrow(() -> new AggregateNotFoundException(AGGREGATE_TYPE, jobId));
    }

    private UUID appendFact(
            PrincipalId actor,
            OrganizationId organizationId,
            DataExportJob job,
            EventType eventType,
            UUID correlationId,
            DomainEvent payload) {
        UUID eventId = UUID.randomUUID();
        DomainEventEnvelope<DomainEvent> event = new DomainEventEnvelope<>(
                eventId,
                eventType,
                SchemaVersion.V1,
                organizationId,
                Optional.empty(),
                Optional.empty(),
                AggregateReference.of(AGGREGATE_TYPE, job.id()),
                job.version(),
                EventActor.principal(EventActorType.USER, actor),
                correlationId,
                Optional.empty(),
                Optional.empty(),
                timeProvider.now(),
                payload);
        eventStore.append(event);
        outboxRepository.enqueue(PendingOutboxEvent.fromDomain(UUID.randomUUID(), event));
        return eventId;
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 digest is unavailable", unavailable);
        }
    }

    private static String sha256Hex(String value) {
        return HexFormat.of().formatHex(
                sha256Digest().digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    /** Job plus its items — the shape every read/process/cancel path returns. */
    public record ExportJobView(DataExportJob job, List<DataExportItem> items) {

        public ExportJobView {
            job = Objects.requireNonNull(job, "job");
            items = List.copyOf(items);
        }
    }

    /** Completed job plus its items and frozen artifacts — the download face. */
    public record ExportArtifactsView(
            DataExportJob job, List<DataExportItem> items, List<DataExportArtifact> artifacts) {

        public ExportArtifactsView {
            job = Objects.requireNonNull(job, "job");
            items = List.copyOf(items);
            artifacts = List.copyOf(artifacts);
        }
    }
}

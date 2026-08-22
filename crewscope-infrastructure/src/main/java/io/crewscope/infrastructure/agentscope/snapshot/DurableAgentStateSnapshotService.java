package io.crewscope.infrastructure.agentscope.snapshot;

import io.agentscope.core.state.AgentState;
import io.crewscope.application.artifact.ArtifactAccessContext;
import io.crewscope.application.artifact.ArtifactDescriptor;
import io.crewscope.application.artifact.ArtifactMutationContext;
import io.crewscope.application.artifact.ArtifactProducer;
import io.crewscope.application.artifact.ArtifactScope;
import io.crewscope.application.artifact.ArtifactStore;
import io.crewscope.application.artifact.ArtifactTombstoneReason;
import io.crewscope.application.artifact.Sha256Hash;
import io.crewscope.application.execution.TaskAgentStateCheckpointCommand;
import io.crewscope.application.execution.TaskAgentStateCheckpointResult;
import io.crewscope.application.execution.TaskAgentStateIdentity;
import io.crewscope.application.execution.TaskAgentStateRecoveryCommand;
import io.crewscope.application.execution.TaskAgentStateRecoveryResult;
import io.crewscope.application.execution.TaskAgentStateSkipReason;
import io.crewscope.application.execution.TaskAgentStateSkippedSnapshot;
import io.crewscope.application.execution.TaskAgentStateSnapshotService;
import io.crewscope.application.execution.TaskRuntimeEventReceiptRepository;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.task.AgentRunRepository;
import io.crewscope.application.task.AgentStateSnapshotRepository;
import io.crewscope.application.task.ExecutionLeaseRepository;
import io.crewscope.application.task.RuntimeArtifactRepository;
import io.crewscope.application.task.TaskAgentRuntimeSessionRepository;
import io.crewscope.application.transaction.AuthoritativeTimeProvider;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.ArtifactId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.AgentRun;
import io.crewscope.domain.task.AgentRunContinuityGap;
import io.crewscope.domain.task.AgentRunContinuityGapReason;
import io.crewscope.domain.task.AgentStateSnapshot;
import io.crewscope.domain.task.AgentStateSnapshotId;
import io.crewscope.domain.task.AgentStateSnapshotStatus;
import io.crewscope.domain.task.ExecutionLease;
import io.crewscope.domain.task.LeaseOwnership;
import io.crewscope.domain.task.RuntimeArtifact;
import io.crewscope.domain.task.RuntimeArtifactId;
import io.crewscope.domain.task.RuntimeArtifactKind;
import io.crewscope.domain.task.RuntimeContentHash;
import io.crewscope.domain.task.TaskAgentRuntimeSession;
import io.crewscope.infrastructure.agentscope.snapshot.AgentStateSnapshotAdapter.RecoveredState;
import io.crewscope.infrastructure.agentscope.snapshot.AgentStateSnapshotAdapter.RecoveryResult;
import io.crewscope.infrastructure.agentscope.snapshot.AgentStateSnapshotAdapter.RecoveryTarget;
import io.crewscope.infrastructure.agentscope.snapshot.AgentStateSnapshotAdapter.SkipReason;
import io.crewscope.infrastructure.agentscope.snapshot.AgentStateSnapshotAdapter.SnapshotCandidate;
import io.crewscope.infrastructure.agentscope.snapshot.AgentStateSnapshotAdapter.SnapshotIdentity;
import io.crewscope.infrastructure.agentscope.snapshot.AgentStateSnapshotAdapter.WriteRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Production Writer/Reader that closes AgentScope state over Artifact and PostgreSQL facts. */
@Service
public class DurableAgentStateSnapshotService implements TaskAgentStateSnapshotService {

    private final ArtifactStore artifactStore;
    private final AgentStateSnapshotAdapter adapter;
    private final AgentRunRepository runRepository;
    private final TaskAgentRuntimeSessionRepository sessionRepository;
    private final RuntimeArtifactRepository artifactRepository;
    private final AgentStateSnapshotRepository snapshotRepository;
    private final TaskRuntimeEventReceiptRepository receiptRepository;
    private final ExecutionLeaseRepository leaseRepository;
    private final PrincipalRepository principalRepository;
    private final TransactionExecutor transactionExecutor;
    private final AuthoritativeTimeProvider timeProvider;

    public DurableAgentStateSnapshotService(
            ArtifactStore artifactStore,
            AgentRunRepository runRepository,
            TaskAgentRuntimeSessionRepository sessionRepository,
            RuntimeArtifactRepository artifactRepository,
            AgentStateSnapshotRepository snapshotRepository,
            TaskRuntimeEventReceiptRepository receiptRepository,
            ExecutionLeaseRepository leaseRepository,
            PrincipalRepository principalRepository,
            TransactionExecutor transactionExecutor,
            AuthoritativeTimeProvider timeProvider) {
        this.artifactStore = Objects.requireNonNull(artifactStore, "artifactStore");
        this.adapter = new AgentStateSnapshotAdapter(artifactStore);
        this.runRepository = Objects.requireNonNull(runRepository, "runRepository");
        this.sessionRepository = Objects.requireNonNull(sessionRepository, "sessionRepository");
        this.artifactRepository = Objects.requireNonNull(artifactRepository, "artifactRepository");
        this.snapshotRepository = Objects.requireNonNull(snapshotRepository, "snapshotRepository");
        this.receiptRepository = Objects.requireNonNull(receiptRepository, "receiptRepository");
        this.leaseRepository = Objects.requireNonNull(leaseRepository, "leaseRepository");
        this.principalRepository = Objects.requireNonNull(principalRepository, "principalRepository");
        this.transactionExecutor = Objects.requireNonNull(transactionExecutor, "transactionExecutor");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    @Override
    public TaskAgentStateCheckpointResult checkpoint(TaskAgentStateCheckpointCommand command) {
        TaskAgentStateCheckpointCommand required = Objects.requireNonNull(command, "command");
        AgentState state = parseState(required.agentStateJson(), required.identity());
        PreparedBoundary prepared = transactionExecutor.required(() -> prepareBoundary(required));
        ArtifactId artifactId = ArtifactId.generate();
        SnapshotCandidate stored = adapter.write(
                new WriteRequest(
                        artifactId,
                        artifactScope(prepared.run()),
                        producer(prepared.run()),
                        identity(required.identity()),
                        prepared.nextCheckpointSequence(),
                        prepared.capturedAt().value(),
                        Optional.of(required.timeToLive().orElse(
                                AgentStateSnapshotAdapter.DEFAULT_TTL))),
                state);
        try {
            return transactionExecutor.required(() -> publish(required, prepared, stored));
        } catch (RuntimeException failure) {
            tombstoneOrphan(artifactId, prepared.run(), failure);
            throw failure;
        }
    }

    @Override
    public TaskAgentStateRecoveryResult recover(TaskAgentStateRecoveryCommand command) {
        TaskAgentStateRecoveryCommand required = Objects.requireNonNull(command, "command");
        RecoveryView view = transactionExecutor.required(() -> recoveryView(required));
        RecoveredState recovered = adapter.recover(
                new RecoveryTarget(identity(required.identity()), view.committedCheckpointSequence()),
                view.candidates().stream().map(CandidateView::adapterCandidate).toList(),
                access(view.run()));
        RecoveryResult selection = recovered.result();
        List<TaskAgentStateSkippedSnapshot> skipped = skipped(selection, view.candidates());
        invalidateSkipped(required.facts(), view, skipped);
        Optional<AgentRunContinuityGap> gap = selection.continuityGap()
                ? Optional.of(continuityGap(view, selection, skipped))
                : Optional.empty();
        AgentStateSnapshotId restoredId = view.candidates().stream()
                .filter(candidate -> candidate.adapterCandidate().artifactId()
                        .equals(selection.restoredCandidate().artifactId()))
                .map(candidate -> candidate.snapshot().id())
                .findFirst()
                .orElseThrow(() -> invalidRecovery(
                        "restored Artifact is absent from PostgreSQL snapshot candidates"));
        return new TaskAgentStateRecoveryResult(
                recovered.state().toJson(),
                restoredId,
                selection.restoredCandidate().checkpointSequence(),
                gap,
                skipped);
    }

    private PreparedBoundary prepareBoundary(TaskAgentStateCheckpointCommand command) {
        Boundary boundary = loadBoundary(command.facts(), command.identity());
        receiptRepository.find(
                        boundary.run().scope().organizationId(),
                        boundary.run().id(),
                        command.segmentSequence(),
                        command.eventSequence())
                .orElseThrow(() -> new DomainValidationException(
                        "agentStateSnapshot.checkpoint",
                        "requires the referenced AgentRun event receipt to be committed first"));
        Optional<AgentStateSnapshot> latestSession = snapshotRepository.findLatestBySession(
                boundary.run().scope().organizationId(), boundary.session().id());
        Optional<AgentStateSnapshot> latestExecution = snapshotRepository.findLatestByExecution(
                boundary.run().scope().organizationId(), boundary.run().executionId());
        long nextSnapshot = Math.addExact(
                latestExecution.map(AgentStateSnapshot::snapshotSequence).orElse(0L), 1L);
        long nextCheckpoint = Math.addExact(
                latestSession.map(AgentStateSnapshot::checkpointSequence).orElse(0L), 1L);
        return new PreparedBoundary(
                boundary.run(),
                boundary.session(),
                boundary.actor(),
                marker(latestSession),
                marker(latestExecution),
                nextSnapshot,
                nextCheckpoint,
                timeProvider.now());
    }

    private TaskAgentStateCheckpointResult publish(
            TaskAgentStateCheckpointCommand command,
            PreparedBoundary prepared,
            SnapshotCandidate stored) {
        requireCurrentLease(command.facts());
        Boundary current = loadBoundary(command.facts(), command.identity());
        receiptRepository.find(
                        current.run().scope().organizationId(),
                        current.run().id(),
                        command.segmentSequence(),
                        command.eventSequence())
                .orElseThrow(() -> new DomainValidationException(
                        "agentStateSnapshot.checkpoint", "durable event receipt disappeared"));
        Optional<AgentStateSnapshot> latestSession = snapshotRepository.findLatestBySession(
                current.run().scope().organizationId(), current.session().id());
        if (!prepared.latestSessionMarker().equals(marker(latestSession))) {
            throw new DomainValidationException(
                    "agentStateSnapshot.sequence",
                    "another Writer advanced the Session snapshot sequence");
        }
        Optional<AgentStateSnapshot> latestExecution = snapshotRepository.findLatestByExecution(
                current.run().scope().organizationId(), current.run().executionId());
        if (!prepared.latestExecutionMarker().equals(marker(latestExecution))) {
            throw new DomainValidationException(
                    "agentStateSnapshot.sequence",
                    "another Writer advanced the execution snapshot sequence");
        }
        ArtifactDescriptor descriptor = artifactStore.head(stored.artifactId(), access(current.run()))
                .orElseThrow(() -> new AgentStateSnapshotPublicationException(
                        "Published AgentState snapshot descriptor is unavailable"));
        RuntimeArtifact runtimeArtifact = artifactRepository.create(RuntimeArtifact.register(
                RuntimeArtifactId.generate(),
                stored.artifactId(),
                current.run(),
                RuntimeArtifactKind.AGENT_STATE_SNAPSHOT,
                AgentStateSnapshot.CONTENT_TYPE,
                stored.declaredSize(),
                new RuntimeContentHash(stored.expectedArtifactHash().value()),
                descriptor.retentionUntil(),
                current.actor(),
                prepared.capturedAt()));
        AgentStateSnapshot snapshot = AgentStateSnapshot.capture(
                AgentStateSnapshotId.generate(),
                current.session(),
                current.run(),
                runtimeArtifact,
                command.identity().agentName(),
                prepared.nextSnapshotSequence(),
                prepared.nextCheckpointSequence(),
                current.actor(),
                prepared.capturedAt());
        Optional<AgentStateSnapshot> currentSnapshot = snapshotRepository.findCurrentBySession(
                current.run().scope().organizationId(), current.session().id());
        Optional<AgentStateSnapshot> superseded = currentSnapshot.map(value -> value.supersedeBy(
                snapshot, value.version(), current.actor(), prepared.capturedAt()));
        AgentStateSnapshot committed = snapshotRepository.publish(superseded, snapshot);
        return new TaskAgentStateCheckpointResult(
                committed.id(),
                runtimeArtifact.id(),
                committed.snapshotSequence(),
                committed.checkpointSequence(),
                command.safePoint());
    }

    private RecoveryView recoveryView(TaskAgentStateRecoveryCommand command) {
        requireCurrentLease(command.facts());
        Boundary boundary = loadBoundary(command.facts(), command.identity());
        List<AgentStateSnapshot> snapshots = snapshotRepository.findRecoveryCandidates(
                boundary.run().scope().organizationId(),
                boundary.run().id(),
                command.candidateLimit());
        Optional<AgentStateSnapshot> latest = snapshotRepository.findLatestBySession(
                boundary.run().scope().organizationId(), boundary.session().id());
        long committedCheckpoint = latest
                .filter(value -> value.agentRunId().equals(boundary.run().id()))
                .map(AgentStateSnapshot::checkpointSequence)
                .orElseGet(() -> snapshots.stream()
                        .mapToLong(AgentStateSnapshot::checkpointSequence)
                        .max()
                        .orElseThrow(() -> invalidRecovery(
                                "no committed AgentState snapshot metadata is available")));
        List<CandidateView> candidates = snapshots.stream()
                .map(snapshot -> candidate(boundary, command.identity(), snapshot))
                .toList();
        return new RecoveryView(
                boundary.run(), boundary.session(), boundary.actor(), committedCheckpoint, candidates);
    }

    private CandidateView candidate(
            Boundary boundary,
            TaskAgentStateIdentity target,
            AgentStateSnapshot snapshot) {
        requireSnapshotBoundary(boundary, target, snapshot);
        RuntimeArtifact runtimeArtifact = artifactRepository.findById(
                        boundary.run().scope().organizationId(), snapshot.runtimeArtifactId())
                .orElseThrow(() -> new AggregateNotFoundException(
                        "RuntimeArtifact", snapshot.runtimeArtifactId()));
        if (!runtimeArtifact.agentRunId().equals(snapshot.agentRunId())
                || !runtimeArtifact.executionId().equals(snapshot.executionId())
                || !runtimeArtifact.contentHash().equals(snapshot.contentHash())
                || runtimeArtifact.size() != snapshot.size()) {
            throw invalidRecovery("RuntimeArtifact metadata crossed the Snapshot boundary");
        }
        SnapshotCandidate candidate = new SnapshotCandidate(
                runtimeArtifact.artifactId(),
                identity(target),
                snapshot.checkpointSequence(),
                artifactScope(boundary.run()),
                producer(boundary.run()),
                new Sha256Hash(snapshot.contentHash().value()),
                snapshot.size());
        return new CandidateView(snapshot, runtimeArtifact, candidate);
    }

    private Boundary loadBoundary(
            io.crewscope.application.execution.TaskExecutionRuntimeFacts facts,
            TaskAgentStateIdentity identity) {
        var organizationId = facts.task().scope().organizationId();
        AgentRun run = runRepository.findById(organizationId, facts.agentRun().id())
                .orElseThrow(() -> new AggregateNotFoundException("AgentRun", facts.agentRun().id()));
        TaskAgentRuntimeSession session = sessionRepository.findById(
                        organizationId, facts.runtimeSession().id())
                .orElseThrow(() -> new AggregateNotFoundException(
                        "TaskAgentRuntimeSession", facts.runtimeSession().id()));
        Principal actor = principalRepository.findById(organizationId, run.agentPrincipalId())
                .orElseThrow(() -> new AggregateNotFoundException(
                        "Principal", run.agentPrincipalId()));
        boolean current = actor.canAct()
                && run.scope().equals(facts.task().scope())
                && run.taskId().equals(facts.task().id())
                && run.executionId().equals(facts.execution().id())
                && run.stepExecutionId().equals(facts.stepExecution().map(
                        io.crewscope.domain.task.StepExecution::id))
                && run.runtimeSessionId().equals(session.id())
                && run.agentPrincipalId().equals(session.agentPrincipalId())
                && run.agentProfileId().equals(session.agentProfileId())
                && run.agentProfileVersion() == session.agentProfileVersion()
                && session.agentScopeKey().userId().equals(identity.userId())
                && session.agentScopeKey().sessionId().equals(identity.sessionId())
                && identity.taskExecutionId().equals(run.executionId().value())
                && identity.agentRunId().equals(run.id().value())
                && identity.agentVersion().equals(Long.toString(run.agentProfileVersion()))
                && stableAgentId(run, session).equals(identity.agentName())
                && stableAgentId(run, session).equals(identity.agentId());
        if (!current) {
            throw new DomainValidationException(
                    "agentStateSnapshot.identity",
                    "must match the current Task, Run, Session, Agent and Principal boundary");
        }
        return new Boundary(run, session, actor);
    }

    private static void requireSnapshotBoundary(
            Boundary boundary,
            TaskAgentStateIdentity target,
            AgentStateSnapshot snapshot) {
        boolean current = snapshot.scope().equals(boundary.run().scope())
                && snapshot.executionId().equals(boundary.run().executionId())
                && snapshot.agentRunId().equals(boundary.run().id())
                && snapshot.runtimeSessionId().equals(boundary.session().id())
                && snapshot.agentProfileId().equals(boundary.run().agentProfileId())
                && snapshot.agentProfileVersion() == boundary.run().agentProfileVersion()
                && snapshot.agentPrincipalId().equals(boundary.run().agentPrincipalId())
                && snapshot.agentName().equals(target.agentName())
                && snapshot.agentScopeKey().equals(boundary.session().agentScopeKey())
                && snapshot.status() != AgentStateSnapshotStatus.INVALID;
        if (!current) {
            throw invalidRecovery("snapshot candidate identity does not match the recovery target");
        }
    }

    private List<TaskAgentStateSkippedSnapshot> skipped(
            RecoveryResult selection, List<CandidateView> candidates) {
        Map<ArtifactId, CandidateView> byArtifact = new HashMap<>();
        candidates.forEach(value -> byArtifact.put(
                value.adapterCandidate().artifactId(), value));
        return selection.skippedSnapshots().stream().map(value -> {
            CandidateView candidate = Optional.ofNullable(byArtifact.get(value.artifactId()))
                    .orElseThrow(() -> invalidRecovery(
                            "skipped Artifact is absent from PostgreSQL candidates"));
            return new TaskAgentStateSkippedSnapshot(
                    candidate.snapshot().id(),
                    value.checkpointSequence(),
                    map(value.reason()));
        }).toList();
    }

    private void invalidateSkipped(
            io.crewscope.application.execution.TaskExecutionRuntimeFacts facts,
            RecoveryView view,
            List<TaskAgentStateSkippedSnapshot> skipped) {
        Map<AgentStateSnapshotId, CandidateView> bySnapshot = new HashMap<>();
        view.candidates().forEach(value -> bySnapshot.put(value.snapshot().id(), value));
        for (TaskAgentStateSkippedSnapshot rejected : skipped) {
            CandidateView candidate = bySnapshot.get(rejected.snapshotId());
            if (candidate == null) {
                continue;
            }
            try {
                transactionExecutor.required(() -> {
                    requireCurrentLease(facts);
                    AgentStateSnapshot current = snapshotRepository.findById(
                                    view.run().scope().organizationId(), rejected.snapshotId())
                            .orElseThrow(() -> new AggregateNotFoundException(
                                    "AgentStateSnapshot", rejected.snapshotId()));
                    if (current.status() != AgentStateSnapshotStatus.INVALID) {
                        snapshotRepository.update(current.invalidate(
                                reasonCode(rejected.reason()),
                                current.version(),
                                view.actor(),
                                timeProvider.now()));
                    }
                    return null;
                });
                if (rejected.reason() != TaskAgentStateSkipReason.MISSING) {
                    artifactStore.tombstone(
                            candidate.runtimeArtifact().artifactId(),
                            mutation(view.run()),
                            ArtifactTombstoneReason.SECURITY_POLICY,
                            Optional.of("Invalid AgentState snapshot"));
                }
            } catch (RuntimeException ignored) {
                // Recovery already excluded the bytes. A later lifecycle sweep retries cleanup.
            }
        }
    }

    private void requireCurrentLease(
            io.crewscope.application.execution.TaskExecutionRuntimeFacts facts) {
        ExecutionLease expected = facts.lease();
        ExecutionLease locked = leaseRepository.findByIdForUpdate(
                        facts.task().scope().organizationId(),
                        expected.environment(),
                        expected.id())
                .orElseThrow(() -> invalidLease("ExecutionLease no longer exists"));
        LeaseOwnership ownership = new LeaseOwnership(
                expected.taskExecutionId(),
                expected.attempt(),
                expected.runtimeId(),
                expected.workerId(),
                expected.claimTokenHash(),
                expected.fencingToken());
        if (!locked.owns(ownership, timeProvider.now())) {
            throw invalidLease("requires the current active ExecutionLease owner");
        }
    }

    private AgentRunContinuityGap continuityGap(
            RecoveryView view,
            RecoveryResult selection,
            List<TaskAgentStateSkippedSnapshot> skipped) {
        long restored = selection.restoredCandidate().checkpointSequence();
        if (restored >= view.committedCheckpointSequence()) {
            throw invalidRecovery("continuity gap does not describe a missing checkpoint interval");
        }
        AgentStateSnapshotId snapshotId = view.candidates().stream()
                .filter(value -> value.adapterCandidate().artifactId()
                        .equals(selection.restoredCandidate().artifactId()))
                .map(value -> value.snapshot().id())
                .findFirst()
                .orElseThrow();
        return new AgentRunContinuityGap(
                view.run().id(),
                Optional.of(snapshotId),
                Math.addExact(restored, 1),
                view.committedCheckpointSequence(),
                gapReason(skipped),
                timeProvider.now());
    }

    private void tombstoneOrphan(
            ArtifactId artifactId, AgentRun run, RuntimeException original) {
        try {
            artifactStore.tombstone(
                    artifactId,
                    mutation(run),
                    ArtifactTombstoneReason.PUBLICATION_ABORTED,
                    Optional.of("Snapshot metadata publication did not commit"));
        } catch (RuntimeException cleanupFailure) {
            original.addSuppressed(cleanupFailure);
        }
    }

    private static AgentState parseState(String json, TaskAgentStateIdentity identity) {
        try {
            AgentState state = AgentState.fromJsonString(json);
            if (!identity.userId().equals(state.getUserId())
                    || !identity.sessionId().equals(state.getSessionId())) {
                throw new IllegalArgumentException("AgentState identity does not match its slot");
            }
            return state;
        } catch (RuntimeException exception) {
            throw new AgentStateSnapshotPublicationException(
                    "AgentState JSON is invalid for the trusted snapshot identity");
        }
    }

    private static SnapshotIdentity identity(TaskAgentStateIdentity identity) {
        return new SnapshotIdentity(
                identity.taskExecutionId(),
                identity.agentRunId(),
                identity.agentName(),
                identity.agentId(),
                identity.agentVersion(),
                identity.userId(),
                identity.sessionId());
    }

    private static ArtifactScope artifactScope(AgentRun run) {
        return ArtifactScope.workspace(
                run.scope().organizationId(),
                Optional.of(run.scope().teamId()),
                run.scope().workspaceId());
    }

    private static ArtifactProducer producer(AgentRun run) {
        return new ArtifactProducer(
                run.agentPrincipalId(),
                Optional.of(run.executionId().value()),
                run.stepExecutionId().map(value -> value.value()),
                Optional.of(run.id().value()),
                Optional.empty());
    }

    private static String stableAgentId(AgentRun run, TaskAgentRuntimeSession session) {
        // Each Task-side role uses a stable Harness namespace. AgentScope 2.0.0 getAgentId() is
        // process-random and cannot participate in cross-Worker recovery identity.
        return TaskAgentStateIdentity.stableAgentId(
                run.agentProfileId(), run.agentProfileVersion(), session.purpose());
    }

    private static ArtifactAccessContext access(AgentRun run) {
        return new ArtifactAccessContext(
                run.scope().organizationId(),
                run.agentPrincipalId(),
                Set.of(run.scope().teamId()),
                Set.of(run.scope().workspaceId()));
    }

    private static ArtifactMutationContext mutation(AgentRun run) {
        return new ArtifactMutationContext(
                run.scope().organizationId(), run.agentPrincipalId());
    }

    private static Optional<SnapshotMarker> marker(Optional<AgentStateSnapshot> snapshot) {
        return snapshot.map(value -> new SnapshotMarker(
                value.id(), value.snapshotSequence(), value.checkpointSequence(), value.version()));
    }

    private static TaskAgentStateSkipReason map(SkipReason reason) {
        return TaskAgentStateSkipReason.valueOf(reason.name());
    }

    private static String reasonCode(TaskAgentStateSkipReason reason) {
        return "SNAPSHOT_" + reason.name();
    }

    private static AgentRunContinuityGapReason gapReason(
            List<TaskAgentStateSkippedSnapshot> skipped) {
        TaskAgentStateSkipReason reason = skipped.isEmpty()
                ? TaskAgentStateSkipReason.INVALID_ENVELOPE
                : skipped.get(0).reason();
        return reason == TaskAgentStateSkipReason.MISSING
                ? AgentRunContinuityGapReason.SNAPSHOT_MISSING
                : AgentRunContinuityGapReason.SNAPSHOT_CORRUPT;
    }

    private static AgentStateSnapshotRecoveryException invalidRecovery(String message) {
        return new AgentStateSnapshotRecoveryException(message);
    }

    private static DomainValidationException invalidLease(String message) {
        return new DomainValidationException("agentStateSnapshot.lease", message);
    }

    private record Boundary(
            AgentRun run, TaskAgentRuntimeSession session, Principal actor) {}

    private record SnapshotMarker(
            AgentStateSnapshotId id,
            long snapshotSequence,
            long checkpointSequence,
            long version) {}

    private record PreparedBoundary(
            AgentRun run,
            TaskAgentRuntimeSession session,
            Principal actor,
            Optional<SnapshotMarker> latestSessionMarker,
            Optional<SnapshotMarker> latestExecutionMarker,
            long nextSnapshotSequence,
            long nextCheckpointSequence,
            UtcTimestamp capturedAt) {}

    private record CandidateView(
            AgentStateSnapshot snapshot,
            RuntimeArtifact runtimeArtifact,
            SnapshotCandidate adapterCandidate) {}

    private record RecoveryView(
            AgentRun run,
            TaskAgentRuntimeSession session,
            Principal actor,
            long committedCheckpointSequence,
            List<CandidateView> candidates) {

        private RecoveryView {
            candidates = List.copyOf(candidates);
        }
    }
}

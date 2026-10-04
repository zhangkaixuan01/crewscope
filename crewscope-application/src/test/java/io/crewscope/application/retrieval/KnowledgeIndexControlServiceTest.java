package io.crewscope.application.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.coding.RepositoryBindingRepository;
import io.crewscope.application.embedding.EmbeddingBatchResult;
import io.crewscope.application.embedding.TeamEmbeddingCommand;
import io.crewscope.application.knowledge.KnowledgeEntryFilter;
import io.crewscope.application.knowledge.KnowledgeEntryPage;
import io.crewscope.application.knowledge.KnowledgeEntryPageRequest;
import io.crewscope.application.knowledge.KnowledgeEntryVersionPage;
import io.crewscope.application.knowledge.KnowledgeRepository;
import io.crewscope.application.knowledge.KnowledgeVersionPageRequest;
import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.coding.RepositoryBinding;
import io.crewscope.domain.coding.RepositoryBindingId;
import io.crewscope.domain.coding.RepositoryBindingScope;
import io.crewscope.domain.coding.RepositoryBindingStatus;
import io.crewscope.domain.coding.RepositoryBranchName;
import io.crewscope.domain.coding.RepositoryKey;
import io.crewscope.domain.coding.RepositoryKind;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.knowledge.KnowledgeEntry;
import io.crewscope.domain.knowledge.KnowledgeEntryId;
import io.crewscope.domain.knowledge.KnowledgeEntryKey;
import io.crewscope.domain.knowledge.KnowledgeEntryRevision;
import io.crewscope.domain.knowledge.KnowledgeEntryVersion;
import io.crewscope.domain.retrieval.EmbeddingModelRevision;
import io.crewscope.domain.retrieval.RepositoryIndexKey;
import io.crewscope.domain.retrieval.SourceCommit;
import io.crewscope.domain.retrieval.chunking.ChunkingPolicy;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.BuiltInTeamRole;
import io.crewscope.domain.team.MemberRole;
import io.crewscope.domain.team.MemberRoleId;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.team.TeamRole;
import io.crewscope.domain.team.TeamRoleId;
import io.crewscope.domain.team.TeamScope;
import io.crewscope.domain.team.UninitializedTeam;
import io.crewscope.domain.workitem.WorkProjectId;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Control-plane contract of the knowledge index (M10-I01c): the guard split between
 * member-level reads and KNOWLEDGE_MANAGE commands (platform administrators bypass),
 * the four-coordinate RepositoryBinding validation, the three-way cancel semantics
 * including both race outcomes, and the read/list passthrough — with in-memory
 * collaborators only.
 */
final class KnowledgeIndexControlServiceTest {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-10-04T08:00:00Z");
    private static final EmbeddingModelRevision MODEL =
            new EmbeddingModelRevision("text-embedding-v4", 1024, 3);
    private static final SourceCommit COMMIT =
            new SourceCommit("0123456789012345678901234567890123456789");
    private static final Duration LEASE = Duration.ofMinutes(30);

    private final OrganizationId organizationId = OrganizationId.generate();
    private final Principal actor =
            Principal.create(
                    PrincipalId.generate(),
                    PrincipalScope.organization(organizationId),
                    PrincipalType.USER,
                    Optional.empty(),
                    "Owner",
                    Optional.empty(),
                    PrincipalVisibility.ORGANIZATION,
                    NOW);
    private final TeamInitialization initialization = TeamInitialization.create(actor, "Platform", NOW);
    private final TeamId teamId = initialization.team().id();
    private final WorkProjectId projectId = WorkProjectId.generate();
    private final Store store = new Store(initialization, actor);
    private final FakeJobRepository jobs = new FakeJobRepository();
    private final FakeEmbeddingExecutor embeddings = new FakeEmbeddingExecutor();
    private final FakeKnowledgeRepository knowledge = new FakeKnowledgeRepository();
    private final FakeBindingRepository bindings = new FakeBindingRepository();
    private final MutableClock clock = new MutableClock(NOW);

    // ------------------------------------------------------------------ guards

    @Test
    void readsStayAtMemberLevelWithoutKnowledgeManage() {
        store.revokeGrants();
        KnowledgeIndexJob job = seedEntryJob();
        KnowledgeIndexControlService service = service(true);

        assertEquals(job.id(), service.job(access(), organizationId, teamId, job.id()).id());
        KnowledgeIndexJobPage page = service.list(
                access(), organizationId, teamId,
                KnowledgeIndexJobFilter.all(), new KnowledgeIndexJobPageRequest(Optional.empty(), 10));
        assertEquals(List.of(job.id()), page.items().stream().map(KnowledgeIndexJob::id).toList());
    }

    @Test
    void commandsRequireKnowledgeManageUnlessPlatformAdministrator() {
        store.revokeGrants();
        KnowledgeIndexJob job = seedEntryJob();
        KnowledgeIndexControlService service = service(true);

        assertThrows(PolicyDeniedException.class,
                () -> service.cancel(access(), organizationId, teamId, job.id()));

        KnowledgeIndexJob cancelled = service.cancel(
                new TeamAccessContext(actor, true), organizationId, teamId, job.id());
        assertEquals(KnowledgeIndexJobStatus.CANCELLED, cancelled.status(),
                "a platform administrator bypasses the Team permission check");
    }

    @Test
    void foreignOrganizationActorsAndNonMembersAreDenied() {
        KnowledgeIndexJob job = seedEntryJob();
        KnowledgeIndexControlService service = service(true);
        Principal stranger =
                Principal.create(
                        PrincipalId.generate(),
                        PrincipalScope.organization(OrganizationId.generate()),
                        PrincipalType.USER,
                        Optional.empty(),
                        "Stranger",
                        Optional.empty(),
                        PrincipalVisibility.ORGANIZATION,
                        NOW);

        assertThrows(PolicyDeniedException.class,
                () -> service.job(new TeamAccessContext(stranger, false), organizationId, teamId, job.id()));
        assertThrows(PolicyDeniedException.class,
                () -> service.cancel(new TeamAccessContext(stranger, true), organizationId, teamId, job.id()),
                "even platform authority cannot act through a foreign Organization identity");

        store.removeMembers();
        assertThrows(PolicyDeniedException.class,
                () -> service.job(access(), organizationId, teamId, job.id()));
        assertThrows(PolicyDeniedException.class,
                () -> service.rebuild(access(), organizationId, teamId));
    }

    // ------------------------------------------------------------------ rebuild / repository build

    @Test
    void rebuildDelegatesToTheSeedAndReportsJobsCreated() {
        knowledge.effectiveVersions.addAll(List.of(
                versionOf(KnowledgeEntryId.generate(), 1),
                versionOf(KnowledgeEntryId.generate(), 2)));

        assertEquals(2, service(true).rebuild(access(), organizationId, teamId));
        assertEquals(2, jobs.values.size());
        assertEquals(0, service(true).rebuild(access(), organizationId, teamId),
                "a second pass converges onto the live jobs");
        assertEquals(0, service(false).rebuild(access(), organizationId, teamId),
                "a closed refresh gate skips without error");
    }

    @Test
    void repositoryBuildValidatesBindingOwnershipBeforeEnqueue() {
        RepositoryBindingId unknown = RepositoryBindingId.generate();
        KnowledgeIndexControlService open = service(true);

        AggregateNotFoundException missing = assertThrows(
                AggregateNotFoundException.class,
                () -> open.enqueueRepositoryBuild(
                        access(), organizationId, teamId, projectId, unknown, COMMIT));
        assertTrue(missing.getMessage().contains("RepositoryBinding"),
                "the aggregate name must surface: " + missing.getMessage());

        // A binding planted under a foreign Organization misses the four-coordinate
        // lookup — the same not-found shape, never a cross-Team enqueue.
        RepositoryBinding foreign = binding(RepositoryBindingStatus.ACTIVE, OrganizationId.generate());
        bindings.create(foreign);
        assertThrows(AggregateNotFoundException.class,
                () -> open.enqueueRepositoryBuild(
                        access(), organizationId, teamId, projectId, foreign.id(), COMMIT));

        RepositoryBinding disabled = binding(RepositoryBindingStatus.DISABLED);
        bindings.create(disabled);
        DomainValidationException rejected = assertThrows(
                DomainValidationException.class,
                () -> open.enqueueRepositoryBuild(
                        access(), organizationId, teamId, projectId, disabled.id(), COMMIT));
        assertEquals("repositoryIndex.bindingId", rejected.error().details().get("field"));
        assertTrue(jobs.values.isEmpty(), "a rejected build must not leave a job behind");

        // Validation precedes the gate: a closed gate never hides a broken target.
        assertThrows(AggregateNotFoundException.class,
                () -> service(false).enqueueRepositoryBuild(
                        access(), organizationId, teamId, projectId, unknown, COMMIT));
    }

    @Test
    void repositoryBuildEnqueuesOrSkipsWithTheRefreshGate() {
        RepositoryBinding active = binding(RepositoryBindingStatus.ACTIVE);
        bindings.create(active);

        RepositoryBuildEnqueueResult accepted = service(true).enqueueRepositoryBuild(
                access(), organizationId, teamId, projectId, active.id(), COMMIT);
        assertTrue(accepted.accepted());
        KnowledgeIndexJob job = accepted.job().orElseThrow();
        assertEquals(KnowledgeIndexJobSource.REPOSITORY, job.source());
        assertEquals(active.id(), job.indexKey().orElseThrow().repositoryBindingId());
        assertEquals(actor.id(), job.createdBy(), "the job records the requesting member");

        RepositoryBuildEnqueueResult skipped = service(false).enqueueRepositoryBuild(
                access(), organizationId, teamId, projectId, active.id(), COMMIT);
        assertTrue(!skipped.accepted(), "a closed gate is a skip, not an error");
        assertTrue(skipped.job().isEmpty());
        assertEquals(1, jobs.values.size());
    }

    // ------------------------------------------------------------------ cancel

    @Test
    void cancelIsIdempotentForCancelledAndRejectsClaimedOrUnknownJobs() {
        KnowledgeIndexControlService service = service(true);

        assertThrows(KnowledgeIndexJobNotFoundException.class,
                () -> service.cancel(access(), organizationId, teamId, UUID.randomUUID()));

        KnowledgeIndexJob queued = seedEntryJob();
        KnowledgeIndexJob cancelled = service.cancel(access(), organizationId, teamId, queued.id());
        assertEquals(KnowledgeIndexJobStatus.CANCELLED, cancelled.status());
        assertEquals(cancelled.id(), service.cancel(access(), organizationId, teamId, queued.id()).id(),
                "re-cancelling a CANCELLED job replays the snapshot idempotently");

        KnowledgeIndexJob claimed = seedEntryJob();
        jobs.claimNext("worker-a", NOW, LEASE);
        KnowledgeIndexJobNotCancellableException running = assertThrows(
                KnowledgeIndexJobNotCancellableException.class,
                () -> service.cancel(access(), organizationId, teamId, claimed.id()));
        assertEquals(claimed.id(), running.jobId());
        assertEquals(KnowledgeIndexJobStatus.CHUNKING, running.status());
    }

    @Test
    void cancelRacesConvergeByReReadingOnce() {
        // The racing canceller won: our conditional update hit zero rows, but the
        // re-read finds the terminal CANCELLED snapshot and replays it.
        KnowledgeIndexJob elsewhere = seedEntryJob();
        jobs.cancelOutcome = "cancelled-elsewhere";
        KnowledgeIndexJob converged = service(true)
                .cancel(access(), organizationId, teamId, elsewhere.id());
        assertEquals(KnowledgeIndexJobStatus.CANCELLED, converged.status());

        // The claim CTE won: the re-read finds a claimed job and the cancel ends 409.
        KnowledgeIndexJob claimed = seedEntryJob();
        jobs.cancelOutcome = "claim-won";
        KnowledgeIndexJobNotCancellableException fenced = assertThrows(
                KnowledgeIndexJobNotCancellableException.class,
                () -> service(true).cancel(access(), organizationId, teamId, claimed.id()));
        assertEquals(KnowledgeIndexJobStatus.CHUNKING, fenced.status());
    }

    // ------------------------------------------------------------------ job / list

    @Test
    void jobLookupSharesOneNotFoundShapeAcrossTenants() {
        KnowledgeIndexJob strangerJob = jobs.create(KnowledgeIndexJob.knowledgeEntry(
                UUID.randomUUID(), OrganizationId.generate(), TeamId.generate(),
                KnowledgeEntryId.generate(), actor.id(), NOW));
        KnowledgeIndexControlService service = service(true);

        assertThrows(KnowledgeIndexJobNotFoundException.class,
                () -> service.job(access(), organizationId, teamId, strangerJob.id()),
                "a cross-tenant id is indistinguishable from a missing one");
    }

    @Test
    void listPassesFiltersAndCursorsThroughAndRejectsForeignCursors() {
        // Distinct created_at values keep the (created_at, id) keyset order deterministic:
        // a tie would fall through to the random UUID tie-break and flip the pages.
        KnowledgeIndexJob first = seedEntryJob(NOW);
        KnowledgeIndexJob second = seedEntryJob(UtcTimestamp.from(NOW.value().plusSeconds(60)));
        KnowledgeIndexJob build = jobs.create(KnowledgeIndexJob.repositoryBuild(
                UUID.randomUUID(), organizationId, teamId, projectId,
                new RepositoryIndexKey(
                        organizationId, teamId, RepositoryBindingId.generate(), COMMIT,
                        ChunkingPolicy.defaults().policyHash(), MODEL),
                actor.id(), UtcTimestamp.from(NOW.value().plusSeconds(120))));
        KnowledgeIndexControlService service = service(true);

        KnowledgeIndexJobPage page = service.list(
                access(), organizationId, teamId, KnowledgeIndexJobFilter.all(),
                new KnowledgeIndexJobPageRequest(Optional.empty(), 2));
        assertEquals(List.of(first.id(), second.id()),
                page.items().stream().map(KnowledgeIndexJob::id).toList());
        KnowledgeIndexJobPage tail = service.list(
                access(), organizationId, teamId, KnowledgeIndexJobFilter.all(),
                new KnowledgeIndexJobPageRequest(page.nextAfterJobId(), 2));
        assertEquals(List.of(build.id()), tail.items().stream().map(KnowledgeIndexJob::id).toList());
        assertTrue(tail.nextAfterJobId().isEmpty());

        KnowledgeIndexJobPage repositoryOnly = service.list(
                access(), organizationId, teamId,
                new KnowledgeIndexJobFilter(
                        Optional.of(KnowledgeIndexJobSource.REPOSITORY), Optional.empty()),
                new KnowledgeIndexJobPageRequest(Optional.empty(), 10));
        assertEquals(List.of(build.id()),
                repositoryOnly.items().stream().map(KnowledgeIndexJob::id).toList());

        assertThrows(IllegalArgumentException.class,
                () -> service.list(access(), organizationId, teamId,
                        KnowledgeIndexJobFilter.all(),
                        new KnowledgeIndexJobPageRequest(Optional.of(UUID.randomUUID()), 10)),
                "a cursor that resolves to nothing is rejected before paging");
        KnowledgeIndexJob strangerJob = jobs.create(KnowledgeIndexJob.knowledgeEntry(
                UUID.randomUUID(), OrganizationId.generate(), TeamId.generate(),
                KnowledgeEntryId.generate(), actor.id(), NOW));
        assertThrows(IllegalArgumentException.class,
                () -> service.list(access(), organizationId, teamId,
                        KnowledgeIndexJobFilter.all(),
                        new KnowledgeIndexJobPageRequest(Optional.of(strangerJob.id()), 10)),
                "a cursor foreign to this Team is rejected before paging");
    }

    // ------------------------------------------------------------------ fixtures

    private TeamAccessContext access() {
        return new TeamAccessContext(actor, false);
    }

    private KnowledgeIndexControlService service(boolean refreshEnabled) {
        return new KnowledgeIndexControlService(
                new KnowledgeIndexJobService(jobs, embeddings, knowledge, clock, refreshEnabled),
                jobs,
                bindings,
                store,
                store,
                store.rolesView(),
                store,
                new DirectTransactionExecutor(),
                clock);
    }

    private KnowledgeIndexJob seedEntryJob() {
        return seedEntryJob(NOW);
    }

    private KnowledgeIndexJob seedEntryJob(UtcTimestamp createdAt) {
        return jobs.create(KnowledgeIndexJob.knowledgeEntry(
                UUID.randomUUID(), organizationId, teamId, KnowledgeEntryId.generate(),
                actor.id(), createdAt));
    }

    private RepositoryBinding binding(RepositoryBindingStatus status) {
        return binding(status, organizationId);
    }

    private RepositoryBinding binding(RepositoryBindingStatus status, OrganizationId owner) {
        RepositoryBindingId id = RepositoryBindingId.generate();
        return RepositoryBinding.reconstitute(
                id,
                new RepositoryBindingScope(owner, teamId, WorkspaceId.generate(), projectId),
                RepositoryKind.LOCAL_MANAGED,
                new RepositoryKey("repo-" + id.value()),
                new RepositoryBranchName("main"),
                status,
                0,
                AuditMetadata.createdBy(actor.id(), NOW));
    }

    private KnowledgeEntryVersion versionOf(KnowledgeEntryId entryId, long revision) {
        return KnowledgeEntryVersion.create(
                entryId,
                new TeamScope(organizationId, teamId),
                new KnowledgeEntryRevision(revision),
                revision == 1
                        ? Optional.empty()
                        : Optional.of(new KnowledgeEntryRevision(revision - 1)),
                "Title " + revision,
                "Content " + revision,
                PrincipalId.generate(),
                NOW);
    }

    /** Clock the tests advance explicitly; the control plane only reads it. */
    private static final class MutableClock implements TimeProvider {
        private UtcTimestamp now;

        private MutableClock(UtcTimestamp now) {
            this.now = now;
        }

        @Override
        public UtcTimestamp now() {
            return now;
        }
    }

    private static final class DirectTransactionExecutor implements TransactionExecutor {
        @Override
        public <T> T required(Supplier<T> operation) {
            return operation.get();
        }
    }

    /** The guard collaborator trio of the distillation Store, trimmed to this slice. */
    private static final class Store
            implements TeamRepository, TeamMembershipQuery, MemberRoleRepository {

        private final TeamInitialization initialization;
        private List<TeamMember> members;
        private List<TeamRole> roles;
        private List<MemberRole> grants;

        private Store(TeamInitialization initialization, Principal actor) {
            this.initialization = initialization;
            this.members = List.of(initialization.ownerMember());
            TeamRole ownerRole = TeamRole.createBuiltIn(
                    TeamRoleId.generate(), initialization.team().scope(),
                    BuiltInTeamRole.TEAM_OWNER, NOW);
            this.roles = List.of(ownerRole);
            this.grants = List.of(MemberRole.grantOwner(
                    MemberRoleId.generate(), initialization.team(),
                    initialization.ownerMember(), ownerRole, actor.id(), NOW));
        }

        TeamRoleRepository rolesView() {
            return new TeamRoleRepository() {
                @Override
                public List<TeamRole> createAll(List<TeamRole> values) {
                    roles = List.copyOf(values);
                    return roles;
                }

                @Override
                public List<TeamRole> findByTeam(OrganizationId organization, TeamId team) {
                    return roles;
                }
            };
        }

        void revokeGrants() {
            grants = List.of();
        }

        void removeMembers() {
            members = List.of();
        }

        @Override
        public Team create(Team team) {
            return team;
        }

        @Override
        public Optional<Team> findById(OrganizationId organizationId, TeamId id) {
            return Optional.of(initialization.team())
                    .filter(team -> team.organizationId().equals(organizationId)
                            && team.id().equals(id));
        }

        @Override
        public Optional<UninitializedTeam> findUninitializedById(OrganizationId organizationId, TeamId id) {
            return Optional.empty();
        }

        @Override
        public List<TeamMember> findByTeam(OrganizationId organization, TeamId team) {
            return members;
        }

        @Override
        public MemberRole create(MemberRole memberRole) {
            grants = new ArrayList<>(grants);
            grants.add(memberRole);
            return memberRole;
        }

        @Override
        public List<MemberRole> findByMember(OrganizationId organizationId, TeamMemberId memberId) {
            return grants;
        }
    }

    /** Governance seam of the enqueue service: a fixed model always resolves. */
    private static final class FakeEmbeddingExecutor implements KnowledgeEmbeddingExecutor {
        private EmbeddingModelRevision model = MODEL;

        @Override
        public EmbeddingModelRevision resolveModel(OrganizationId organizationId, TeamId teamId) {
            return model;
        }

        @Override
        public EmbeddingBatchResult embed(TeamEmbeddingCommand command) {
            throw new UnsupportedOperationException("control-plane tests never embed");
        }
    }

    /** Only the rebuild seed is exercised; every other read fails the test loudly. */
    private static final class FakeKnowledgeRepository implements KnowledgeRepository {
        private final List<KnowledgeEntryVersion> effectiveVersions = new ArrayList<>();

        @Override
        public KnowledgeEntry create(KnowledgeEntry entry) {
            throw new UnsupportedOperationException();
        }

        @Override
        public KnowledgeEntry save(
                KnowledgeEntry entry, Optional<KnowledgeEntryVersion> appendedVersion) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<KnowledgeEntry> findById(
                OrganizationId organizationId, TeamId teamId, KnowledgeEntryId entryId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<KnowledgeEntry> findByKey(
                OrganizationId organizationId, TeamId teamId, KnowledgeEntryKey entryKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public KnowledgeEntryPage findByTeam(
                OrganizationId organizationId,
                TeamId teamId,
                KnowledgeEntryFilter filter,
                KnowledgeEntryPageRequest pageRequest) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<KnowledgeEntryVersion> findVersion(
                OrganizationId organizationId,
                TeamId teamId,
                KnowledgeEntryId entryId,
                KnowledgeEntryRevision revision) {
            throw new UnsupportedOperationException();
        }

        @Override
        public KnowledgeEntryVersionPage findVersionHistory(
                OrganizationId organizationId,
                TeamId teamId,
                KnowledgeEntryId entryId,
                KnowledgeVersionPageRequest pageRequest) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<KnowledgeEntryVersion> findEffectiveVersion(
                OrganizationId organizationId, TeamId teamId, KnowledgeEntryId entryId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<KnowledgeEntryVersion> findEffectiveVersionsByTeam(
                OrganizationId organizationId, TeamId teamId) {
            return List.copyOf(effectiveVersions);
        }
    }

    /** Four-coordinate lookup plus the cancel-race knobs; worker writes fail loudly. */
    private static final class FakeJobRepository implements KnowledgeIndexJobRepository {
        private final Map<UUID, KnowledgeIndexJob> values = new LinkedHashMap<>();

        /** null = normal; "claim-won"/"cancelled-elsewhere" simulate cancel races. */
        private String cancelOutcome;

        @Override
        public KnowledgeIndexJob create(KnowledgeIndexJob job) {
            values.put(job.id(), job);
            return job;
        }

        @Override
        public Optional<KnowledgeIndexJob> findById(
                OrganizationId organizationId, TeamId teamId, UUID jobId) {
            KnowledgeIndexJob job = values.get(jobId);
            return job != null
                    && job.organizationId().equals(organizationId)
                    && job.teamId().equals(teamId)
                    ? Optional.of(job) : Optional.empty();
        }

        @Override
        public Optional<KnowledgeIndexJob> findLiveByEntry(
                OrganizationId organizationId, TeamId teamId, KnowledgeEntryId entryId) {
            return values.values().stream()
                    .filter(job -> job.source() == KnowledgeIndexJobSource.KNOWLEDGE_ENTRY
                            && job.organizationId().equals(organizationId)
                            && job.teamId().equals(teamId)
                            && job.entryId().equals(Optional.of(entryId))
                            && !job.status().terminal())
                    .min(Comparator.comparing(KnowledgeIndexJob::createdAt)
                            .thenComparing(job -> job.id().toString()));
        }

        @Override
        public Optional<KnowledgeIndexJob> findLiveByIndexKey(RepositoryIndexKey indexKey) {
            return values.values().stream()
                    .filter(job -> job.source() == KnowledgeIndexJobSource.REPOSITORY
                            && job.indexKey().equals(Optional.of(indexKey))
                            && !job.status().terminal())
                    .min(Comparator.comparing(KnowledgeIndexJob::createdAt)
                            .thenComparing(job -> job.id().toString()));
        }

        @Override
        public Optional<KnowledgeIndexJob> findLatestByEntry(
                OrganizationId organizationId, TeamId teamId, KnowledgeEntryId entryId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public KnowledgeIndexJobPage findByTeam(
                OrganizationId organizationId,
                TeamId teamId,
                KnowledgeIndexJobFilter filter,
                KnowledgeIndexJobPageRequest pageRequest) {
            List<KnowledgeIndexJob> ordered = values.values().stream()
                    .filter(job -> job.organizationId().equals(organizationId)
                            && job.teamId().equals(teamId))
                    .sorted(Comparator.comparing(KnowledgeIndexJob::createdAt)
                            .thenComparing(job -> job.id().toString()))
                    .toList();
            int start = 0;
            if (pageRequest.afterJobId().isPresent()) {
                UUID after = pageRequest.afterJobId().orElseThrow();
                List<UUID> ids = ordered.stream().map(KnowledgeIndexJob::id).toList();
                int index = ids.indexOf(after);
                if (index < 0) {
                    throw new IllegalArgumentException(
                            "cursor job " + after + " is not part of this Team's jobs");
                }
                start = index + 1;
            }
            List<KnowledgeIndexJob> candidates = ordered.subList(start, ordered.size()).stream()
                    .filter(job -> filter.source().map(job.source()::equals).orElse(true))
                    .filter(job -> filter.status().map(job.status()::equals).orElse(true))
                    .limit(pageRequest.limit() + 1L)
                    .toList();
            if (candidates.size() <= pageRequest.limit()) {
                return new KnowledgeIndexJobPage(candidates, Optional.empty());
            }
            List<KnowledgeIndexJob> items = candidates.subList(0, pageRequest.limit());
            return new KnowledgeIndexJobPage(
                    items, Optional.of(items.get(items.size() - 1).id()));
        }

        @Override
        public Optional<KnowledgeIndexJob> claimNext(
                String owner, UtcTimestamp now, Duration leaseDuration) {
            Optional<KnowledgeIndexJob> candidate = values.values().stream()
                    .filter(job -> !job.status().terminal()
                            && (job.status() == KnowledgeIndexJobStatus.QUEUED
                                    || job.leaseExpiresAt()
                                            .map(lease -> lease.value()
                                                    .isBefore(now.value()))
                                            .orElse(false)))
                    .min(Comparator.comparing(KnowledgeIndexJob::createdAt)
                            .thenComparing(job -> job.id().toString()));
            if (candidate.isEmpty()) {
                return Optional.empty();
            }
            KnowledgeIndexJob claimed = candidate.get().asClaimed(
                    owner, UtcTimestamp.from(now.value().plus(leaseDuration)), now);
            values.put(claimed.id(), claimed);
            return Optional.of(claimed);
        }

        @Override
        public Optional<KnowledgeIndexJob> updateClaimed(
                KnowledgeIndexJob job, String owner, UtcTimestamp now, Duration leaseDuration) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean insertCheckpoint(
                UUID jobId, long claimToken, int chunkSeq, int chunkCount) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int maxCheckpointSeq(UUID jobId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<KnowledgeIndexJob> cancelQueued(
                KnowledgeIndexJob job, UtcTimestamp cancelledAt) {
            if ("claim-won".equals(cancelOutcome)) {
                values.put(job.id(), job.asClaimed(
                        "worker-a", UtcTimestamp.from(NOW.value().plus(LEASE)), NOW));
                return Optional.empty();
            }
            KnowledgeIndexJob stored = values.get(job.id());
            if (stored == null || stored.status() != KnowledgeIndexJobStatus.QUEUED) {
                return Optional.empty();
            }
            KnowledgeIndexJob cancelled =
                    stored.cancelled(KnowledgeIndexFailureCodes.CANCELLED, cancelledAt);
            values.put(cancelled.id(), cancelled);
            if ("cancelled-elsewhere".equals(cancelOutcome)) {
                return Optional.empty();
            }
            return Optional.of(cancelled);
        }
    }

    /** Four-coordinate tenant-scoped lookup over reconstituted bindings. */
    private static final class FakeBindingRepository implements RepositoryBindingRepository {
        private final Map<RepositoryBindingId, RepositoryBinding> values = new LinkedHashMap<>();

        @Override
        public RepositoryBinding create(RepositoryBinding binding) {
            values.put(binding.id(), binding);
            return binding;
        }

        @Override
        public RepositoryBinding update(RepositoryBinding binding) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<RepositoryBinding> findById(
                OrganizationId organizationId,
                TeamId teamId,
                WorkProjectId workProjectId,
                RepositoryBindingId bindingId) {
            return Optional.ofNullable(values.get(bindingId))
                    .filter(binding -> binding.scope().organizationId().equals(organizationId)
                            && binding.scope().teamId().equals(teamId)
                            && binding.scope().workProjectId().equals(workProjectId));
        }

        @Override
        public Optional<RepositoryBinding> findByKey(
                OrganizationId organizationId,
                TeamId teamId,
                WorkProjectId workProjectId,
                RepositoryKey repositoryKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<RepositoryBinding> findByWorkProject(
                OrganizationId organizationId, TeamId teamId, WorkProjectId workProjectId) {
            throw new UnsupportedOperationException();
        }
    }
}

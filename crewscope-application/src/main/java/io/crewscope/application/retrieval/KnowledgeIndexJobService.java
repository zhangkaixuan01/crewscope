package io.crewscope.application.retrieval;

import io.crewscope.application.knowledge.KnowledgeRepository;
import io.crewscope.domain.coding.RepositoryBindingId;
import io.crewscope.domain.knowledge.KnowledgeEntryId;
import io.crewscope.domain.retrieval.RepositoryIndexKey;
import io.crewscope.domain.retrieval.SourceCommit;
import io.crewscope.domain.retrieval.chunking.ChunkingPolicy;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Enqueue face of the knowledge index (M10-I01b). Refresh-class enqueues (entry refresh,
 * repository build, team rebuild) are gated by {@code crewscope.knowledge.index.enabled}
 * and return empty while the gate is closed — a skip, not an error. Cleanup enqueues are
 * never gated (§10.7: retirement and deletion keep draining vectors). Repository builds
 * resolve the Team's embedding model through the governance chain at enqueue time and
 * freeze it into the index key; a Team without a usable model fails fast.
 *
 * <p>Enqueue is idempotent per target: while a live job exists for the same entry or
 * index coordinate, it is returned instead of creating a second one. Concurrent
 * creates for the same target race on the partial unique indexes; the loser's
 * {@link KnowledgeIndexJobLiveConflictException} converges onto the winner through
 * the same find-live lookups, so the race never surfaces as an error.
 */
public final class KnowledgeIndexJobService {

    private final KnowledgeIndexJobRepository jobs;
    private final KnowledgeEmbeddingExecutor embeddings;
    private final KnowledgeRepository knowledge;
    private final TimeProvider timeProvider;
    private final boolean refreshEnabled;

    public KnowledgeIndexJobService(
            KnowledgeIndexJobRepository jobs,
            KnowledgeEmbeddingExecutor embeddings,
            KnowledgeRepository knowledge,
            TimeProvider timeProvider,
            boolean refreshEnabled) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.embeddings = Objects.requireNonNull(embeddings, "embeddings");
        this.knowledge = Objects.requireNonNull(knowledge, "knowledge");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
        this.refreshEnabled = refreshEnabled;
    }

    /** Whether refresh-class enqueues are accepted right now. */
    public boolean refreshEnabled() {
        return refreshEnabled;
    }

    /**
     * Enqueues re-indexing of one entry. Empty means the refresh gate is closed; the
     * effective revision is intentionally not captured here — the worker re-reads it at
     * claim time through the authoritative gate.
     */
    public Optional<KnowledgeIndexJob> enqueueEntryRefresh(
            OrganizationId organizationId, TeamId teamId, KnowledgeEntryId entryId,
            PrincipalId actor) {
        if (!refreshEnabled) {
            return Optional.empty();
        }
        return Optional.of(enqueueEntryJob(organizationId, teamId, entryId, actor));
    }

    /**
     * Enqueues index invalidation of one entry (retirement or deletion). Never gated:
     * cleanup must keep draining vectors even while refreshes are disabled.
     */
    public KnowledgeIndexJob enqueueEntryCleanup(
            OrganizationId organizationId, TeamId teamId, KnowledgeEntryId entryId,
            PrincipalId actor) {
        return enqueueEntryJob(organizationId, teamId, entryId, actor);
    }

    /**
     * Enqueues one repository index build: resolves the embedding model now, freezes it
     * together with the default chunking policy hash into the index key. Empty means
     * the refresh gate is closed; a Team without a usable model throws.
     */
    public Optional<KnowledgeIndexJob> enqueueRepositoryBuild(
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId projectId,
            RepositoryBindingId bindingId,
            SourceCommit commit,
            PrincipalId actor) {
        if (!refreshEnabled) {
            return Optional.empty();
        }
        RepositoryIndexKey indexKey = new RepositoryIndexKey(
                organizationId,
                teamId,
                Objects.requireNonNull(bindingId, "bindingId"),
                Objects.requireNonNull(commit, "commit"),
                ChunkingPolicy.defaults().policyHash(),
                embeddings.resolveModel(organizationId, teamId));
        return Optional.of(enqueueRepositoryJob(
                organizationId, teamId, projectId, indexKey, actor));
    }

    /** Seeds a refresh job for every effective version of one Team; returns jobs created. */
    public int rebuildTeamKnowledge(
            OrganizationId organizationId, TeamId teamId, PrincipalId actor) {
        if (!refreshEnabled) {
            return 0;
        }
        Objects.requireNonNull(actor, "actor");
        int created = 0;
        for (var version : knowledge.findEffectiveVersionsByTeam(organizationId, teamId)) {
            if (jobs.findLiveByEntry(organizationId, teamId, version.entryId()).isEmpty()) {
                try {
                    jobs.create(KnowledgeIndexJob.knowledgeEntry(
                            UUID.randomUUID(), organizationId, teamId, version.entryId(),
                            actor, now()));
                    created++;
                } catch (KnowledgeIndexJobLiveConflictException raceLost) {
                    // The invalidation consumer raced this entry's live slot and won;
                    // its job is the winner and this entry does not count as created.
                }
            }
        }
        return created;
    }

    /** Cancels a still-QUEUED job; empty when not found or already claimed. */
    public Optional<KnowledgeIndexJob> cancel(
            OrganizationId organizationId, TeamId teamId, UUID jobId) {
        return jobs.findById(organizationId, teamId, jobId)
                .flatMap(job -> jobs.cancelQueued(job, timeProvider.now()));
    }

    private KnowledgeIndexJob enqueueEntryJob(
            OrganizationId organizationId, TeamId teamId, KnowledgeEntryId entryId,
            PrincipalId actor) {
        Optional<KnowledgeIndexJob> live = jobs.findLiveByEntry(
                organizationId, teamId, Objects.requireNonNull(entryId, "entryId"));
        if (live.isPresent()) {
            return live.get();
        }
        try {
            return jobs.create(KnowledgeIndexJob.knowledgeEntry(
                    UUID.randomUUID(), organizationId, teamId, entryId,
                    Objects.requireNonNull(actor, "actor"), now()));
        } catch (KnowledgeIndexJobLiveConflictException raceLost) {
            return jobs.findLiveByEntry(organizationId, teamId, entryId)
                    .orElseThrow(() -> raceLost);
        }
    }

    private KnowledgeIndexJob enqueueRepositoryJob(
            OrganizationId organizationId, TeamId teamId, WorkProjectId projectId,
            RepositoryIndexKey indexKey, PrincipalId actor) {
        Optional<KnowledgeIndexJob> live = jobs.findLiveByIndexKey(indexKey);
        if (live.isPresent()) {
            return live.get();
        }
        try {
            return jobs.create(KnowledgeIndexJob.repositoryBuild(
                    UUID.randomUUID(), organizationId, teamId,
                    Objects.requireNonNull(projectId, "projectId"), indexKey,
                    Objects.requireNonNull(actor, "actor"), now()));
        } catch (KnowledgeIndexJobLiveConflictException raceLost) {
            return jobs.findLiveByIndexKey(indexKey).orElseThrow(() -> raceLost);
        }
    }

    private UtcTimestamp now() {
        return timeProvider.now();
    }
}

package io.crewscope.infrastructure.persistence.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.event.publication.EventPublication;
import io.crewscope.application.knowledge.KnowledgeEntryFilter;
import io.crewscope.application.knowledge.KnowledgeEntryPage;
import io.crewscope.application.knowledge.KnowledgeEntryPageRequest;
import io.crewscope.application.knowledge.KnowledgeEntryVersionPage;
import io.crewscope.application.knowledge.KnowledgeRepository;
import io.crewscope.application.knowledge.KnowledgeVersionPageRequest;
import io.crewscope.application.retrieval.KnowledgeEmbeddingExecutor;
import io.crewscope.application.retrieval.KnowledgeIndexJob;
import io.crewscope.application.retrieval.KnowledgeIndexJobFilter;
import io.crewscope.application.retrieval.KnowledgeIndexJobPage;
import io.crewscope.application.retrieval.KnowledgeIndexJobPageRequest;
import io.crewscope.application.retrieval.KnowledgeIndexJobRepository;
import io.crewscope.application.retrieval.KnowledgeIndexJobService;
import io.crewscope.application.retrieval.KnowledgeIndexJobSource;
import io.crewscope.application.retrieval.KnowledgeIndexJobStatus;
import io.crewscope.domain.knowledge.KnowledgeEntry;
import io.crewscope.domain.knowledge.KnowledgeEntryId;
import io.crewscope.domain.knowledge.KnowledgeEntryKey;
import io.crewscope.domain.knowledge.KnowledgeEntryRevision;
import io.crewscope.domain.knowledge.KnowledgeEntryVersion;
import io.crewscope.domain.knowledge.event.KnowledgeEntryCreated;
import io.crewscope.domain.knowledge.event.KnowledgeEntryDeleted;
import io.crewscope.domain.knowledge.event.KnowledgeVersionPublished;
import io.crewscope.domain.knowledge.event.KnowledgeVersionRetired;
import io.crewscope.domain.retrieval.RepositoryIndexKey;
import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.event.EventActor;
import io.crewscope.domain.shared.event.EventActorType;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkProjectId;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumer contract of the knowledge index (M10-I01b): the four knowledge lifecycle
 * events enqueue the right job class for their entry, unrelated events and actorless
 * service events are ignored, and the refresh gate skips refresh enqueues while
 * cleanup enqueues flow regardless.
 */
class KnowledgeIndexInvalidationConsumerTest {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-10-03T08:00:00Z");

    private final OrganizationId organizationId = OrganizationId.generate();
    private final TeamId teamId = TeamId.generate();
    private final PrincipalId actor = PrincipalId.generate();
    private final RecordingJobRepository jobs = new RecordingJobRepository();

    // ------------------------------------------------------------------ dispatch

    @org.junit.jupiter.api.Test
    void enqueuesRefreshJobsForCreationAndPublication() {
        KnowledgeIndexInvalidationConsumer consumer = consumer(true);
        UUID createdEntry = entry();
        UUID publishedEntry = entry();

        consumer.consume(publication(envelope(
                new KnowledgeEntryCreated(createdEntry, "onboarding", "Onboarding", "RUNBOOK"),
                EventActor.principal(EventActorType.USER, actor))));
        consumer.consume(publication(envelope(
                new KnowledgeVersionPublished(publishedEntry, "onboarding", 2, "a".repeat(64), "v2"),
                EventActor.principal(EventActorType.USER, actor))));

        assertEquals("knowledge-index-invalidation-v1", consumer.consumerName());
        assertEquals(2, jobs.created.size());
        assertTrue(jobs.created.stream()
                .allMatch(job -> job.source() == KnowledgeIndexJobSource.KNOWLEDGE_ENTRY
                        && job.status() == KnowledgeIndexJobStatus.QUEUED
                        && job.claimedBy().isEmpty()));
        assertEquals(
                List.of(createdEntry, publishedEntry),
                jobs.created.stream().map(job -> job.entryId().orElseThrow().value()).toList(),
                "each event targets its own entry");
    }

    @org.junit.jupiter.api.Test
    void enqueuesCleanupJobsForRetirementAndDeletion() {
        KnowledgeIndexInvalidationConsumer consumer = consumer(true);

        consumer.consume(publication(envelope(
                new KnowledgeVersionRetired(entry(), "onboarding", 1, "a".repeat(64)),
                EventActor.principal(EventActorType.USER, actor))));
        consumer.consume(publication(envelope(
                new KnowledgeEntryDeleted(entry(), "onboarding", 1),
                EventActor.principal(EventActorType.USER, actor))));

        assertEquals(2, jobs.created.size(),
                "cleanup jobs enqueue even without any live vector row");
    }

    @org.junit.jupiter.api.Test
    void ignoresOtherAggregatesAndActorlessServiceEvents() {
        KnowledgeIndexInvalidationConsumer consumer = consumer(true);

        consumer.consume(publication("""
                {"eventType":"FINAL_DIFF_ARTIFACT_PUBLISHED","payload":{}}
                """));
        consumer.consume(publication(envelope(
                new KnowledgeVersionPublished(entry(), "onboarding", 3, "b".repeat(64), "v3"),
                EventActor.anonymousService())));

        assertTrue(jobs.created.isEmpty(),
                "neither foreign aggregates nor actorless events enqueue a job");
    }

    @org.junit.jupiter.api.Test
    void aClosedRefreshGateSkipsRefreshButNeverCleanup() {
        KnowledgeIndexInvalidationConsumer consumer = consumer(false);

        consumer.consume(publication(envelope(
                new KnowledgeVersionPublished(entry(), "onboarding", 4, "c".repeat(64), "v4"),
                EventActor.principal(EventActorType.USER, actor))));
        assertTrue(jobs.created.isEmpty(),
                "a closed gate skips the refresh enqueue without failing the dispatch");

        consumer.consume(publication(envelope(
                new KnowledgeEntryDeleted(entry(), "onboarding", 4),
                EventActor.principal(EventActorType.USER, actor))));
        assertEquals(1, jobs.created.size(),
                "cleanup keeps draining vectors while the gate is closed");
    }

    // ------------------------------------------------------------------ fixtures

    private KnowledgeIndexInvalidationConsumer consumer(boolean refreshEnabled) {
        return new KnowledgeIndexInvalidationConsumer(
                JsonMapper.builder().findAndAddModules().build(),
                new KnowledgeIndexJobService(
                        jobs,
                        new FixedModelExecutor(),
                        new UnsupportedKnowledgeRepository(),
                        () -> NOW,
                        refreshEnabled));
    }

    private static UUID entry() {
        return UUID.randomUUID();
    }

    private <T extends DomainEvent> String envelope(T payload, EventActor eventActor) {
        return new io.crewscope.application.event.json.DomainEventEnvelopeJsonCodec(
                JsonMapper.builder().findAndAddModules().build())
                .encode(new DomainEventEnvelope<>(
                        UUID.randomUUID(),
                        io.crewscope.domain.shared.event.EventType.from(
                                eventTypeOf(payload)),
                        io.crewscope.domain.shared.event.SchemaVersion.from("1"),
                        organizationId,
                        Optional.of(teamId),
                        Optional.empty(),
                        new io.crewscope.domain.shared.event.AggregateReference(
                                "KNOWLEDGE_ENTRY", UUID.randomUUID()),
                        1L,
                        eventActor,
                        UUID.randomUUID(),
                        Optional.empty(),
                        Optional.empty(),
                        NOW,
                        payload));
    }

    private static String eventTypeOf(DomainEvent payload) {
        if (payload instanceof KnowledgeEntryCreated) {
            return KnowledgeIndexInvalidationConsumer.ENTRY_CREATED;
        }
        if (payload instanceof KnowledgeVersionPublished) {
            return KnowledgeIndexInvalidationConsumer.VERSION_PUBLISHED;
        }
        if (payload instanceof KnowledgeVersionRetired) {
            return KnowledgeIndexInvalidationConsumer.VERSION_RETIRED;
        }
        if (payload instanceof KnowledgeEntryDeleted) {
            return KnowledgeIndexInvalidationConsumer.ENTRY_DELETED;
        }
        return "UNRELATED_EVENT";
    }

    private static EventPublication publication(String eventJson) {
        return new EventPublication(
                UUID.randomUUID(), UUID.randomUUID(), "crewscope.events", "partition", 1,
                NOW, eventJson);
    }

    private static final class FixedModelExecutor implements KnowledgeEmbeddingExecutor {
        @Override
        public io.crewscope.domain.retrieval.EmbeddingModelRevision resolveModel(
                OrganizationId organizationId, TeamId teamId) {
            return new io.crewscope.domain.retrieval.EmbeddingModelRevision(
                    "text-embedding-v4", 1024, 1);
        }

        @Override
        public io.crewscope.application.embedding.EmbeddingBatchResult embed(
                io.crewscope.application.embedding.TeamEmbeddingCommand command) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class UnsupportedKnowledgeRepository implements KnowledgeRepository {
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
            throw new UnsupportedOperationException();
        }
    }

    /** Records created jobs; only the enqueue surface of the port is exercised. */
    private static final class RecordingJobRepository implements KnowledgeIndexJobRepository {
        private final Map<UUID, KnowledgeIndexJob> values = new LinkedHashMap<>();
        private final List<KnowledgeIndexJob> created = new ArrayList<>();

        @Override
        public KnowledgeIndexJob create(KnowledgeIndexJob job) {
            values.put(job.id(), job);
            created.add(job);
            return job;
        }

        @Override
        public Optional<KnowledgeIndexJob> findById(
                OrganizationId organizationId, TeamId teamId, UUID jobId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<KnowledgeIndexJob> findLiveByEntry(
                OrganizationId organizationId, TeamId teamId, KnowledgeEntryId entryId) {
            return values.values().stream()
                    .filter(job -> !job.status().terminal())
                    .filter(job -> job.entryId().equals(Optional.of(entryId)))
                    .min(Comparator.comparing(KnowledgeIndexJob::createdAt));
        }

        @Override
        public Optional<KnowledgeIndexJob> findLiveByIndexKey(RepositoryIndexKey indexKey) {
            return Optional.empty();
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
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<KnowledgeIndexJob> claimNext(
                String owner, UtcTimestamp now, Duration leaseDuration) {
            throw new UnsupportedOperationException();
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
            throw new UnsupportedOperationException();
        }
    }
}

package io.crewscope.infrastructure.persistence.knowledge;

import io.crewscope.application.event.json.DomainEventEnvelopeJsonCodec;
import io.crewscope.application.event.publication.DomainEventConsumer;
import io.crewscope.application.event.publication.EventPublication;
import io.crewscope.application.retrieval.KnowledgeIndexJobService;
import io.crewscope.domain.knowledge.KnowledgeEntryId;
import io.crewscope.domain.knowledge.event.KnowledgeEntryCreated;
import io.crewscope.domain.knowledge.event.KnowledgeEntryDeleted;
import io.crewscope.domain.knowledge.event.KnowledgeVersionPublished;
import io.crewscope.domain.knowledge.event.KnowledgeVersionRetired;
import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import java.util.Objects;
import java.util.function.Function;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Enqueues knowledge-index invalidation from knowledge events (M10-I01b). Creation and
 * publication enqueue a refresh-class job, retirement and deletion a cleanup-class job;
 * the effective revision is deliberately not captured — the worker re-reads the head at
 * claim time, so stale or reordered events can never resurrect retired content. Refresh
 * enqueues observe the {@code crewscope.knowledge.index.enabled} gate (a closed gate
 * skips, never errors); cleanup enqueues never do (§10.7: retirement keeps draining
 * vectors). The class remains proxyable because {@link #consume(EventPublication)}
 * requires a transaction.
 */
@Order(Ordered.LOWEST_PRECEDENCE - 100)
public class KnowledgeIndexInvalidationConsumer implements DomainEventConsumer {

    static final String ENTRY_CREATED = "KNOWLEDGE_ENTRY_CREATED";
    static final String VERSION_PUBLISHED = "KNOWLEDGE_VERSION_PUBLISHED";
    static final String VERSION_RETIRED = "KNOWLEDGE_VERSION_RETIRED";
    static final String ENTRY_DELETED = "KNOWLEDGE_ENTRY_DELETED";

    private final DomainEventEnvelopeJsonCodec eventCodec;
    private final ObjectMapper objectMapper;
    private final KnowledgeIndexJobService index;

    public KnowledgeIndexInvalidationConsumer(
            ObjectMapper objectMapper, KnowledgeIndexJobService index) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.eventCodec = new DomainEventEnvelopeJsonCodec(this.objectMapper);
        this.index = Objects.requireNonNull(index, "index");
    }

    @Override
    public String consumerName() {
        return "knowledge-index-invalidation-v1";
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void consume(EventPublication publication) {
        EventPublication source = Objects.requireNonNull(publication, "publication");
        String eventType = objectMapper.readTree(source.eventJson())
                .path("eventType").asText();
        switch (eventType) {
            case ENTRY_CREATED -> enqueueRefresh(
                    source, KnowledgeEntryCreated.class, KnowledgeEntryCreated::entryId);
            case VERSION_PUBLISHED -> enqueueRefresh(
                    source, KnowledgeVersionPublished.class, KnowledgeVersionPublished::entryId);
            case VERSION_RETIRED -> enqueueCleanup(
                    source, KnowledgeVersionRetired.class, KnowledgeVersionRetired::entryId);
            case ENTRY_DELETED -> enqueueCleanup(
                    source, KnowledgeEntryDeleted.class, KnowledgeEntryDeleted::entryId);
            default -> {
                // Other aggregates never invalidate the knowledge index.
            }
        }
    }

    private <T extends DomainEvent> void enqueueRefresh(
            EventPublication source, Class<T> payloadType, Function<T, java.util.UUID> entryIdOf) {
        DomainEventEnvelope<T> envelope = eventCodec.decode(source.eventJson(), payloadType);
        enqueueIfActionable(envelope, entryIdOf.apply(envelope.payload()), true);
    }

    private <T extends DomainEvent> void enqueueCleanup(
            EventPublication source, Class<T> payloadType, Function<T, java.util.UUID> entryIdOf) {
        DomainEventEnvelope<T> envelope = eventCodec.decode(source.eventJson(), payloadType);
        enqueueIfActionable(envelope, entryIdOf.apply(envelope.payload()), false);
    }

    private <T extends DomainEvent> void enqueueIfActionable(
            DomainEventEnvelope<T> envelope, java.util.UUID entryId, boolean refresh) {
        // A job row requires a principal author (FK) and a team scope; knowledge
        // lifecycle events carry both, anything else simply does not enqueue.
        java.util.Optional<TeamId> team = envelope.teamId();
        java.util.Optional<PrincipalId> actor = envelope.actor().id();
        if (team.isEmpty() || actor.isEmpty()) {
            return;
        }
        KnowledgeEntryId target = new KnowledgeEntryId(entryId);
        if (refresh) {
            index.enqueueEntryRefresh(envelope.organizationId(), team.orElseThrow(), target,
                    actor.orElseThrow());
        } else {
            index.enqueueEntryCleanup(envelope.organizationId(), team.orElseThrow(), target,
                    actor.orElseThrow());
        }
    }
}

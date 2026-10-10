package io.crewscope.infrastructure.persistence.collaboration;

import io.crewscope.application.collaboration.CollaborationSignalSink;
import io.crewscope.application.conversation.ConversationRepository;
import io.crewscope.application.event.publication.DomainEventConsumer;
import io.crewscope.application.event.publication.EventPublication;
import io.crewscope.domain.collaboration.CollaborationResourceChanged;
import io.crewscope.domain.collaboration.CollaborationResourceScope;
import io.crewscope.domain.collaboration.CollaborationResourceType;
import io.crewscope.domain.collaboration.ResourceScope;
import io.crewscope.domain.collaboration.TeamScope;
import io.crewscope.domain.collaboration.WorkProjectScope;
import io.crewscope.domain.conversation.Conversation;
import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.conversation.ConversationVisibility;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Maps whitelisted domain events onto collaboration change signals (M11-A01, ADR-032).
 * WorkItem events fan out to the resource, its project and the Team; a WorkProject
 * creation to the project and the Team; a Conversation event passes a visibility gate
 * first — a PRIVATE conversation never reaches the Team scope, otherwise its very
 * existence would leak to Team-scope subscribers (§10.1). The emitted fact carries
 * coordinates and the aggregate version only; every other payload field is dropped.
 *
 * <p>The consumer runs inside the outbox receipt transaction: a throw would roll back
 * the receipts of every consumer of that event and redeliver it forever, so malformed
 * events are skipped with a warning and a throwing sink is swallowed — signals are
 * lossy by contract and the authoritative read stays on REST/SSE.
 */
@Order(Ordered.LOWEST_PRECEDENCE - 100)
public class CollaborationResourceChangeConsumer implements DomainEventConsumer {

    static final String WORK_ITEM_CREATED = "WORK_ITEM_CREATED";
    static final String WORK_ITEM_CONTENT_UPDATED = "WORK_ITEM_CONTENT_UPDATED";
    static final String WORK_ITEM_STATUS_CHANGED = "WORK_ITEM_STATUS_CHANGED";
    static final String WORK_PROJECT_CREATED = "WORK_PROJECT_CREATED";
    static final String CONVERSATION_CREATED = "CONVERSATION_CREATED";
    static final String CONVERSATION_MESSAGE_POSTED = "CONVERSATION_MESSAGE_POSTED";

    private static final Logger log =
            LoggerFactory.getLogger(CollaborationResourceChangeConsumer.class);

    private final ObjectMapper objectMapper;
    private final CollaborationSignalSink sink;
    private final ConversationRepository conversations;

    public CollaborationResourceChangeConsumer(
            ObjectMapper objectMapper,
            CollaborationSignalSink sink,
            ConversationRepository conversations) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.conversations = Objects.requireNonNull(conversations, "conversations");
    }

    @Override
    public String consumerName() {
        return "collaboration-resource-changed-v1";
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void consume(EventPublication publication) {
        EventPublication source = Objects.requireNonNull(publication, "publication");
        try {
            dispatch(source);
        } catch (RuntimeException malformed) {
            log.warn("Skipping malformed collaboration change event {}: {}",
                    source.eventId(), malformed.toString());
        }
    }

    private void dispatch(EventPublication source) {
        JsonNode root = objectMapper.readTree(source.eventJson());
        switch (root.path("eventType").asText()) {
            case WORK_ITEM_CREATED, WORK_ITEM_CONTENT_UPDATED, WORK_ITEM_STATUS_CHANGED ->
                workItemChanged(root);
            case WORK_PROJECT_CREATED -> workProjectCreated(root);
            case CONVERSATION_CREATED -> conversationChanged(
                    root, visibilityFromPayload(root));
            case CONVERSATION_MESSAGE_POSTED -> conversationChanged(
                    root, visibilityFromRepository(root));
            default -> {
                // Other aggregates (participants, comments, responsibilities…) never
                // produce a collaboration change signal.
            }
        }
    }

    private void workItemChanged(JsonNode root) {
        TeamId teamId = requiredTeamId(root);
        ResourceScope item = new ResourceScope(
                organizationId(root), teamId, CollaborationResourceType.WORK_ITEM,
                requiredUuid(root, "aggregateId"));
        Set<CollaborationResourceScope> audience = new LinkedHashSet<>();
        audience.add(item);
        audience.add(new WorkProjectScope(
                organizationId(root), teamId,
                new WorkProjectId(requiredUuid(root.path("payload"), "projectId"))));
        audience.add(new TeamScope(organizationId(root), teamId));
        publish(item, version(root), audience);
    }

    private void workProjectCreated(JsonNode root) {
        TeamId teamId = requiredTeamId(root);
        WorkProjectScope project = new WorkProjectScope(
                organizationId(root), teamId,
                new WorkProjectId(requiredUuid(root, "aggregateId")));
        Set<CollaborationResourceScope> audience = new LinkedHashSet<>();
        audience.add(project);
        audience.add(new TeamScope(organizationId(root), teamId));
        publish(project, version(root), audience);
    }

    private void conversationChanged(JsonNode root, ConversationVisibility visibility) {
        if (visibility == null) {
            // A deleted conversation, or an event without a usable visibility fact:
            // there is no audience to widen, so the signal is skipped silently.
            return;
        }
        TeamId teamId = requiredTeamId(root);
        ResourceScope conversation = new ResourceScope(
                organizationId(root), teamId, CollaborationResourceType.CONVERSATION,
                requiredUuid(root, "aggregateId"));
        Set<CollaborationResourceScope> audience = new LinkedHashSet<>();
        audience.add(conversation);
        if (visibility == ConversationVisibility.TEAM) {
            audience.add(new TeamScope(organizationId(root), teamId));
        }
        publish(conversation, version(root), audience);
    }

    /** Creation events carry the visibility in their payload. */
    private ConversationVisibility visibilityFromPayload(JsonNode root) {
        String visibility = root.path("payload").path("visibility").asText("");
        return switch (visibility) {
            case "PRIVATE" -> ConversationVisibility.PRIVATE;
            case "TEAM" -> ConversationVisibility.TEAM;
            default -> null;
        };
    }

    /** Message events do not; one primary-key read recovers it from the aggregate. */
    private ConversationVisibility visibilityFromRepository(JsonNode root) {
        Optional<Conversation> conversation = conversations.findById(
                organizationId(root), new ConversationId(requiredUuid(root, "aggregateId")));
        return conversation.map(Conversation::visibility).orElse(null);
    }

    private void publish(
            CollaborationResourceScope resource,
            long version,
            Set<CollaborationResourceScope> audience) {
        try {
            sink.resourceChanged(new CollaborationResourceChanged(resource, version, audience));
        } catch (RuntimeException dropped) {
            log.warn("Collaboration signal sink dropped a change of {}: {}",
                    resource, dropped.toString());
        }
    }

    // A TeamId is required, not optional: every subscription scope of the three
    // granularities carries the team coordinate, so an event without one cannot name
    // any audience at all and is skipped through the malformed-event path.

    private static OrganizationId organizationId(JsonNode root) {
        return OrganizationId.from(root.path("organizationId").asText());
    }

    private static TeamId requiredTeamId(JsonNode root) {
        JsonNode teamId = root.path("teamId");
        if (teamId.isMissingNode() || teamId.isNull()) {
            throw new IllegalArgumentException("teamId is required for a collaboration signal");
        }
        return TeamId.from(teamId.asText());
    }

    private static UUID requiredUuid(JsonNode node, String field) {
        return UUID.fromString(node.path(field).asText());
    }

    private static long version(JsonNode root) {
        return root.path("aggregateVersion").asLong();
    }
}

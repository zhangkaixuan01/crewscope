package io.crewscope.infrastructure.persistence.collaboration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.collaboration.CollaborationSignalSink;
import io.crewscope.application.conversation.ConversationRepository;
import io.crewscope.application.event.publication.EventPublication;
import io.crewscope.application.workitem.WorkItemRepository;
import io.crewscope.domain.collaboration.CollaborationResourceChanged;
import io.crewscope.domain.collaboration.CollaborationResourceType;
import io.crewscope.domain.collaboration.ResourceScope;
import io.crewscope.domain.collaboration.TeamScope;
import io.crewscope.domain.collaboration.WorkProjectScope;
import io.crewscope.domain.conversation.Conversation;
import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.conversation.ConversationVisibility;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumer contract of the A01 change signal (M11-A01): the whitelisted aggregates map
 * onto coordinates plus a version, a PRIVATE conversation never widens to the Team
 * scope, unrelated or malformed events never throw (the receipt transaction must not
 * roll back), and a throwing sink is swallowed. Since M11-A02 a relation change fans
 * out to both endpoint WorkItems at their own current versions.
 */
class CollaborationResourceChangeConsumerTest {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-10-10T08:00:00Z");

    private final OrganizationId organizationId = OrganizationId.generate();
    private final TeamId teamId = TeamId.generate();
    private final RecordingSink sink = new RecordingSink();
    private final ConversationRepository conversations = mock(ConversationRepository.class);
    private final WorkItemRepository workItems = mock(WorkItemRepository.class);

    // ------------------------------------------------------------------ work items

    @Test
    void workItemEventsMapOntoTheThreeLevelAudience() {
        for (String eventType : List.of(
                CollaborationResourceChangeConsumer.WORK_ITEM_CREATED,
                CollaborationResourceChangeConsumer.WORK_ITEM_CONTENT_UPDATED,
                CollaborationResourceChangeConsumer.WORK_ITEM_STATUS_CHANGED)) {
            sink.changes.clear();
            UUID itemId = UUID.randomUUID();
            UUID projectId = UUID.randomUUID();

            consumer().consume(publication(workItemEvent(eventType, itemId, projectId, 12)));

            assertEquals(1, sink.changes.size(), eventType);
            CollaborationResourceChanged changed = sink.changes.get(0);
            assertEquals(12, changed.version());
            assertEquals(new ResourceScope(
                    organizationId, teamId,
                    io.crewscope.domain.collaboration.CollaborationResourceType.WORK_ITEM,
                    itemId), changed.resource());
            assertEquals(3, changed.audience().size());
            assertTrue(changed.audience().contains(new WorkProjectScope(
                    organizationId, teamId,
                    new io.crewscope.domain.workitem.WorkProjectId(projectId))));
            assertTrue(changed.audience().contains(new TeamScope(organizationId, teamId)));
        }
    }

    // ------------------------------------------------------------------ work projects

    @Test
    void aWorkProjectCreationSignalsTheProjectAndTheTeam() {
        UUID projectId = UUID.randomUUID();

        consumer().consume(publication(event(
                CollaborationResourceChangeConsumer.WORK_PROJECT_CREATED,
                projectId, 3, "{\"projectKey\":\"P-1\"}")));

        assertEquals(1, sink.changes.size());
        CollaborationResourceChanged changed = sink.changes.get(0);
        assertEquals(3, changed.version());
        assertEquals(new WorkProjectScope(
                organizationId, teamId,
                new io.crewscope.domain.workitem.WorkProjectId(projectId)),
                changed.resource());
        assertEquals(2, changed.audience().size());
        assertTrue(changed.audience().contains(new TeamScope(organizationId, teamId)));
    }

    // ------------------------------------------------------------------ conversations

    @Test
    void aTeamVisibleConversationAddsTheTeamScope() {
        UUID conversationId = UUID.randomUUID();

        consumer().consume(publication(event(
                CollaborationResourceChangeConsumer.CONVERSATION_CREATED,
                conversationId, 2, "{\"visibility\":\"TEAM\"}")));

        assertEquals(1, sink.changes.size());
        CollaborationResourceChanged changed = sink.changes.get(0);
        assertEquals(2, changed.version());
        assertEquals(new ResourceScope(
                organizationId, teamId,
                io.crewscope.domain.collaboration.CollaborationResourceType.CONVERSATION,
                conversationId), changed.resource());
        assertTrue(changed.audience().contains(new TeamScope(organizationId, teamId)));
    }

    @Test
    void aPrivateConversationNeverWidensToTheTeamScope() {
        // §10.1: a Team-scope subscriber must not even learn that the conversation exists.
        consumer().consume(publication(event(
                CollaborationResourceChangeConsumer.CONVERSATION_CREATED,
                UUID.randomUUID(), 1, "{\"visibility\":\"PRIVATE\"}")));

        assertEquals(1, sink.changes.size());
        assertEquals(1, sink.changes.get(0).audience().size(),
                "a PRIVATE conversation signals its own resource scope only");
    }

    @Test
    void aMessageReadsTheVisibilityFromTheConversationAggregate() {
        UUID conversationId = UUID.randomUUID();
        // Built before the stubbing: opening a second when() inside thenReturn's argument
        // breaks Mockito's state machine (UnfinishedStubbing).
        Conversation teamConversation = visibility(ConversationVisibility.TEAM);
        when(conversations.findById(organizationId, new ConversationId(conversationId)))
                .thenReturn(Optional.of(teamConversation));

        consumer().consume(publication(event(
                CollaborationResourceChangeConsumer.CONVERSATION_MESSAGE_POSTED,
                conversationId, 5, "{\"messageId\":\"" + UUID.randomUUID() + "\"}")));

        assertEquals(1, sink.changes.size());
        assertTrue(sink.changes.get(0).audience().contains(new TeamScope(organizationId, teamId)));
    }

    @Test
    void aPrivateMessageNeverWidensToTheTeamScope() {
        UUID conversationId = UUID.randomUUID();
        Conversation privateConversation = visibility(ConversationVisibility.PRIVATE);
        when(conversations.findById(organizationId, new ConversationId(conversationId)))
                .thenReturn(Optional.of(privateConversation));

        consumer().consume(publication(event(
                CollaborationResourceChangeConsumer.CONVERSATION_MESSAGE_POSTED,
                conversationId, 6, "{\"messageId\":\"" + UUID.randomUUID() + "\"}")));

        assertEquals(1, sink.changes.size());
        assertEquals(1, sink.changes.get(0).audience().size());
    }

    @Test
    void aVanishedConversationIsSkippedSilently() {
        when(conversations.findById(any(), any())).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> consumer().consume(publication(event(
                CollaborationResourceChangeConsumer.CONVERSATION_MESSAGE_POSTED,
                UUID.randomUUID(), 7, "{}"))));

        assertTrue(sink.changes.isEmpty());
    }

    // ------------------------------------------------------------------ exclusions

    @Test
    void unrelatedAggregatesNeverSignal() {
        for (String eventType : List.of(
                "RESPONSIBILITY_ASSIGNMENT_CREATED",
                "WORK_ITEM_COMMENT_POSTED",
                "CONVERSATION_PARTICIPANT_ADDED",
                "KNOWLEDGE_VERSION_PUBLISHED")) {
            consumer().consume(publication(event(eventType, UUID.randomUUID(), 1, "{}")));
        }

        assertTrue(sink.changes.isEmpty());
    }

    @Test
    void malformedEventsAreSkippedWithoutThrowing() {
        assertDoesNotThrow(() -> consumer().consume(publication("not json at all")));
        // A team-less event cannot name any audience and takes the same skip path.
        assertDoesNotThrow(() -> consumer().consume(publication("""
                {"eventType":"WORK_ITEM_CREATED","organizationId":"%s",
                 "aggregateId":"%s","aggregateVersion":1,"payload":{"projectId":"%s"}}
                """.formatted(organizationId.value(), UUID.randomUUID(), UUID.randomUUID()))));
        assertTrue(sink.changes.isEmpty());
    }

    @Test
    void aThrowingSinkIsSwallowed() {
        CollaborationResourceChangeConsumer throwing = new CollaborationResourceChangeConsumer(
                JsonMapper.builder().findAndAddModules().build(),
                change -> { throw new IllegalStateException("boom"); },
                conversations, workItems);

        assertDoesNotThrow(() -> throwing.consume(publication(event(
                CollaborationResourceChangeConsumer.WORK_ITEM_CREATED,
                UUID.randomUUID(), 1,
                "{\"projectId\":\"" + UUID.randomUUID() + "\"}"))));
    }

    // ------------------------------------------------------------------ relations (M11-A02)

    @Test
    void relationEventsFanOutToBothEndpointsAtTheirCurrentVersions() {
        for (String eventType : List.of(
                CollaborationResourceChangeConsumer.WORK_ITEM_RELATION_CREATED,
                CollaborationResourceChangeConsumer.WORK_ITEM_RELATION_REMOVED)) {
            sink.changes.clear();
            UUID srcId = UUID.randomUUID();
            UUID dstId = UUID.randomUUID();
            UUID srcProject = UUID.randomUUID();
            UUID dstProject = UUID.randomUUID();
            // Built before the stubbing: opening a mock inside thenReturn's argument
            // breaks Mockito's state machine (UnfinishedStubbing).
            WorkItem srcEndpoint = endpoint(11);
            WorkItem dstEndpoint = endpoint(4);
            when(workItems.findById(organizationId, new WorkItemId(srcId)))
                    .thenReturn(Optional.of(srcEndpoint));
            when(workItems.findById(organizationId, new WorkItemId(dstId)))
                    .thenReturn(Optional.of(dstEndpoint));

            consumer().consume(publication(event(
                    eventType, UUID.randomUUID(), 0,
                    "{\"relationId\":\"" + UUID.randomUUID() + "\","
                            + "\"srcWorkItemId\":\"" + srcId + "\","
                            + "\"dstWorkItemId\":\"" + dstId + "\","
                            + "\"kind\":\"BLOCKS\","
                            + "\"srcProjectId\":\"" + srcProject + "\","
                            + "\"dstProjectId\":\"" + dstProject + "\"}")));

            // the envelope's version 0 is a fact about the edge; each endpoint signals its own
            // current aggregate version (the A02 fanout decision)
            assertEquals(2, sink.changes.size(), eventType);
            ResourceScope srcResource = new ResourceScope(
                    organizationId, teamId, CollaborationResourceType.WORK_ITEM, srcId);
            ResourceScope dstResource = new ResourceScope(
                    organizationId, teamId, CollaborationResourceType.WORK_ITEM, dstId);
            assertTrue(sink.changes.stream().anyMatch(change ->
                    change.resource().equals(srcResource) && change.version() == 11));
            assertTrue(sink.changes.stream().anyMatch(change ->
                    change.resource().equals(dstResource) && change.version() == 4));
            for (CollaborationResourceChanged change : sink.changes) {
                assertEquals(3, change.audience().size(), eventType);
                assertTrue(change.audience().contains(new TeamScope(organizationId, teamId)));
            }
        }
    }

    @Test
    void aVanishedRelationEndpointIsSkippedSilently() {
        when(workItems.findById(any(), any())).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> consumer().consume(publication(event(
                CollaborationResourceChangeConsumer.WORK_ITEM_RELATION_CREATED,
                UUID.randomUUID(), 0,
                "{\"srcWorkItemId\":\"" + UUID.randomUUID() + "\","
                        + "\"dstWorkItemId\":\"" + UUID.randomUUID() + "\","
                        + "\"srcProjectId\":\"" + UUID.randomUUID() + "\","
                        + "\"dstProjectId\":\"" + UUID.randomUUID() + "\"}"))));

        assertTrue(sink.changes.isEmpty());
    }

    // ------------------------------------------------------------------ fixtures

    private CollaborationResourceChangeConsumer consumer() {
        return new CollaborationResourceChangeConsumer(
                JsonMapper.builder().findAndAddModules().build(), sink, conversations, workItems);
    }

    private static WorkItem endpoint(long version) {
        WorkItem item = mock(WorkItem.class);
        when(item.version()).thenReturn(version);
        return item;
    }

    private static Conversation visibility(ConversationVisibility visibility) {
        Conversation conversation = mock(Conversation.class);
        when(conversation.visibility()).thenReturn(visibility);
        return conversation;
    }

    private String workItemEvent(String eventType, UUID itemId, UUID projectId, long version) {
        return event(eventType, itemId, version,
                "{\"projectId\":\"" + projectId + "\",\"itemKey\":\"WI-1\"}");
    }

    private String event(String eventType, UUID aggregateId, long version, String payloadJson) {
        return """
                {
                  "eventId": "%s",
                  "eventType": "%s",
                  "schemaVersion": "1",
                  "organizationId": "%s",
                  "teamId": "%s",
                  "workspaceId": null,
                  "aggregateType": "AGGREGATE",
                  "aggregateId": "%s",
                  "aggregateVersion": %d,
                  "actorType": "USER",
                  "actorId": "%s",
                  "correlationId": "%s",
                  "causationId": null,
                  "idempotencyKey": null,
                  "occurredAt": "2026-10-10T08:00:00Z",
                  "payload": %s
                }
                """.formatted(UUID.randomUUID(), eventType, organizationId.value(), teamId.value(),
                aggregateId, version, UUID.randomUUID(), UUID.randomUUID(),
                payloadJson);
    }

    private static EventPublication publication(String eventJson) {
        return new EventPublication(
                UUID.randomUUID(), UUID.randomUUID(), "crewscope.events", "partition", 1,
                NOW, eventJson);
    }

    private static final class RecordingSink implements CollaborationSignalSink {
        private final List<CollaborationResourceChanged> changes = new ArrayList<>();

        @Override
        public void resourceChanged(CollaborationResourceChanged change) {
            changes.add(change);
        }
    }
}

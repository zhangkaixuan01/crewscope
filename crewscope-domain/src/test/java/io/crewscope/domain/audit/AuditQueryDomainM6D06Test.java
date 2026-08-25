package io.crewscope.domain.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.event.AggregateReference;
import io.crewscope.domain.shared.event.EventActor;
import io.crewscope.domain.shared.event.EventActorType;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskFactHash;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** M6-D06 safe Audit summary, identity and typed-reference domain tests. */
class AuditQueryDomainM6D06Test {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-25T12:00:00Z");
    private static final EventType EVENT_TYPE = EventType.from("WORK_ITEM_CREATED");

    @Test
    void projectsKnownPayloadSchemaIntoTypedAppendOnlyQueryFact() {
        PrincipalId initiator = PrincipalId.generate();
        AuditSummarySchemaRegistry registry = registry();
        AuditRedactedSummary summary = registry.project(
                EVENT_TYPE,
                SchemaVersion.V1,
                Map.of("action", "Work item created", "resultCode", "CREATED"));
        AuditProviderReference provider = new AuditProviderReference(
                ProviderBindingId.generate(),
                ConnectionId.generate(),
                Optional.of(TaskFactHash.sha256("external-operation")));

        AuditQueryEvent event = new AuditQueryEvent(
                AuditEventId.generate(),
                OrganizationId.generate(),
                TeamId.generate(),
                AuditEventCategory.WORK,
                AuditOutcome.SUCCEEDED,
                AuditIdentityChain.from(
                        Optional.of(initiator),
                        EventActor.principal(EventActorType.USER, initiator)),
                new AggregateReference("WORK_ITEM", UUID.randomUUID()),
                Optional.of(provider),
                new AuditCorrelationReference(
                        UUID.randomUUID(), Optional.of(UUID.randomUUID()), Optional.of(UUID.randomUUID())),
                AuditRetentionLevel.EXTENDED,
                NOW,
                summary);

        assertEquals(EVENT_TYPE, event.summary().eventType());
        assertEquals(AuditEventCategory.WORK, event.category());
        assertEquals("CREATED", event.summary().values().get("resultCode"));
        assertTrue(event.providerReference().isPresent());
    }

    @Test
    void rejectsUnknownEventTypeVersionAndPayloadFields() {
        AuditSummarySchemaRegistry registry = registry();

        assertThrows(
                DomainValidationException.class,
                () -> registry.project(
                        EventType.from("FUTURE_EVENT"),
                        SchemaVersion.V1,
                        Map.of("action", "Future event")));
        assertThrows(
                DomainValidationException.class,
                () -> registry.project(
                        EVENT_TYPE,
                        SchemaVersion.V2,
                        Map.of("action", "Future schema")));
        assertThrows(
                DomainValidationException.class,
                () -> registry.project(
                        EVENT_TYPE,
                        SchemaVersion.V1,
                        Map.of("action", "Created", "rawPayload", "private")));
    }

    @Test
    void rejectsSensitiveFieldNamesAndSecretOrPiiValues() {
        assertThrows(
                DomainValidationException.class,
                () -> new AuditSummarySchema(
                        EVENT_TYPE,
                        SchemaVersion.V1,
                        AuditEventCategory.WORK,
                        Set.of("accessToken"),
                        Set.of()));
        AuditSummarySchema schema = schema();

        assertThrows(
                DomainValidationException.class,
                () -> schema.project(Map.of("action", "Authorization: Bearer private")));
        assertThrows(
                DomainValidationException.class,
                () -> schema.project(Map.of("action", "contact admin@example.com")));
        assertThrows(
                DomainValidationException.class,
                () -> schema.project(Map.of("action", "call +86 138 0013 8000")));
        assertThrows(
                DomainValidationException.class,
                () -> schema.project(Map.of("action", "open https://internal.example/private")));
    }

    @Test
    void requiresExactInitiatorActorAgentIdentityShape() {
        PrincipalId user = PrincipalId.generate();
        PrincipalId agent = PrincipalId.generate();

        AuditIdentityChain chain = AuditIdentityChain.from(
                Optional.of(user),
                EventActor.principal(EventActorType.SPECIALIST_AGENT, agent));

        assertEquals(Optional.of(user), chain.initiatorId());
        assertEquals(Optional.of(agent), chain.agentPrincipalId());
        assertThrows(
                DomainValidationException.class,
                () -> new AuditIdentityChain(
                        Optional.of(user),
                        EventActor.principal(EventActorType.USER, user),
                        Optional.of(agent)));
        assertThrows(
                DomainValidationException.class,
                () -> AuditIdentityChain.from(
                        Optional.of(PrincipalId.generate()),
                        EventActor.principal(EventActorType.USER, user)));
    }

    @Test
    void rejectsCategoryThatDoesNotMatchRegisteredSummarySchema() {
        PrincipalId user = PrincipalId.generate();

        assertThrows(
                DomainValidationException.class,
                () -> new AuditQueryEvent(
                        AuditEventId.generate(),
                        OrganizationId.generate(),
                        TeamId.generate(),
                        AuditEventCategory.SECURITY,
                        AuditOutcome.SUCCEEDED,
                        AuditIdentityChain.from(
                                Optional.of(user),
                                EventActor.principal(EventActorType.USER, user)),
                        new AggregateReference("WORK_ITEM", UUID.randomUUID()),
                        Optional.empty(),
                        new AuditCorrelationReference(
                                UUID.randomUUID(), Optional.empty(), Optional.empty()),
                        AuditRetentionLevel.STANDARD,
                        NOW,
                        schema().project(Map.of("action", "Created"))));
    }

    @Test
    void registryRejectsDuplicateEventSchemaCoordinates() {
        assertThrows(
                DomainValidationException.class,
                () -> new AuditSummarySchemaRegistry(List.of(schema(), schema())));
    }

    private static AuditSummarySchemaRegistry registry() {
        return new AuditSummarySchemaRegistry(List.of(schema()));
    }

    private static AuditSummarySchema schema() {
        return new AuditSummarySchema(
                EVENT_TYPE,
                SchemaVersion.V1,
                AuditEventCategory.WORK,
                Set.of("action"),
                Set.of("resultCode"));
    }
}

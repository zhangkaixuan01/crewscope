package io.crewscope.infrastructure.persistence.collaboration;

import io.crewscope.application.collaboration.CollaborationMemberRevocationSink;
import io.crewscope.application.event.publication.DomainEventConsumer;
import io.crewscope.application.event.publication.EventPublication;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.util.Objects;
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
 * Maps member lifecycle events onto the event-driven revocation sweep (M11-D02, ADR-038
 * §3.1 layer 1). The four loss-causing commands — suspension, removal, leaving and a role
 * revocation (the M9b demotion path) — cut the member's live collaboration connections in
 * that Team with 4403 and clean their presence. Activation and role grants only widen
 * access, and an ownership transfer's outgoing side stays an ACTIVE member whose
 * subscriptions ride resource visibility, not the OWNER role — none of them disconnect.
 *
 * <p>The consumer runs inside the outbox receipt transaction: a throw would roll back the
 * receipts of every consumer of that event and redeliver it forever, so malformed events
 * are skipped with a warning and a throwing sink is swallowed — the sweep is an
 * accelerator, and the revalidation/TTL layers already bound the same window.</p>
 */
@Order(Ordered.LOWEST_PRECEDENCE - 100)
public class CollaborationMemberRevocationConsumer implements DomainEventConsumer {

    static final String TEAM_MEMBER_SUSPENDED = "TEAM_MEMBER_SUSPENDED";
    static final String TEAM_MEMBER_REMOVED = "TEAM_MEMBER_REMOVED";
    static final String TEAM_MEMBER_LEFT = "TEAM_MEMBER_LEFT";
    static final String MEMBER_ROLE_REVOKED = "MEMBER_ROLE_REVOKED";

    private static final Logger log =
            LoggerFactory.getLogger(CollaborationMemberRevocationConsumer.class);

    private final ObjectMapper objectMapper;
    private final CollaborationMemberRevocationSink sink;

    public CollaborationMemberRevocationConsumer(
            ObjectMapper objectMapper, CollaborationMemberRevocationSink sink) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    @Override
    public String consumerName() {
        return "collaboration-member-revocation-v1";
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void consume(EventPublication publication) {
        EventPublication source = Objects.requireNonNull(publication, "publication");
        try {
            dispatch(source);
        } catch (RuntimeException malformed) {
            log.warn("Skipping malformed member revocation event {}: {}",
                    source.eventId(), malformed.toString());
        }
    }

    private void dispatch(EventPublication source) {
        JsonNode root = objectMapper.readTree(source.eventJson());
        switch (root.path("eventType").asText()) {
            case TEAM_MEMBER_SUSPENDED, TEAM_MEMBER_REMOVED, TEAM_MEMBER_LEFT,
                    MEMBER_ROLE_REVOKED -> memberRevoked(root);
            default -> {
                // Activation, role grants, ownership transfers and every non-member event
                // never shrink what an established connection may still receive.
            }
        }
    }

    private void memberRevoked(JsonNode root) {
        UUID userPrincipalId =
                UUID.fromString(root.path("payload").path("userPrincipalId").asText());
        sink.memberAuthorizationRevoked(
                userPrincipalId, organizationId(root), requiredTeamId(root));
    }

    // The team coordinate is required, not optional: a member event without one cannot
    // name which Team's connections to cut, and is skipped through the malformed path.

    private static OrganizationId organizationId(JsonNode root) {
        return OrganizationId.from(root.path("organizationId").asText());
    }

    private static TeamId requiredTeamId(JsonNode root) {
        JsonNode teamId = root.path("teamId");
        if (teamId.isMissingNode() || teamId.isNull()) {
            throw new IllegalArgumentException("teamId is required for a member revocation");
        }
        return TeamId.from(teamId.asText());
    }
}

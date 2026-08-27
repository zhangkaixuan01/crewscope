package io.crewscope.server.api;

import io.crewscope.application.correlation.CorrelationEvent;
import io.crewscope.application.correlation.CorrelationObjectReference;
import io.crewscope.application.correlation.CorrelationPage;
import io.crewscope.domain.shared.id.TeamId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.web.util.UriComponentsBuilder;

/** Public payload-free Correlation graph with server-generated internal navigation links. */
public record CorrelationPageResponse(
        UUID correlationId,
        List<EventResponse> events,
        List<ObjectResponse> objects,
        boolean hasMore,
        String nextCursor) {

    static CorrelationPageResponse from(
            CorrelationPage page, TeamId teamId, CorrelationCursorCodec codec) {
        CorrelationPage value = Objects.requireNonNull(page, "page");
        return new CorrelationPageResponse(
                value.correlationId(),
                value.events().stream()
                        .map(event -> EventResponse.from(event, teamId, value.correlationId()))
                        .toList(),
                value.objects().stream()
                        .map(object -> ObjectResponse.from(object, teamId, value.correlationId()))
                        .toList(),
                value.hasMore(),
                value.nextCursor().map(codec::encode).orElse(null));
    }

    public record EventResponse(
            UUID eventId,
            String source,
            String eventType,
            String actorType,
            UUID actorId,
            String outcome,
            Instant occurredAt,
            List<ReferenceResponse> references) {

        private static EventResponse from(
                CorrelationEvent event, TeamId teamId, UUID correlationId) {
            return new EventResponse(
                    event.eventId(), event.source().name(), event.eventType(), event.actorType(),
                    event.actorId().orElse(null), event.outcome().orElse(null),
                    event.occurredAt().value(),
                    event.references().stream()
                            .map(reference -> ReferenceResponse.from(
                                    reference, teamId, correlationId))
                            .toList());
        }
    }

    public record ReferenceResponse(String type, UUID id, String href) {

        private static ReferenceResponse from(
                CorrelationObjectReference reference, TeamId teamId, UUID correlationId) {
            return new ReferenceResponse(
                    reference.type().name(), reference.id(),
                    CorrelationPageResponse.href(reference, teamId, correlationId));
        }
    }

    public record ObjectResponse(
            String type, UUID id, String href, List<UUID> relatedEventIds) {

        private static ObjectResponse from(
                CorrelationPage.CorrelationObjectLink link,
                TeamId teamId,
                UUID correlationId) {
            CorrelationObjectReference object = link.object();
            return new ObjectResponse(
                    object.type().name(), object.id(),
                    CorrelationPageResponse.href(object, teamId, correlationId),
                    link.eventIds());
        }
    }

    private static String href(
            CorrelationObjectReference object, TeamId teamId, UUID correlationId) {
        return UriComponentsBuilder.fromPath("/activity")
                .queryParam("team", teamId)
                .queryParam("correlation", correlationId)
                .queryParam("objectType", object.type().name())
                .queryParam("objectId", object.id())
                .build().encode().toUriString();
    }
}

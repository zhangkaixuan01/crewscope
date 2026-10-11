package io.crewscope.server.api;

import static io.crewscope.server.api.OperationsRequestParser.invalid;
import static io.crewscope.server.api.OperationsRequestParser.requireText;
import static io.crewscope.server.api.OperationsRequestParser.version;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.crewscope.application.operations.NotificationDeliveryRecoveryTarget;
import io.crewscope.application.operations.OperationsRecoveryTarget;
import io.crewscope.application.operations.OutboxDeadLetterRecoveryTarget;
import io.crewscope.application.operations.ProjectionDeadLetterRecoveryTarget;
import io.crewscope.domain.notification.NotificationDeliveryId;
import io.crewscope.domain.projection.ProjectionDeadLetterId;
import io.crewscope.domain.projection.ProjectionGeneration;
import java.util.UUID;

/** Closed union. Fields that do not belong to the selected type are rejected. */
public final class RecoveryTargetRequest {

    private final String type;
    private final String outboxEventId;
    private final String projectionName;
    private final Long generation;
    private final String deadLetterId;
    private final String deliveryId;
    private final String domainEventId;
    private final Long expectedVersion;
    private final Long expectedGenerationVersion;

    @JsonCreator
    public RecoveryTargetRequest(
            @JsonProperty("type") String type,
            @JsonProperty("outboxEventId") String outboxEventId,
            @JsonProperty("projectionName") String projectionName,
            @JsonProperty("generation") Long generation,
            @JsonProperty("deadLetterId") String deadLetterId,
            @JsonProperty("deliveryId") String deliveryId,
            @JsonProperty("domainEventId") String domainEventId,
            @JsonProperty("expectedVersion") Long expectedVersion,
            @JsonProperty("expectedGenerationVersion") Long expectedGenerationVersion) {
        this.type = requireText(type, "target.type");
        this.outboxEventId = outboxEventId;
        this.projectionName = projectionName;
        this.generation = generation;
        this.deadLetterId = deadLetterId;
        this.deliveryId = deliveryId;
        this.domainEventId = domainEventId;
        this.expectedVersion = expectedVersion;
        this.expectedGenerationVersion = expectedGenerationVersion;
    }

    OperationsRecoveryTarget toDomain() {
        try {
            return switch (type) {
                case "OUTBOX_DEAD_LETTER" -> outboxTarget();
                case "PROJECTION_DEAD_LETTER" -> projectionTarget();
                case "NOTIFICATION_DELIVERY" -> notificationTarget();
                default -> throw invalid("target.type");
            };
        } catch (ApiRequestException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw invalid("target");
        }
    }

    private OperationsRecoveryTarget outboxTarget() {
        requireAbsent(projectionName, generation, deadLetterId, deliveryId,
                expectedGenerationVersion);
        return new OutboxDeadLetterRecoveryTarget(
                UUID.fromString(requireText(outboxEventId, "target.outboxEventId")),
                UUID.fromString(requireText(domainEventId, "target.domainEventId")),
                version(expectedVersion, "target.expectedVersion"));
    }

    private OperationsRecoveryTarget projectionTarget() {
        requireAbsent(outboxEventId, deliveryId, expectedVersion);
        if (generation == null) {
            throw invalid("target.generation");
        }
        return new ProjectionDeadLetterRecoveryTarget(
                OperationsRequestParser.projectionName(projectionName),
                new ProjectionGeneration(generation),
                new ProjectionDeadLetterId(
                        UUID.fromString(requireText(deadLetterId, "target.deadLetterId"))),
                UUID.fromString(requireText(domainEventId, "target.domainEventId")),
                version(
                        expectedGenerationVersion,
                        "target.expectedGenerationVersion"));
    }

    private OperationsRecoveryTarget notificationTarget() {
        requireAbsent(outboxEventId, projectionName, generation, deadLetterId,
                domainEventId, expectedGenerationVersion);
        return new NotificationDeliveryRecoveryTarget(
                new NotificationDeliveryId(
                        UUID.fromString(requireText(deliveryId, "target.deliveryId"))),
                version(expectedVersion, "target.expectedVersion"));
    }

    private static void requireAbsent(Object... values) {
        for (Object value : values) {
            if (value != null) {
                throw invalid("target");
            }
        }
    }

    @JsonAnySetter
    void rejectUnknownProperty(String ignoredProperty, Object ignoredValue) {
        throw new IllegalArgumentException("Unsupported operations recovery target property");
    }
}

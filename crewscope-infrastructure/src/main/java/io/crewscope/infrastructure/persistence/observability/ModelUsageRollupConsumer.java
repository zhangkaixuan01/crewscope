package io.crewscope.infrastructure.persistence.observability;

import io.crewscope.application.event.json.DomainEventEnvelopeJsonCodec;
import io.crewscope.application.event.publication.DomainEventConsumer;
import io.crewscope.application.event.publication.EventPublication;
import io.crewscope.application.observability.ModelUsageRollupService;
import io.crewscope.domain.model.event.ModelUsageFactRecorded;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import java.util.Objects;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * White-listed consumer projecting {@code MODEL_USAGE_FACT_RECORDED} facts onto the
 * monthly usage rollup (M10-F03). The side effect runs inside the idempotent
 * dispatcher's transaction, so the event-consumer receipt and the rollup increment
 * commit or roll back together; every other event type is ignored — the projection is
 * rebuildable from usage facts alone. The class remains proxyable because
 * {@link #consume(EventPublication)} requires a transaction.
 */
@Order(Ordered.LOWEST_PRECEDENCE - 100)
public class ModelUsageRollupConsumer implements DomainEventConsumer {

    private final ObjectMapper objectMapper;
    private final DomainEventEnvelopeJsonCodec eventCodec;
    private final ModelUsageRollupService rollup;

    public ModelUsageRollupConsumer(ObjectMapper objectMapper, ModelUsageRollupService rollup) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.eventCodec = new DomainEventEnvelopeJsonCodec(this.objectMapper);
        this.rollup = Objects.requireNonNull(rollup, "rollup");
    }

    @Override
    public String consumerName() {
        return "model-usage-rollup-v1";
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void consume(EventPublication publication) {
        String eventType = objectMapper
                .readTree(Objects.requireNonNull(publication, "publication").eventJson())
                .path("eventType").asText();
        if (!ModelUsageRollupService.EVENT_TYPE.equals(eventType)) {
            // Other aggregates never touch the usage rollup.
            return;
        }
        DomainEventEnvelope<ModelUsageFactRecorded> envelope =
                eventCodec.decode(publication.eventJson(), ModelUsageFactRecorded.class);
        rollup.project(envelope.organizationId(), envelope.teamId(), envelope.payload());
    }
}

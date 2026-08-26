package io.crewscope.infrastructure.event.projection;

import io.crewscope.application.event.publication.DomainEventConsumer;
import io.crewscope.application.event.publication.EventPublication;
import io.crewscope.domain.projection.ProjectionGenerationLease;
import io.crewscope.domain.projection.ProjectionName;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Dynamically routes every DomainEvent to the ACTIVE Generation and then writable shadows.
 *
 * <p>Each Generation commits in its own local transaction. A shadow gap may therefore leave the
 * online Generation committed while the outer Outbox delivery remains retryable; Generation
 * Receipts make that retry deterministic.
 */
public final class GenerationAwareProjectionRunner implements DomainEventConsumer {

    private static final String CONSUMER_PREFIX = "projection-router:";

    private final GenerationAwareProjectionHandler handler;
    private final ProjectionName projectionName;
    private final JdbcProjectionGenerationRegistry registry;
    private final JdbcGenerationProjectionStore store;
    private final ProjectionEventJsonMapper eventMapper;
    private final Clock clock;
    private final TransactionTemplate generationTransaction;

    public GenerationAwareProjectionRunner(
            GenerationAwareProjectionHandler handler,
            JdbcProjectionGenerationRegistry registry,
            JdbcGenerationProjectionStore store,
            ProjectionEventJsonMapper eventMapper,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.handler = Objects.requireNonNull(handler, "handler");
        this.projectionName = this.handler.definition().name();
        this.registry = Objects.requireNonNull(registry, "registry");
        this.store = Objects.requireNonNull(store, "store");
        this.eventMapper = Objects.requireNonNull(eventMapper, "eventMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.generationTransaction = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.generationTransaction.setName("crewscope-generation-projection");
        this.generationTransaction.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public String consumerName() {
        return CONSUMER_PREFIX + projectionName.value();
    }

    @Override
    public void consume(EventPublication publication) {
        ProjectionEvent event = eventMapper.map(Objects.requireNonNull(publication, "publication"));
        List<ProjectionGenerationLease> leases = registry.writableLeases(
                event.organizationId(), projectionName);
        if (leases.isEmpty()) {
            registry.bootstrapIfAbsent(event.organizationId(), handler.definition());
            leases = registry.writableLeases(event.organizationId(), projectionName);
        }
        if (leases.isEmpty()) {
            throw new IllegalStateException(
                    "Projection bootstrap did not create a writable Generation");
        }
        for (ProjectionGenerationLease lease : leases) {
            consume(lease, event);
        }
    }

    /** Used by bounded history replay with the same transaction and idempotency protocol. */
    public ProjectionConsumptionResult consume(
            ProjectionGenerationLease lease, EventPublication publication) {
        return consume(
                Objects.requireNonNull(lease, "lease"),
                eventMapper.map(Objects.requireNonNull(publication, "publication")));
    }

    private ProjectionConsumptionResult consume(
            ProjectionGenerationLease lease, ProjectionEvent event) {
        ProjectionConsumptionResult result = generationTransaction.execute(status -> store.consume(
                lease, consumerName(), event, handler, clock.instant()));
        return Objects.requireNonNull(result, "projection consumption result");
    }
}

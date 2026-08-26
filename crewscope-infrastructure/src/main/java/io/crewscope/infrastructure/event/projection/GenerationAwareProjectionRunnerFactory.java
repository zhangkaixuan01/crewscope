package io.crewscope.infrastructure.event.projection;

import java.time.Clock;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

/** Creates one dynamically routed consumer for each business projection handler. */
@Component
public class GenerationAwareProjectionRunnerFactory {

    private final JdbcProjectionGenerationRegistry registry;
    private final JdbcGenerationProjectionStore store;
    private final ProjectionEventJsonMapper eventMapper;
    private final PlatformTransactionManager transactionManager;
    private final Clock clock;

    @Autowired
    public GenerationAwareProjectionRunnerFactory(
            JdbcProjectionGenerationRegistry registry,
            JdbcGenerationProjectionStore store,
            ProjectionEventJsonMapper eventMapper,
            PlatformTransactionManager transactionManager) {
        this(registry, store, eventMapper, transactionManager, Clock.systemUTC());
    }

    GenerationAwareProjectionRunnerFactory(
            JdbcProjectionGenerationRegistry registry,
            JdbcGenerationProjectionStore store,
            ProjectionEventJsonMapper eventMapper,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.store = Objects.requireNonNull(store, "store");
        this.eventMapper = Objects.requireNonNull(eventMapper, "eventMapper");
        this.transactionManager = Objects.requireNonNull(transactionManager, "transactionManager");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public GenerationAwareProjectionRunner create(GenerationAwareProjectionHandler handler) {
        return new GenerationAwareProjectionRunner(
                handler, registry, store, eventMapper, transactionManager, clock);
    }
}

package io.crewscope.infrastructure.event.projection;

import io.crewscope.application.event.publication.DomainEventConsumer;
import io.crewscope.application.event.publication.EventPublication;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** One transport consumer that fans out to all registered Generation-aware projection runners. */
public final class GenerationAwareProjectionRouter implements DomainEventConsumer {

    private final List<GenerationAwareProjectionRunner> runners;

    public GenerationAwareProjectionRouter(
            List<GenerationAwareProjectionHandler> handlers,
            GenerationAwareProjectionRunnerFactory factory) {
        GenerationAwareProjectionRunnerFactory runnerFactory = Objects.requireNonNull(
                factory, "factory");
        List<GenerationAwareProjectionHandler> registered = List.copyOf(
                Objects.requireNonNull(handlers, "handlers"));
        Set<String> names = new HashSet<>();
        for (GenerationAwareProjectionHandler handler : registered) {
            String name = Objects.requireNonNull(handler, "handler")
                    .definition().name().value();
            if (!names.add(name)) {
                throw new IllegalArgumentException(
                        "Duplicate Generation-aware projection handler: " + name);
            }
        }
        this.runners = registered.stream()
                .sorted((left, right) -> left.definition().name().value().compareTo(
                        right.definition().name().value()))
                .map(runnerFactory::create)
                .toList();
    }

    @Override
    public String consumerName() {
        return "projection-generation-router";
    }

    @Override
    public void consume(EventPublication publication) {
        EventPublication event = Objects.requireNonNull(publication, "publication");
        for (GenerationAwareProjectionRunner runner : runners) {
            runner.consume(event);
        }
    }

    public int registeredProjectionCount() {
        return runners.size();
    }
}

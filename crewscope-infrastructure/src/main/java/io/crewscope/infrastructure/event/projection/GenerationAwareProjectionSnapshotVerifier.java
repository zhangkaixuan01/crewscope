package io.crewscope.infrastructure.event.projection;

import io.crewscope.application.projection.ProjectionSnapshotVerifier;
import io.crewscope.application.projection.ProjectionVerificationSnapshots;
import io.crewscope.domain.projection.ProjectionDefinition;
import io.crewscope.domain.projection.ProjectionGenerationState;
import io.crewscope.domain.projection.ProjectionSnapshot;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Resolves canonical verification against the exact registered runtime handler. */
@Component
public class GenerationAwareProjectionSnapshotVerifier implements ProjectionSnapshotVerifier {

    private final Map<String, GenerationAwareProjectionHandler> handlers;

    public GenerationAwareProjectionSnapshotVerifier(
            List<GenerationAwareProjectionHandler> handlers) {
        Map<String, GenerationAwareProjectionHandler> indexed = new LinkedHashMap<>();
        for (GenerationAwareProjectionHandler handler : List.copyOf(handlers)) {
            String name = handler.definition().name().value();
            if (indexed.put(name, handler) != null) {
                throw new IllegalStateException("Duplicate generation-aware projection: " + name);
            }
        }
        this.handlers = Map.copyOf(indexed);
    }

    @Override
    public ProjectionVerificationSnapshots verify(
            ProjectionDefinition definition, ProjectionGenerationState target) {
        GenerationAwareProjectionHandler handler = handler(definition, target);
        return new ProjectionVerificationSnapshots(
                handler.expectedSnapshot(target.key().organizationId()),
                handler.actualSnapshot(target.key()));
    }

    @Override
    public ProjectionSnapshot current(
            ProjectionDefinition definition, ProjectionGenerationState target) {
        return handler(definition, target).actualSnapshot(target.key());
    }

    private GenerationAwareProjectionHandler handler(
            ProjectionDefinition definition, ProjectionGenerationState target) {
        ProjectionDefinition required = Objects.requireNonNull(definition, "definition");
        ProjectionGenerationState generation = Objects.requireNonNull(target, "target");
        if (!required.name().equals(generation.key().projectionName())
                || !required.version().equals(generation.definitionVersion())) {
            throw new IllegalArgumentException("Projection verification coordinates are mixed");
        }
        GenerationAwareProjectionHandler handler = handlers.get(required.name().value());
        if (handler == null || !handler.definition().equals(required)) {
            throw new IllegalStateException("Projection runtime definition is not registered");
        }
        return handler;
    }
}

package io.crewscope.application.operations;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Complete threshold registry; missing components fail closed during composition. */
public record OperationsHealthThresholds(
        Map<OperationsHealthComponent, OperationsComponentThreshold> components) {

    public OperationsHealthThresholds {
        Objects.requireNonNull(components, "components");
        EnumMap<OperationsHealthComponent, OperationsComponentThreshold> copy =
                new EnumMap<>(OperationsHealthComponent.class);
        copy.putAll(components);
        if (copy.size() != OperationsHealthComponent.values().length
                || copy.values().stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("health thresholds must cover every component");
        }
        components = Map.copyOf(copy);
    }

    public OperationsComponentThreshold require(OperationsHealthComponent component) {
        return Objects.requireNonNull(
                components.get(Objects.requireNonNull(component, "component")),
                "component threshold");
    }
}

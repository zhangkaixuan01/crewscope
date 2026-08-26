package io.crewscope.application.operations;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** One transactionally consistent safe snapshot returned by the future M6-I01 adapter. */
public record OperationsHealthSnapshot(
        OrganizationId organizationId,
        UtcTimestamp observedAt,
        List<OperationsComponentObservation> components,
        List<ProjectionHealthDiagnostic> projections,
        List<OperationsRecoveryTarget> recoveryCandidates) {

    public OperationsHealthSnapshot {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
        components = List.copyOf(Objects.requireNonNull(components, "components"));
        projections = List.copyOf(Objects.requireNonNull(projections, "projections"));
        recoveryCandidates = List.copyOf(
                Objects.requireNonNull(recoveryCandidates, "recoveryCandidates"));
        EnumSet<OperationsHealthComponent> found = EnumSet.noneOf(OperationsHealthComponent.class);
        for (OperationsComponentObservation component : components) {
            if (!found.add(component.component())) {
                throw new IllegalArgumentException("health snapshot contains a duplicate component");
            }
        }
        if (found.size() != OperationsHealthComponent.values().length) {
            throw new IllegalArgumentException("health snapshot must contain every component");
        }
        HashSet<String> projectionNames = new HashSet<>();
        if (projections.stream().anyMatch(value ->
                !projectionNames.add(value.projectionName().value()))) {
            throw new IllegalArgumentException("health snapshot contains duplicate projections");
        }
        HashSet<String> recoveryTargets = new HashSet<>();
        if (recoveryCandidates.stream().anyMatch(value ->
                !recoveryTargets.add(value.referenceHash()))) {
            throw new IllegalArgumentException("health snapshot contains duplicate recovery targets");
        }
    }
}

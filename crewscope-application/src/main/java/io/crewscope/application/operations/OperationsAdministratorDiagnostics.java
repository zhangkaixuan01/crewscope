package io.crewscope.application.operations;

import java.util.List;
import java.util.Objects;

/** Permission-gated detail view with safe recovery coordinates and no raw event payload. */
public record OperationsAdministratorDiagnostics(
        OperationsMemberHealthSummary summary,
        List<ProjectionHealthDiagnostic> projections,
        List<OperationsRecoveryTarget> recoveryCandidates) {

    public OperationsAdministratorDiagnostics {
        summary = Objects.requireNonNull(summary, "summary");
        projections = List.copyOf(Objects.requireNonNull(projections, "projections"));
        recoveryCandidates = List.copyOf(
                Objects.requireNonNull(recoveryCandidates, "recoveryCandidates"));
    }
}

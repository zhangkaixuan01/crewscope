package io.crewscope.server.api;

import io.crewscope.application.operations.OperationsMemberHealthSummary;
import java.util.List;

/** Member-facing operations health: observed level plus every component summary. */
public record HealthSummaryResponse(
        String observedAt, String health, List<ComponentHealthResponse> components) {

    static HealthSummaryResponse from(OperationsMemberHealthSummary value) {
        return new HealthSummaryResponse(
                value.observedAt().toString(),
                value.health().name(),
                value.components().stream().map(ComponentHealthResponse::from).toList());
    }
}

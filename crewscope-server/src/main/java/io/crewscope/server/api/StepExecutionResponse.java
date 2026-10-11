package io.crewscope.server.api;

import io.crewscope.domain.task.StepExecution;
import io.crewscope.domain.task.TaskExecutionFailure;
import java.time.Instant;
import java.util.UUID;

/** One step execution row with its checkpoint and failure classification. */
public record StepExecutionResponse(
        UUID id,
        UUID planVersionId,
        String planStepKey,
        int sequence,
        boolean critical,
        int runAttempt,
        int maxRunAttempts,
        String status,
        String waitReason,
        StepCheckpointResponse checkpoint,
        String failureClass,
        String failureCode,
        long version,
        AuditResponse audit) {
    static StepExecutionResponse from(StepExecution value) {
        TaskExecutionFailure failure = value.failure().orElse(null);
        return new StepExecutionResponse(
                value.id().value(),
                value.planVersionId().value(),
                value.planStepKey(),
                value.sequence(),
                value.critical(),
                value.runAttempt(),
                value.maxRunAttempts(),
                value.status().name(),
                value.waitReason().map(Enum::name).orElse(null),
                value.checkpoint().map(StepCheckpointResponse::from).orElse(null),
                failure == null ? null : failure.failureClass().name(),
                failure == null ? null : failure.code(),
                value.version(),
                AuditResponse.from(value.audit()));
    }
}

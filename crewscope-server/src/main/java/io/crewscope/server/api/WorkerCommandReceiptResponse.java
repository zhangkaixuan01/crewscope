package io.crewscope.server.api;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.task.WorkerCommandOperation;
import io.crewscope.application.task.WorkerTaskCommandResult;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;

/** Durable acknowledgement plus the next versions required by the Worker protocol. */
public record WorkerCommandReceiptResponse(
        UUID commandId,
        UUID domainEventId,
        long committedVersion,
        UUID correlationId,
        WorkerCommandOperation operation,
        Long taskExecutionVersion,
        Long leaseVersion) {

    public WorkerCommandReceiptResponse {
        Objects.requireNonNull(commandId, "commandId");
        Objects.requireNonNull(domainEventId, "domainEventId");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(operation, "operation");
        if (committedVersion < 0
                || (taskExecutionVersion != null && taskExecutionVersion < 0)
                || (leaseVersion != null && leaseVersion < 0)) {
            throw new IllegalArgumentException("committed versions must not be negative");
        }
        if (taskExecutionVersion == null && leaseVersion == null) {
            throw new IllegalArgumentException("at least one committed version must exist");
        }
    }

    /**
     * Replays derive the same next versions from the request preconditions already bound into the
     * durable request hash; no current mutable fact is read to fabricate an old response.
     */
    public static ResponseEntity<WorkerCommandReceiptResponse> accepted(
            CommandExecution<WorkerTaskCommandResult> execution,
            WorkerCommandOperation operation,
            Optional<Long> replayExecutionVersion,
            Optional<Long> replayLeaseVersion) {
        CommandExecution<WorkerTaskCommandResult> source = Objects.requireNonNull(
                execution, "execution");
        WorkerCommandOperation requiredOperation = Objects.requireNonNull(operation, "operation");
        source.result().ifPresent(result -> {
            if (result.operation() != requiredOperation) {
                throw new IllegalArgumentException(
                        "Worker command result operation does not match the response route");
            }
        });
        Optional<Long> executionVersion = source.result()
                .flatMap(WorkerTaskCommandResult::taskExecutionVersion)
                .or(() -> Objects.requireNonNull(
                        replayExecutionVersion, "replayExecutionVersion"));
        Optional<Long> leaseVersion = source.result()
                .flatMap(WorkerTaskCommandResult::leaseVersion)
                .or(() -> Objects.requireNonNull(replayLeaseVersion, "replayLeaseVersion"));
        CommandReceipt receipt = source.receipt();
        ResponseEntity.BodyBuilder response = ResponseEntity.accepted()
                .cacheControl(CacheControl.noStore());
        if (source.replayed()) {
            response.header(ApiHeaders.IDEMPOTENCY_REPLAYED, "true");
        }
        return response.body(new WorkerCommandReceiptResponse(
                receipt.commandId(),
                receipt.domainEventId(),
                receipt.committedVersion(),
                receipt.correlationId(),
                requiredOperation,
                executionVersion.orElse(null),
                leaseVersion.orElse(null)));
    }
}

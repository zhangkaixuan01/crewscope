package io.crewscope.server.api;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;

/** Public command acknowledgement used while projections converge asynchronously. */
public record CommandReceiptResponse(
        UUID commandId,
        UUID domainEventId,
        long committedVersion,
        UUID correlationId) {

    public CommandReceiptResponse {
        Objects.requireNonNull(commandId, "commandId");
        Objects.requireNonNull(domainEventId, "domainEventId");
        Objects.requireNonNull(correlationId, "correlationId");
        if (committedVersion < 0) {
            throw new IllegalArgumentException("committedVersion must not be negative");
        }
    }

    public static CommandReceiptResponse from(CommandReceipt receipt) {
        CommandReceipt source = Objects.requireNonNull(receipt, "receipt");
        return new CommandReceiptResponse(
                source.commandId(),
                source.domainEventId(),
                source.committedVersion(),
                source.correlationId());
    }

    /** Returns the same 202 contract for first execution and replay, marking only the replay header. */
    public static ResponseEntity<CommandReceiptResponse> accepted(CommandExecution<?> execution) {
        CommandExecution<?> result = Objects.requireNonNull(execution, "execution");
        ResponseEntity.BodyBuilder response =
                ResponseEntity.accepted().cacheControl(CacheControl.noStore());
        if (result.replayed()) {
            response.header(ApiHeaders.IDEMPOTENCY_REPLAYED, "true");
        }
        return response.body(from(result.receipt()));
    }
}

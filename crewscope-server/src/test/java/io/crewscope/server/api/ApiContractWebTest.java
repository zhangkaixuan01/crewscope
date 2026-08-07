package io.crewscope.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.command.CommandRequestHash;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.domain.shared.error.IdempotencyConflictException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.workitem.WorkItemId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ServerWebExchange;

/** Proves the shared command, replay, validation, concurrency and safe-error HTTP protocol. */
class ApiContractWebTest {

    private static final UUID CORRELATION_ID =
            UUID.fromString("01989ee2-f6b0-7cda-97c4-1b337043d401");
    private static final WorkItemId WORK_ITEM_ID =
            WorkItemId.from("01989ee2-f6b0-7cda-97c4-1b337043d402");

    private ContractController controller;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        controller = new ContractController();
        client = WebTestClient.bindToController(controller)
                .controllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void returnsAStableAcceptedCommandReceipt() {
        client.post()
                .uri("/api/v1/contract/commands")
                .header(ApiHeaders.IDEMPOTENCY_KEY, "command-success-1")
                .header(ApiCorrelationIds.HEADER, CORRELATION_ID.toString())
                .bodyValue(Map.of("title", "Create baseline"))
                .exchange()
                .expectStatus()
                .isAccepted()
                .expectHeader()
                .doesNotExist(ApiHeaders.IDEMPOTENCY_REPLAYED)
                .expectBody()
                .jsonPath("$.commandId")
                .isNotEmpty()
                .jsonPath("$.domainEventId")
                .isNotEmpty()
                .jsonPath("$.committedVersion")
                .isEqualTo(0)
                .jsonPath("$.correlationId")
                .isEqualTo(CORRELATION_ID.toString());
    }

    @Test
    void returnsTheSameReceiptAndReplayHeaderForAnIdenticalRetry() {
        CommandReceiptResponse first = submit("command-replay-1", "Retry baseline");

        client.post()
                .uri("/api/v1/contract/commands")
                .header(ApiHeaders.IDEMPOTENCY_KEY, "command-replay-1")
                .header(ApiCorrelationIds.HEADER, UUID.randomUUID().toString())
                .bodyValue(Map.of("title", "Retry baseline"))
                .exchange()
                .expectStatus()
                .isAccepted()
                .expectHeader()
                .valueEquals(ApiHeaders.IDEMPOTENCY_REPLAYED, "true")
                .expectBody(CommandReceiptResponse.class)
                .isEqualTo(first);

        assertEquals(1, controller.executionCount);
    }

    @Test
    void rejectsChangedContentUsingTheSameIdempotencyKey() {
        submit("command-conflict-1", "Original request");

        client.post()
                .uri("/api/v1/contract/commands")
                .header(ApiHeaders.IDEMPOTENCY_KEY, "command-conflict-1")
                .header(ApiCorrelationIds.HEADER, CORRELATION_ID.toString())
                .bodyValue(Map.of("title", "Changed request"))
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("idempotency_conflict")
                .jsonPath("$.retryable")
                .isEqualTo(false)
                .jsonPath("$.correlationId")
                .isEqualTo(CORRELATION_ID.toString())
                .jsonPath("$.details.existingRequestHash")
                .isNotEmpty()
                .jsonPath("$.details.requestedRequestHash")
                .isNotEmpty();
    }

    @Test
    void mapsOptimisticConflictsAndCurrentVersion() {
        client.patch()
                .uri("/api/v1/contract/resources/current")
                .header(ApiHeaders.IF_MATCH, "\"11\"")
                .header(ApiCorrelationIds.HEADER, CORRELATION_ID.toString())
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectHeader()
                .valueEquals("Cache-Control", "no-store")
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("optimistic_lock_conflict")
                .jsonPath("$.currentVersion")
                .isEqualTo(12)
                .jsonPath("$.details.expectedVersion")
                .isEqualTo("11")
                .jsonPath("$.details.actualVersion")
                .isEqualTo("12");
    }

    @Test
    void requiresOneStrongIfMatchVersion() {
        client.patch()
                .uri("/api/v1/contract/resources/current")
                .exchange()
                .expectStatus()
                .isEqualTo(428)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("precondition_required");

        client.patch()
                .uri("/api/v1/contract/resources/current")
                .header(ApiHeaders.IF_MATCH, "W/\"12\"")
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("invalid_if_match");
    }

    @Test
    void mapsBeanValidationAndMissingIdempotencyHeadersWithoutRejectedValues() {
        client.post()
                .uri("/api/v1/contract/commands")
                .header(ApiHeaders.IDEMPOTENCY_KEY, "validation-1")
                .bodyValue(Map.of("title", " "))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("invalid_request")
                .jsonPath("$.details.title")
                .isEqualTo("NotBlank");

        client.post()
                .uri("/api/v1/contract/commands")
                .bodyValue(Map.of("title", "Valid"))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("invalid_request")
                .jsonPath("$.details.header")
                .isEqualTo(ApiHeaders.IDEMPOTENCY_KEY);
    }

    @Test
    void mapsDomainValidationSeparatelyFromTransportValidation() {
        client.post()
                .uri("/api/v1/contract/domain-validation")
                .header(ApiCorrelationIds.HEADER, CORRELATION_ID.toString())
                .exchange()
                .expectStatus()
                .isEqualTo(422)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("invalid_value")
                .jsonPath("$.details.field")
                .isEqualTo("workItem.title")
                .jsonPath("$.correlationId")
                .isEqualTo(CORRELATION_ID.toString());
    }

    @Test
    void normalizesUnknownFailuresWithoutLeakingTheirMessage() {
        client.post()
                .uri("/api/v1/contract/fail")
                .header(ApiCorrelationIds.HEADER, CORRELATION_ID.toString())
                .exchange()
                .expectStatus()
                .is5xxServerError()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("internal_error")
                .jsonPath("$.message")
                .isEqualTo("The request could not be completed")
                .jsonPath("$.message")
                .value(message -> {
                    if (message.toString().contains("database-password")) {
                        throw new AssertionError("Internal exception message leaked");
                    }
                });
    }

    private CommandReceiptResponse submit(String key, String title) {
        return client.post()
                .uri("/api/v1/contract/commands")
                .header(ApiHeaders.IDEMPOTENCY_KEY, key)
                .header(ApiCorrelationIds.HEADER, CORRELATION_ID.toString())
                .bodyValue(Map.of("title", title))
                .exchange()
                .expectStatus()
                .isAccepted()
                .expectBody(CommandReceiptResponse.class)
                .returnResult()
                .getResponseBody();
    }

    @RestController
    @RequestMapping("/api/v1/contract")
    static class ContractController {

        private final Map<IdempotencyKey, StoredCommand> commands = new ConcurrentHashMap<>();
        private int executionCount;

        @PostMapping("/commands")
        ResponseEntity<CommandReceiptResponse> command(
                @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String keyHeader,
                @Valid @RequestBody ContractCommand command,
                ServerWebExchange exchange) {
            IdempotencyKey key = ApiHeaders.requireIdempotencyKey(keyHeader);
            CommandRequestHash requestHash =
                    CommandRequestHash.sha256("CONTRACT_COMMAND", command.title().strip());
            StoredCommand existing = commands.get(key);
            if (existing != null) {
                if (!existing.requestHash().equals(requestHash)) {
                    throw new IdempotencyConflictException(
                            key.value(),
                            existing.requestHash().value(),
                            requestHash.value());
                }
                return CommandReceiptResponse.accepted(
                        CommandExecution.replayed(existing.receipt()));
            }
            executionCount++;
            CommandReceipt receipt = new CommandReceipt(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    0,
                    ApiCorrelationIds.resolve(exchange));
            commands.put(key, new StoredCommand(requestHash, receipt));
            return CommandReceiptResponse.accepted(
                    CommandExecution.completed("accepted", receipt));
        }

        @PatchMapping("/resources/current")
        ResponseEntity<Void> update(
                @RequestHeader(name = ApiHeaders.IF_MATCH, required = false) String ifMatch) {
            long expectedVersion = ApiHeaders.requireIfMatch(ifMatch);
            if (expectedVersion != 12) {
                throw new OptimisticLockConflictException(
                        "WorkItem", WORK_ITEM_ID, expectedVersion, 12);
            }
            return ResponseEntity.noContent()
                    .eTag(ApiHeaders.versionEtag(12))
                    .build();
        }

        @PostMapping("/fail")
        void fail() {
            throw new IllegalStateException("database-password must stay private");
        }

        @PostMapping("/domain-validation")
        void domainValidation() {
            throw new DomainValidationException("workItem.title", "must not be blank");
        }
    }

    record ContractCommand(
            @NotBlank @Size(max = 40) String title) {}

    record StoredCommand(CommandRequestHash requestHash, CommandReceipt receipt) {}
}

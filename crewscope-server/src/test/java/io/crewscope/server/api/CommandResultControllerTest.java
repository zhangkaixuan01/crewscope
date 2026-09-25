package io.crewscope.server.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.crewscope.application.command.*;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.identity.*;
import io.crewscope.domain.shared.id.*;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

class CommandResultControllerTest {
  private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-09-22T00:00:00Z");
  private final OrganizationId org = OrganizationId.generate();
  private final Principal actor = Principal.create(PrincipalId.generate(),
      PrincipalScope.organization(org), PrincipalType.USER, Optional.empty(), "Creator",
      Optional.empty(), PrincipalVisibility.ORGANIZATION, NOW);
  private final CommandResultQueryService service = mock(CommandResultQueryService.class);
  private final WebTestClient client = client((auth, organization, correlation) ->
      Mono.just(new TeamAccessContext(actor, false)));
  private final String path = "/api/v1/organizations/" + org + "/command-results";

  @Test void returnsOnlyReceiptAndAuthorizedCoordinatesWithoutCaching() {
    WorkProjectId project = WorkProjectId.generate();
    var receipt = new CommandReceipt(UUID.randomUUID(), UUID.randomUUID(), 0, UUID.randomUUID());
    var result = new CommandResult(org, new IdempotencyKey("create-project-1"), actor.id(),
        "CREATE_WORK_PROJECT", TeamId.generate(), Optional.of(project),
        CommandResult.ResourceType.WORK_PROJECT, project.value(), 0, receipt, NOW);
    when(service.find(any(), any(), any())).thenReturn(Optional.of(result));
    client.get().uri(path).header("Idempotency-Key", "create-project-1").exchange()
        .expectStatus().isOk().expectHeader().valueEquals("Cache-Control", "no-store")
        .expectBody().jsonPath("$.receipt.commandId").isEqualTo(receipt.commandId().toString())
        .jsonPath("$.result.resourceId").isEqualTo(project.toString())
        .jsonPath("$.result.type").isEqualTo("WORK_PROJECT")
        .jsonPath("$.result.stage").isEqualTo("COMMITTED")
        .jsonPath("$.result.committedVersion").isEqualTo(0)
        .jsonPath("$.actorId").doesNotExist().jsonPath("$.idempotencyKey").doesNotExist()
        .jsonPath("$.result.title").doesNotExist().jsonPath("$.result.body").doesNotExist();
  }

  @Test void missingOrInaccessibleResultsHaveOneSafeNotFoundResponse() {
    when(service.find(any(), any(), any())).thenReturn(Optional.empty());
    client.get().uri(path).header("Idempotency-Key", "unknown-key").exchange()
        .expectStatus().isNotFound().expectHeader().valueEquals("Cache-Control", "no-store")
        .expectBody().jsonPath("$.code").isEqualTo("command_result_not_found")
        .jsonPath("$.result").doesNotExist();
  }

  @Test void queryStringCannotReplaceTheRequiredHeader() {
    client.get().uri(path + "?idempotencyKey=not-a-header").exchange()
        .expectStatus().isBadRequest().expectHeader().valueEquals("Cache-Control", "no-store");
    verifyNoInteractions(service);
  }

  @ParameterizedTest @ValueSource(strings = {"", "one,two", "key with spaces"})
  void invalidHeaderIsRejectedWithoutQueryingResults(String key) {
    client.get().uri(path).header("Idempotency-Key", key).exchange()
        .expectStatus().isBadRequest().expectHeader().valueEquals("Cache-Control", "no-store");
    verifyNoInteractions(service);
  }

  @Test void duplicateHeadersAreRejected() {
    client.get().uri(path).header("Idempotency-Key", "one", "two").exchange()
        .expectStatus().isBadRequest().expectHeader().valueEquals("Cache-Control", "no-store");
    verifyNoInteractions(service);
  }

  @Test void authenticationFailureRemains401() {
    client((auth, organization, correlation) -> Mono.error(new ApiRequestException(
        HttpStatus.UNAUTHORIZED, "unauthorized", "Authentication required", Map.of())))
        .get().uri(path).header("Idempotency-Key", "one").exchange()
        .expectStatus().isUnauthorized().expectHeader().valueEquals("Cache-Control", "no-store");
    verifyNoInteractions(service);
  }

  @Test void lostOrganizationAccessUsesTheSame404WithoutDisclosingResultExistence() {
    client((auth, organization, correlation) -> Mono.error(
        new io.crewscope.domain.shared.error.PolicyDeniedException("act in this Organization")))
        .get().uri(path).header("Idempotency-Key", "one").exchange()
        .expectStatus().isNotFound().expectHeader().valueEquals("Cache-Control", "no-store")
        .expectBody().jsonPath("$.code").isEqualTo("command_result_not_found");
    verifyNoInteractions(service);
  }

  private WebTestClient client(TeamRequestIdentityResolver resolver) {
    return WebTestClient.bindToController(new CommandResultController(service, resolver))
        .controllerAdvice(new ApiExceptionHandler()).build();
  }
}

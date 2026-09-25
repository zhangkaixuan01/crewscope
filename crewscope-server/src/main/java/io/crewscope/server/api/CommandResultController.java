package io.crewscope.server.api;

import io.crewscope.application.command.CommandResult;
import io.crewscope.application.command.CommandResultQueryService;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Read-only, actor-qualified recovery. A missing result never means the command did not commit. */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/command-results")
public final class CommandResultController {
  private final CommandResultQueryService service;
  private final TeamRequestIdentityResolver identities;

  public CommandResultController(CommandResultQueryService service, TeamRequestIdentityResolver identities) {
    this.service = service;
    this.identities = identities;
  }

  @GetMapping
  public Mono<ResponseEntity<ResultResponse>> get(@PathVariable String organizationId,
      @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String ignoredKey,
      Authentication authentication, ServerWebExchange exchange) {
    exchange.getResponse().getHeaders().setCacheControl("no-store");
    IdempotencyKey key = ApiHeaders.requireSingleIdempotencyKey(
        exchange.getRequest().getHeaders().get(ApiHeaders.IDEMPOTENCY_KEY));
    OrganizationId organization = OrganizationId.from(organizationId);
    return identities.resolve(authentication, organization, ApiCorrelationIds.resolve(exchange))
        .onErrorMap(PolicyDeniedException.class, denied -> notFound())
        .flatMap(access -> Mono.fromCallable(() -> service.find(access, organization, key))
            .subscribeOn(Schedulers.boundedElastic()))
        .map(result -> ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ResultResponse.from(result.orElseThrow(CommandResultController::notFound))));
  }

  private static ApiRequestException notFound() {
    return new ApiRequestException(HttpStatus.NOT_FOUND, "command_result_not_found",
        "暂未找到可访问的结果", Map.of());
  }

  public record ResultResponse(CommandReceiptResponse receipt, ResultCoordinate result) {
    static ResultResponse from(CommandResult value) {
      return new ResultResponse(CommandReceiptResponse.from(value.receipt()),
          new ResultCoordinate(value.resourceType(), value.organizationId().value(), value.teamId().value(),
              value.projectId().map(id -> id.value()).orElse(null), value.resourceId(),
              value.resourceVersion(), "COMMITTED"));
    }
  }

  public record ResultCoordinate(CommandResult.ResourceType type, UUID organizationId, UUID teamId,
      UUID projectId, UUID resourceId, long committedVersion, String stage) {}
}

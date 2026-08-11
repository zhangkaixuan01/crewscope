package io.crewscope.server.api;

import io.crewscope.application.provider.ProviderBindingCandidate;
import io.crewscope.application.provider.ProviderBindingLookup;
import io.crewscope.application.provider.ProviderBindingQueryService;
import io.crewscope.domain.provider.ProviderCapability;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Read-only Team API for the current Native WorkItem Provider capability and Binding verdict. */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/teams/{teamId}/provider-bindings")
public final class ProviderBindingController {

  private final ProviderBindingQueryService service;
  private final TeamRequestIdentityResolver identityResolver;

  public ProviderBindingController(
      ProviderBindingQueryService service, TeamRequestIdentityResolver identityResolver) {
    this.service = service;
    this.identityResolver = identityResolver;
  }

  @GetMapping
  public Mono<ResponseEntity<ProviderBindingLookupResponse>> getDefault(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      Authentication authentication,
      ServerWebExchange exchange) {
    OrganizationId organization = organizationId(organizationId);
    TeamId team = teamId(teamId);
    return identityResolver
        .resolve(authentication, organization, ApiCorrelationIds.resolve(exchange))
        .flatMap(access -> blocking(() -> service.resolveDefault(access, organization, team)))
        .map(
            lookup ->
                ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(ProviderBindingLookupResponse.from(lookup)));
  }

  private static OrganizationId organizationId(String value) {
    try {
      return OrganizationId.from(value);
    } catch (IllegalArgumentException failure) {
      throw invalidIdentifier("organizationId");
    }
  }

  private static TeamId teamId(String value) {
    try {
      return TeamId.from(value);
    } catch (IllegalArgumentException failure) {
      throw invalidIdentifier("teamId");
    }
  }

  private static ApiRequestException invalidIdentifier(String field) {
    return new ApiRequestException(
        org.springframework.http.HttpStatus.BAD_REQUEST,
        "invalid_request",
        "Request contains an invalid identifier",
        Map.of("field", field));
  }

  private static <T> Mono<T> blocking(Callable<T> action) {
    return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
  }

  public record ProviderBindingLookupResponse(
      String providerType,
      String definitionKey,
      String implementationKey,
      List<String> requestedCapabilities,
      String status,
      String level,
      ProviderBindingResponse binding,
      List<String> ambiguousBindingIds) {

    static ProviderBindingLookupResponse from(ProviderBindingLookup lookup) {
      return new ProviderBindingLookupResponse(
          lookup.registration().type().name(),
          lookup.registration().definitionKey(),
          lookup.registration().implementationKey(),
          capabilities(lookup.registration().capabilities()),
          lookup.resolution().status().name(),
          lookup.resolution().level().name(),
          lookup.resolution().candidate().map(ProviderBindingResponse::from).orElse(null),
          lookup.resolution().ambiguousBindingIds().stream().map(Object::toString).toList());
    }
  }

  public record ProviderBindingResponse(
      String id,
      String targetType,
      String workspaceId,
      String ownerType,
      boolean defaultUsage,
      String status,
      long version,
      String definitionId,
      long definitionVersion,
      String definitionStatus,
      String implementationId,
      long implementationVersion,
      String implementationStatus,
      boolean connectionless,
      List<String> effectiveCapabilities,
      boolean resourceUnrestricted,
      List<String> effectiveResources) {

    static ProviderBindingResponse from(ProviderBindingCandidate candidate) {
      var binding = candidate.binding();
      return new ProviderBindingResponse(
          binding.id().toString(),
          binding.target().type().name(),
          binding.target().workspaceId().toString(),
          binding.owner().type().name(),
          binding.defaultUsage(),
          binding.status().name(),
          binding.version(),
          candidate.definition().id().toString(),
          binding.definitionVersion(),
          candidate.definition().status().name(),
          candidate.implementation().id().toString(),
          binding.implementationVersion(),
          candidate.implementation().status().name(),
          binding.connectionId().isEmpty(),
          capabilities(candidate.effectiveAccess().capabilities()),
          candidate.effectiveAccess().resources().unrestricted(),
          candidate.effectiveAccess().resources().resources().stream().sorted().toList());
    }
  }

  private static List<String> capabilities(
      io.crewscope.domain.provider.ProviderCapabilities capabilities) {
    return capabilities.values().stream()
        .map(ProviderCapability::value)
        .sorted()
        .toList();
  }
}

package io.crewscope.server.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.crewscope.application.identity.AuthenticationFlow;
import io.crewscope.application.identity.LocalAccountRegistrationCommand;
import io.crewscope.application.identity.LocalAccountRegistrationContext;
import io.crewscope.application.identity.LocalAccountRegistrationResult;
import io.crewscope.application.identity.LoginDefense;
import io.crewscope.application.identity.LoginDefenseRequest;
import io.crewscope.application.identity.LoginIdentifierResource;
import io.crewscope.application.identity.LocalAccountRegistrationService;
import io.crewscope.application.team.InvitationToken;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.server.config.RegistrationProperties;
import io.crewscope.server.security.login.ControlledNetworkSourceResolver;
import io.crewscope.server.security.session.BrowserSessionLifecycle;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Anonymous local-account registration boundary with post-commit Session establishment. */
@RestController
@RequestMapping("/api/v1/auth")
public class RegistrationController {

  private final LocalAccountRegistrationService registrations;
  private final RegistrationProperties properties;
  private final ObjectProvider<LoginDefense> loginDefense;
  private final ObjectProvider<ControlledNetworkSourceResolver> networkResolver;
  private final ObjectProvider<BrowserSessionLifecycle> sessions;

  public RegistrationController(
      LocalAccountRegistrationService registrations,
      RegistrationProperties properties,
      ObjectProvider<LoginDefense> loginDefense,
      ObjectProvider<ControlledNetworkSourceResolver> networkResolver,
      ObjectProvider<BrowserSessionLifecycle> sessions) {
    this.registrations = registrations;
    this.properties = properties;
    this.loginDefense = loginDefense;
    this.networkResolver = networkResolver;
    this.sessions = sessions;
  }

  /** Creates only a USER identity chain; tenant, role and Principal coordinates are server-owned. */
  @PostMapping("/register")
  public Mono<ResponseEntity<RegistrationResponse>> register(
      @Valid @RequestBody RegistrationRequest request,
      @RequestHeader(value = ApiHeaders.IDEMPOTENCY_KEY, required = false)
          List<String> idempotencyKeys,
      ServerWebExchange exchange) {
    UUID correlationId = ApiCorrelationIds.resolve(exchange);
    OrganizationId organizationId = registrationOrganization();
    LoginDefense defense = required(loginDefense.getIfAvailable());
    ControlledNetworkSourceResolver networks = required(networkResolver.getIfAvailable());
    BrowserSessionLifecycle sessionLifecycle = required(sessions.getIfAvailable());

    LoginDefenseRequest admission = new LoginDefenseRequest(
        AuthenticationFlow.REGISTRATION,
        LoginIdentifierResource.fromSubmitted(request.email()),
        networks.resolve(exchange));
    LocalAccountRegistrationContext context = new LocalAccountRegistrationContext(
        organizationId,
        properties.getMode(),
        ApiHeaders.requireSingleIdempotencyKey(idempotencyKeys),
        correlationId,
        Optional.empty());
    LocalAccountRegistrationCommand command = new LocalAccountRegistrationCommand(
        request.username(),
        request.email(),
        request.displayName(),
        request.password(),
        invitationToken(request.invitationToken()));

    return Mono.fromCompletionStage(() -> defense.admit(admission))
        .flatMap(decision -> decision.allowed()
            ? Mono.defer(() -> Mono.fromCompletionStage(registrations.register(context, command)))
                // Receipt lookup and transaction entry are blocking persistence operations.
                .subscribeOn(Schedulers.boundedElastic())
            : Mono.error(rateLimited()))
        .flatMap(result -> sessionLifecycle
            .establish(exchange, result.account())
            .onErrorMap(ignored -> sessionUnavailable())
            .thenReturn(response(result, organizationId)));
  }

  private OrganizationId registrationOrganization() {
    String value = properties.getOrganizationId();
    if (value == null || value.isBlank()) {
      throw unavailable();
    }
    try {
      return OrganizationId.from(value.strip());
    } catch (RuntimeException invalid) {
      throw unavailable();
    }
  }

  private static Optional<InvitationToken> invitationToken(Optional<String> value) {
    try {
      return value.filter(candidate -> !candidate.isBlank()).map(InvitationToken::new);
    } catch (RuntimeException invalid) {
      throw new ApiRequestException(
          HttpStatus.BAD_REQUEST,
          "invalid_request",
          "Request validation failed",
          Map.of("invitationToken", "invalid"));
    }
  }

  private static <T> T required(T value) {
    if (value == null) {
      throw unavailable();
    }
    return value;
  }

  private static ResponseEntity<RegistrationResponse> response(
      LocalAccountRegistrationResult result, OrganizationId organizationId) {
    RegistrationResponse body = new RegistrationResponse(
        result.account().id().value(),
        result.principal().id().value(),
        organizationId.value(),
        result.acceptedInvitation().map(value -> value.scope().teamId().value()),
        result.membership().map(value -> value.id().value()),
        result.acceptedInvitation().isEmpty(),
        result.receipt().commandId(),
        result.receipt().domainEventId(),
        result.receipt().committedVersion(),
        result.receipt().correlationId(),
        result.replayed());
    ResponseEntity.BodyBuilder response =
        ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
            .location(URI.create("/api/v1/account"))
            .cacheControl(CacheControl.noStore());
    if (result.replayed()) {
      response.header(ApiHeaders.IDEMPOTENCY_REPLAYED, "true");
    }
    return response.body(body);
  }

  private static ApiRequestException rateLimited() {
    return new ApiRequestException(
        HttpStatus.TOO_MANY_REQUESTS,
        "too_many_requests",
        "Registration is temporarily unavailable",
        Map.of());
  }

  private static ApiRequestException sessionUnavailable() {
    return new ApiRequestException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "registration_session_unavailable",
        "Registration was committed but the browser Session could not be established",
        Map.of());
  }

  private static ApiRequestException unavailable() {
    return new ApiRequestException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "registration_unavailable",
        "Registration is unavailable",
        Map.of());
  }

  /** Public fields are deliberately limited to self-service profile and proof values. */
  public record RegistrationRequest(
      @NotBlank @Size(min = 3, max = 64) String username,
      @NotBlank @Size(max = 254) String email,
      @NotBlank @Size(max = 200) String displayName,
      @NotBlank @Size(max = 512) String password,
      Optional<@Size(min = 43, max = 43) String> invitationToken) {

    public RegistrationRequest {
      invitationToken = invitationToken == null ? Optional.empty() : invitationToken;
    }

    /** Rejects tenant, Principal, role, provider and other server-owned registration fields. */
    @JsonAnySetter
    void rejectUnknownProperty(String ignoredName, Object ignoredValue) {
      throw new IllegalArgumentException("Unsupported registration property");
    }
  }

  /** Secret-free committed coordinates needed by onboarding and observability clients. */
  public record RegistrationResponse(
      UUID accountId,
      UUID principalId,
      UUID organizationId,
      Optional<UUID> teamId,
      Optional<UUID> memberId,
      boolean onboardingRequired,
      UUID commandId,
      UUID domainEventId,
      long committedVersion,
      UUID correlationId,
      boolean replayed) {}
}

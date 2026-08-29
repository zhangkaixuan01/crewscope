package io.crewscope.server.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.identity.AccountOrganizationResolution;
import io.crewscope.application.identity.AuthenticatedAccountOrganizationResolver;
import io.crewscope.application.team.CreateTeamCommand;
import io.crewscope.application.team.OnboardingAccountContext;
import io.crewscope.application.team.OnboardingApplicationService;
import io.crewscope.application.team.OnboardingStatus;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.domain.identity.SecurityVersion;
import io.crewscope.domain.identity.UserAccountId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.team.Team;
import io.crewscope.server.config.RegistrationProperties;
import io.crewscope.server.security.session.BrowserSessionPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Current-account onboarding state and replay-safe first-Team creation boundary. */
@RestController
@RequestMapping("/api/v1/onboarding")
public class OnboardingController {

  private final OnboardingApplicationService onboarding;
  private final AuthenticatedAccountOrganizationResolver accountResolver;
  private final RegistrationProperties registration;

  public OnboardingController(
      OnboardingApplicationService onboarding,
      AuthenticatedAccountOrganizationResolver accountResolver,
      RegistrationProperties registration) {
    this.onboarding = onboarding;
    this.accountResolver = accountResolver;
    this.registration = registration;
  }

  /** Returns TEAM_REQUIRED only when the current member has no active Team Membership. */
  @GetMapping
  public Mono<ResponseEntity<OnboardingResponse>> status(
      Authentication authentication, ServerWebExchange exchange) {
    return resolved(authentication, exchange)
        .flatMap(value -> blocking(() -> onboarding.status(value.accountContext())))
        .map(OnboardingController::statusResponse);
  }

  /** Creates the complete M1 Team foundation while an Account row serializes concurrent submits. */
  @PostMapping("/team")
  public Mono<ResponseEntity<CommandReceiptResponse>> createFirstTeam(
      @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) List<String> keys,
      @Valid @RequestBody CreateFirstTeamRequest request,
      Authentication authentication,
      ServerWebExchange exchange) {
    IdempotencyKey idempotencyKey = ApiHeaders.requireSingleIdempotencyKey(keys);
    UUID correlationId = ApiCorrelationIds.resolve(exchange);
    return resolved(authentication, exchange)
        .flatMap(value -> blocking(() -> onboarding.createFirstTeam(
            value.accountContext(),
            new TeamCommandContext(
                value.accountContext().teamAccess(),
                idempotencyKey,
                correlationId,
                Optional.empty()),
            new CreateTeamCommand(request.name()))))
        .map(CommandReceiptResponse::accepted);
  }

  private Mono<ResolvedOnboarding> resolved(
      Authentication authentication, ServerWebExchange exchange) {
    return Mono.fromCallable(() -> resolve(authentication, exchange))
        .subscribeOn(Schedulers.boundedElastic());
  }

  private ResolvedOnboarding resolve(
      Authentication authentication, ServerWebExchange exchange) {
    if (authentication == null
        || !authentication.isAuthenticated()
        || !(authentication.getPrincipal() instanceof BrowserSessionPrincipal sessionPrincipal)) {
      throw unauthenticated();
    }
    OrganizationId organizationId = organizationId();
    AccountOrganizationResolution resolution = accountResolver
        .resolveSession(
            new UserAccountId(sessionPrincipal.accountId()),
            new SecurityVersion(sessionPrincipal.securityVersion()),
            organizationId)
        .orElseThrow(OnboardingController::unauthenticated);
    TeamAccessContext access = new TeamAccessContext(
        resolution.principal(), resolution.account().allowsPlatformOperations());
    return new ResolvedOnboarding(new OnboardingAccountContext(
        resolution.account().id(),
        resolution.account().securityVersion(),
        access));
  }

  private OrganizationId organizationId() {
    String value = registration.getOrganizationId();
    if (value == null || value.isBlank()) {
      throw unavailable();
    }
    try {
      return OrganizationId.from(value.strip());
    } catch (RuntimeException invalid) {
      throw unavailable();
    }
  }

  private static ResponseEntity<OnboardingResponse> statusResponse(OnboardingStatus status) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(new OnboardingResponse(
            status.state().name(), status.onboardingRequired(), status.activeTeamCount()));
  }

  private static <T> Mono<T> blocking(java.util.concurrent.Callable<T> operation) {
    return Mono.fromCallable(operation).subscribeOn(Schedulers.boundedElastic());
  }

  private static ApiRequestException unauthenticated() {
    return new ApiRequestException(
        HttpStatus.UNAUTHORIZED,
        "authentication_required",
        "Authentication is required",
        Map.of());
  }

  private static ApiRequestException unavailable() {
    return new ApiRequestException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "onboarding_unavailable",
        "Onboarding is unavailable",
        Map.of());
  }

  public record CreateFirstTeamRequest(
      @NotBlank @Size(max = Team.MAX_NAME_LENGTH) String name) {

    /** Rejects Organization, owner, role, workspace and Personal Agent coordinates. */
    @JsonAnySetter
    void rejectUnknownProperty(String ignoredName, Object ignoredValue) {
      throw new IllegalArgumentException("Unsupported onboarding property");
    }
  }

  public record OnboardingResponse(
      String state, boolean onboardingRequired, int activeTeamCount) {}

  private record ResolvedOnboarding(OnboardingAccountContext accountContext) {}
}

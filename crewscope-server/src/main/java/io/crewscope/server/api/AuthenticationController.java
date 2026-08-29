package io.crewscope.server.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.crewscope.application.identity.AccountOrganizationResolution;
import io.crewscope.application.identity.AuthenticatedAccountOrganizationResolver;
import io.crewscope.application.identity.AuthenticationFlow;
import io.crewscope.application.identity.LocalAccountLoginCommand;
import io.crewscope.application.identity.LocalAccountLoginService;
import io.crewscope.application.identity.LoginDefense;
import io.crewscope.application.identity.LoginDefenseRequest;
import io.crewscope.application.identity.LoginIdentifierResource;
import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.TeamMemberRepository;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.domain.identity.RegistrationMode;
import io.crewscope.domain.identity.UserAccount;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamPermission;
import io.crewscope.domain.team.TeamRole;
import io.crewscope.domain.team.TeamRoleId;
import io.crewscope.server.config.RegistrationProperties;
import io.crewscope.server.security.login.ControlledNetworkSourceResolver;
import io.crewscope.server.security.session.BrowserSessionLifecycle;
import io.crewscope.server.security.session.BrowserSessionPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** JSON login, logout and current browser Session projection for local CrewScope accounts. */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthenticationController {

  private final ObjectProvider<LocalAccountLoginService> logins;
  private final ObjectProvider<LoginDefense> loginDefense;
  private final ObjectProvider<ControlledNetworkSourceResolver> networkResolver;
  private final ObjectProvider<BrowserSessionLifecycle> sessions;
  private final AuthenticatedAccountOrganizationResolver accountResolver;
  private final TeamRepository teams;
  private final TeamMemberRepository members;
  private final MemberRoleRepository memberRoles;
  private final TeamRoleRepository roles;
  private final RegistrationProperties registration;
  private final TimeProvider timeProvider;

  public AuthenticationController(
      ObjectProvider<LocalAccountLoginService> logins,
      ObjectProvider<LoginDefense> loginDefense,
      ObjectProvider<ControlledNetworkSourceResolver> networkResolver,
      ObjectProvider<BrowserSessionLifecycle> sessions,
      AuthenticatedAccountOrganizationResolver accountResolver,
      TeamRepository teams,
      TeamMemberRepository members,
      MemberRoleRepository memberRoles,
      TeamRoleRepository roles,
      RegistrationProperties registration,
      TimeProvider timeProvider) {
    this.logins = logins;
    this.loginDefense = loginDefense;
    this.networkResolver = networkResolver;
    this.sessions = sessions;
    this.accountResolver = accountResolver;
    this.teams = teams;
    this.members = members;
    this.memberRoles = memberRoles;
    this.roles = roles;
    this.registration = registration;
    this.timeProvider = timeProvider;
  }

  /** Authenticates through the fixed defense and rotates into a credential-free Redis Session. */
  @PostMapping("/login")
  public Mono<ResponseEntity<LoginResponse>> login(
      @Valid @RequestBody LoginRequest request, ServerWebExchange exchange) {
    LocalAccountLoginService service = required(logins.getIfAvailable());
    LoginDefense defense = required(loginDefense.getIfAvailable());
    ControlledNetworkSourceResolver networks = required(networkResolver.getIfAvailable());
    BrowserSessionLifecycle lifecycle = required(sessions.getIfAvailable());
    LoginDefenseRequest admission = new LoginDefenseRequest(
        AuthenticationFlow.LOGIN,
        LoginIdentifierResource.fromSubmitted(request.identifier()),
        networks.resolve(exchange));

    return Mono.fromCompletionStage(() -> defense.admit(admission))
        .flatMap(decision -> decision.allowed()
            ? Mono.defer(() -> Mono.fromCompletionStage(service.authenticate(
                    new LocalAccountLoginCommand(request.identifier(), request.password()))))
                .subscribeOn(Schedulers.boundedElastic())
            : Mono.error(rateLimited()))
        .flatMap(account -> lifecycle.establish(exchange, account).thenReturn(account))
        .map(account -> ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(new LoginResponse(true, account.id().value(), account.displayName())));
  }

  /** Invalidates only this browser Session; other users and devices remain isolated. */
  @PostMapping("/logout")
  public Mono<ResponseEntity<Void>> logout(ServerWebExchange exchange) {
    return required(sessions.getIfAvailable())
        .invalidateCurrent(exchange)
        .thenReturn(ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build());
  }

  /** Returns anonymous bootstrap coordinates or a database-revalidated account projection. */
  @GetMapping("/session")
  public Mono<ResponseEntity<SessionResponse>> session(
      ServerWebExchange exchange, Authentication authentication) {
    Mono<CsrfToken> csrf = exchange.getAttribute(CsrfToken.class.getName());
    if (csrf == null) {
      return Mono.error(unavailable());
    }
    return Mono.zip(exchange.getSession(), csrf).flatMap(tuple -> {
      tuple.getT1().start();
      CsrfCoordinates coordinates = new CsrfCoordinates(
          tuple.getT2().getHeaderName(), tuple.getT2().getParameterName(), tuple.getT2().getToken());
      if (authentication == null
          || !authentication.isAuthenticated()
          || !(authentication.getPrincipal() instanceof BrowserSessionPrincipal principal)) {
        return Mono.just(response(anonymous(coordinates)));
      }
      return Mono.fromCallable(() -> authenticated(principal, coordinates))
          .subscribeOn(Schedulers.boundedElastic())
          .flatMap(projected -> projected
              .map(value -> Mono.just(response(value)))
              .orElseGet(() -> required(sessions.getIfAvailable())
                  .invalidateCurrent(exchange)
                  .thenReturn(response(anonymous(coordinates)))));
    });
  }

  private Optional<SessionResponse> authenticated(
      BrowserSessionPrincipal sessionPrincipal, CsrfCoordinates csrf) {
    OrganizationId organizationId = organizationId();
    return accountResolver
        .resolveSession(
            new io.crewscope.domain.identity.UserAccountId(sessionPrincipal.accountId()),
            new io.crewscope.domain.identity.SecurityVersion(sessionPrincipal.securityVersion()),
            organizationId)
        .map(resolution -> authenticatedResponse(resolution, organizationId, csrf));
  }

  private SessionResponse authenticatedResponse(
      AccountOrganizationResolution resolution,
      OrganizationId organizationId,
      CsrfCoordinates csrf) {
    UserAccount account = resolution.account();
    List<TeamSessionView> teamViews = teams
        .findActiveByMember(organizationId, resolution.principal().id())
        .stream()
        .map(team -> teamView(team, resolution))
        .sorted(Comparator.comparing(TeamSessionView::name).thenComparing(TeamSessionView::teamId))
        .toList();
    List<String> permissions = teamViews.stream()
        .flatMap(team -> team.permissions().stream())
        .collect(Collectors.toCollection(TreeSet::new))
        .stream()
        .toList();
    return new SessionResponse(
        true,
        registration.getMode(),
        csrf,
        Optional.of(new AccountSessionView(
            account.id().value(),
            account.username().displayValue(),
            account.displayName(),
            account.platformRole().name(),
            account.securityVersion().value(),
            account.version())),
        Optional.of(new PrincipalSessionView(
            resolution.principal().id().value(), organizationId.value())),
        teamViews,
        permissions);
  }

  private TeamSessionView teamView(Team team, AccountOrganizationResolution resolution) {
    var member = members
        .findByTeamAndUserPrincipalId(
            team.organizationId(), team.id(), resolution.principal().id())
        .filter(candidate -> candidate.canParticipate())
        .orElseThrow(AuthenticationController::unavailable);
    Map<TeamRoleId, TeamRole> currentRoles =
        roles.findByTeam(team.organizationId(), team.id()).stream()
        .filter(TeamRole::isGrantable)
        .collect(Collectors.toMap(TeamRole::id, Function.identity()));
    List<String> permissions = new ArrayList<>();
    var observedAt = timeProvider.now();
    memberRoles.findByMember(team.organizationId(), member.id()).stream()
        .filter(grant -> grant.isEffectiveAt(observedAt))
        .map(grant -> currentRoles.get(grant.teamRoleId()))
        .filter(Objects::nonNull)
        .flatMap(role -> role.permissions().stream())
        .map(TeamPermission::name)
        .distinct()
        .sorted()
        .forEach(permissions::add);
    return new TeamSessionView(
        team.id().value(), team.name(), member.id().value(), List.copyOf(permissions));
  }

  private SessionResponse anonymous(CsrfCoordinates csrf) {
    return new SessionResponse(
        false,
        registration.getMode(),
        csrf,
        Optional.empty(),
        Optional.empty(),
        List.of(),
        List.of());
  }

  private OrganizationId organizationId() {
    String configured = registration.getOrganizationId();
    if (configured == null || configured.isBlank()) {
      throw unavailable();
    }
    try {
      return OrganizationId.from(configured.strip());
    } catch (RuntimeException invalid) {
      throw unavailable();
    }
  }

  private static ResponseEntity<SessionResponse> response(SessionResponse value) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
  }

  private static <T> T required(T value) {
    if (value == null) {
      throw unavailable();
    }
    return value;
  }

  private static ApiRequestException rateLimited() {
    return new ApiRequestException(
        HttpStatus.TOO_MANY_REQUESTS,
        "too_many_requests",
        "Authentication is temporarily unavailable",
        Map.of());
  }

  private static ApiRequestException unavailable() {
    return new ApiRequestException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "authentication_unavailable",
        "Authentication is unavailable",
        Map.of());
  }

  public record LoginRequest(
      @NotBlank @Size(max = 1024) String identifier,
      @NotBlank @Size(max = 512) String password) {

    /** Keeps identity, provider, Session and authorization coordinates server-owned. */
    @JsonAnySetter
    void rejectUnknownProperty(String ignoredName, Object ignoredValue) {
      throw new IllegalArgumentException("Unsupported login property");
    }
  }

  public record LoginResponse(boolean authenticated, UUID accountId, String displayName) {}

  public record CsrfCoordinates(String headerName, String parameterName, String token) {}

  public record AccountSessionView(
      UUID accountId,
      String username,
      String displayName,
      String platformRole,
      long securityVersion,
      long version) {}

  public record PrincipalSessionView(UUID principalId, UUID organizationId) {}

  public record TeamSessionView(
      UUID teamId, String name, UUID memberId, List<String> permissions) {}

  public record SessionResponse(
      boolean authenticated,
      RegistrationMode registrationMode,
      CsrfCoordinates csrf,
      Optional<AccountSessionView> account,
      Optional<PrincipalSessionView> principal,
      List<TeamSessionView> teams,
      List<String> permissions) {}
}

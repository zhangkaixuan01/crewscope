package io.crewscope.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.identity.AccountOrganizationResolution;
import io.crewscope.application.identity.AuthenticatedAccountOrganizationResolver;
import io.crewscope.application.team.CreateTeamCommand;
import io.crewscope.application.team.FirstTeamAlreadyExistsException;
import io.crewscope.application.team.OnboardingApplicationService;
import io.crewscope.application.team.OnboardingState;
import io.crewscope.application.team.OnboardingStatus;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.identity.RegistrationMode;
import io.crewscope.domain.identity.UserAccount;
import io.crewscope.domain.identity.UserAccountId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.server.config.RegistrationProperties;
import io.crewscope.server.security.session.BrowserSessionPrincipal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

/** Current-account-only status, idempotency and stable error HTTP contract for M7-A04. */
class OnboardingControllerM7A04Test {

  private OnboardingApplicationService onboarding;
  private AuthenticatedAccountOrganizationResolver resolver;
  private UserAccount account;
  private WebTestClient client;

  @BeforeEach
  void setUp() {
    onboarding = mock(OnboardingApplicationService.class);
    resolver = mock(AuthenticatedAccountOrganizationResolver.class);
    UtcTimestamp now = UtcTimestamp.parse("2026-08-29T03:00:00Z");
    OrganizationId organizationId = OrganizationId.generate();
    account = UserAccount.register(
        UserAccountId.generate(), "alice", "alice@example.com", "Alice", now);
    Principal principal = Principal.create(
        PrincipalId.generate(),
        PrincipalScope.organization(organizationId),
        PrincipalType.USER,
        Optional.empty(),
        "Alice",
        Optional.empty(),
        PrincipalVisibility.ORGANIZATION,
        now);
    AccountOrganizationResolution resolution = mock(AccountOrganizationResolution.class);
    when(resolution.account()).thenReturn(account);
    when(resolution.principal()).thenReturn(principal);
    when(resolver.resolveSession(any(), any(), any())).thenReturn(Optional.of(resolution));
    RegistrationProperties registration = new RegistrationProperties();
    registration.setMode(RegistrationMode.OPEN);
    registration.setOrganizationId(organizationId.toString());
    OnboardingController controller =
        new OnboardingController(onboarding, resolver, registration);
    client = authenticatedClient(controller, account.id().value(), 1);
  }

  @Test
  void noTeamReturnsTheExplicitRequiredStateWithoutCaching() {
    when(onboarding.status(any())).thenReturn(
        new OnboardingStatus(OnboardingState.TEAM_REQUIRED, 0));

    client.get()
        .uri("/api/v1/onboarding")
        .exchange()
        .expectStatus().isOk()
        .expectHeader().cacheControl(org.springframework.http.CacheControl.noStore())
        .expectBody()
        .jsonPath("$.state").isEqualTo("TEAM_REQUIRED")
        .jsonPath("$.onboardingRequired").isEqualTo(true)
        .jsonPath("$.activeTeamCount").isEqualTo(0);
  }

  @Test
  void existingTeamReturnsCompleteAndSkipsOnboarding() {
    when(onboarding.status(any())).thenReturn(
        new OnboardingStatus(OnboardingState.COMPLETE, 2));

    client.get()
        .uri("/api/v1/onboarding")
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.state").isEqualTo("COMPLETE")
        .jsonPath("$.onboardingRequired").isEqualTo(false)
        .jsonPath("$.activeTeamCount").isEqualTo(2);
  }

  @Test
  void creationRequiresIdempotencyAndReturnsTheStandardReceipt() {
    CommandReceipt receipt = new CommandReceipt(
        UUID.randomUUID(), UUID.randomUUID(), 0, UUID.randomUUID());
    @SuppressWarnings("unchecked")
    CommandExecution<TeamInitialization> execution = CommandExecution.replayed(receipt);
    when(onboarding.createFirstTeam(any(), any(), any())).thenReturn(execution);

    client.post()
        .uri("/api/v1/onboarding/team")
        .header("Idempotency-Key", "first-team-browser-1")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"name\":\"Platform Team\"}")
        .exchange()
        .expectStatus().isAccepted()
        .expectHeader().valueEquals("Idempotency-Replayed", "true")
        .expectHeader().cacheControl(org.springframework.http.CacheControl.noStore())
        .expectBody()
        .jsonPath("$.commandId").isEqualTo(receipt.commandId().toString())
        .jsonPath("$.domainEventId").isEqualTo(receipt.domainEventId().toString());

    ArgumentCaptor<TeamCommandContext> teamContext =
        ArgumentCaptor.forClass(TeamCommandContext.class);
    ArgumentCaptor<CreateTeamCommand> command =
        ArgumentCaptor.forClass(CreateTeamCommand.class);
    verify(onboarding).createFirstTeam(any(), teamContext.capture(), command.capture());
    assertEquals("first-team-browser-1", teamContext.getValue().idempotencyKey().value());
    assertEquals("Platform Team", command.getValue().name());

    client.post()
        .uri("/api/v1/onboarding/team")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"name\":\"Missing Key\"}")
        .exchange()
        .expectStatus().isBadRequest()
        .expectBody()
        .jsonPath("$.code").isEqualTo("invalid_request");
  }

  @Test
  void anotherCreationKeyAfterCompletionReturnsAStableConflict() {
    when(onboarding.createFirstTeam(any(), any(), any()))
        .thenThrow(new FirstTeamAlreadyExistsException());

    client.post()
        .uri("/api/v1/onboarding/team")
        .header("Idempotency-Key", "second-team-browser-1")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"name\":\"Unexpected Team\"}")
        .exchange()
        .expectStatus().isEqualTo(409)
        .expectBody()
        .jsonPath("$.code").isEqualTo("onboarding_already_complete")
        .jsonPath("$.details").isEmpty();
  }

  @Test
  void staleOrRevokedSessionCannotReadOnboarding() {
    when(resolver.resolveSession(any(), any(), any())).thenReturn(Optional.empty());

    client.get()
        .uri("/api/v1/onboarding")
        .exchange()
        .expectStatus().isUnauthorized()
        .expectBody()
        .jsonPath("$.code").isEqualTo("authentication_required");
  }

  private static WebTestClient authenticatedClient(
      OnboardingController controller, UUID accountId, long securityVersion) {
    var authentication = UsernamePasswordAuthenticationToken.authenticated(
        new BrowserSessionPrincipal(accountId, securityVersion),
        null,
        List.of(new SimpleGrantedAuthority("ROLE_USER")));
    WebFilter principal = (exchange, chain) ->
        chain.filter(exchange.mutate().principal(Mono.just(authentication)).build());
    return WebTestClient.bindToController(controller)
        .controllerAdvice(new ApiExceptionHandler())
        .webFilter(principal)
        .build();
  }
}

package io.crewscope.server.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.identity.AuthenticatedAccountOrganizationResolver;
import io.crewscope.application.identity.AccountOrganizationResolution;
import io.crewscope.application.identity.LocalAccountLoginException;
import io.crewscope.application.identity.LocalAccountLoginService;
import io.crewscope.application.identity.LoginDefense;
import io.crewscope.application.identity.LoginResourceAdmission;
import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.TeamMemberRepository;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.domain.identity.RegistrationMode;
import io.crewscope.domain.identity.PlatformRole;
import io.crewscope.domain.identity.SecurityVersion;
import io.crewscope.domain.identity.UserAccount;
import io.crewscope.domain.identity.UserAccountId;
import io.crewscope.domain.identity.Username;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.MemberRole;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.team.TeamPermission;
import io.crewscope.domain.team.TeamRole;
import io.crewscope.domain.team.TeamRoleId;
import io.crewscope.server.config.RegistrationProperties;
import io.crewscope.server.security.login.ControlledNetworkSourceResolver;
import io.crewscope.server.security.session.BrowserSessionLifecycle;
import io.crewscope.server.security.session.BrowserSessionPrincipal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

/** Public JSON, anonymous Session and fixed login-error contract for M7-A02. */
class AuthenticationControllerM7A02Test {

  private LocalAccountLoginService logins;
  private LoginDefense defense;
  private BrowserSessionLifecycle sessions;
  private AuthenticatedAccountOrganizationResolver accountResolver;
  private TeamRepository teams;
  private TeamMemberRepository members;
  private MemberRoleRepository memberRoles;
  private TeamRoleRepository roles;
  private TimeProvider timeProvider;
  private OrganizationId organizationId;
  private AuthenticationController controller;
  private WebTestClient client;

  @BeforeEach
  void setUp() {
    logins = mock(LocalAccountLoginService.class);
    defense = mock(LoginDefense.class);
    sessions = mock(BrowserSessionLifecycle.class);
    ControlledNetworkSourceResolver networks = mock(ControlledNetworkSourceResolver.class);
    when(networks.resolve(any())).thenReturn(
        io.crewscope.application.identity.ControlledNetworkResource.ofCanonical(
            "ipv4:7f000000/24"));
    RegistrationProperties registration = new RegistrationProperties();
    registration.setMode(RegistrationMode.INVITE_ONLY);
    organizationId = OrganizationId.generate();
    registration.setOrganizationId(organizationId.toString());
    teams = mock(TeamRepository.class);
    members = mock(TeamMemberRepository.class);
    memberRoles = mock(MemberRoleRepository.class);
    roles = mock(TeamRoleRepository.class);
    timeProvider = mock(TimeProvider.class);
    accountResolver = mock(AuthenticatedAccountOrganizationResolver.class);
    controller = new AuthenticationController(
        provider(logins),
        provider(defense),
        provider(networks),
        provider(sessions),
        accountResolver,
        teams,
        members,
        memberRoles,
        roles,
        registration,
        timeProvider);
    client = WebTestClient.bindToController(controller)
        .controllerAdvice(new ApiExceptionHandler())
        .webFilter(csrf())
        .build();
  }

  @Test
  void anonymousSessionExposesOnlyRegistrationAndCsrfCoordinates() {
    client.get()
        .uri("/api/v1/auth/session")
        .exchange()
        .expectStatus().isOk()
        .expectHeader().cacheControl(org.springframework.http.CacheControl.noStore())
        .expectBody()
        .jsonPath("$.authenticated").isEqualTo(false)
        .jsonPath("$.registrationMode").isEqualTo("INVITE_ONLY")
        .jsonPath("$.csrf.headerName").isEqualTo("X-XSRF-TOKEN")
        .jsonPath("$.csrf.token").isEqualTo("csrf-token")
        .jsonPath("$.account").doesNotExist()
        .jsonPath("$.teams").isArray();
  }

  @Test
  void invalidCredentialsUseOneNonEnumeratingFailure() {
    when(defense.admit(any())).thenReturn(
        CompletableFuture.completedFuture(LoginResourceAdmission.ALLOWED));
    when(logins.authenticate(any())).thenReturn(
        CompletableFuture.failedFuture(new LocalAccountLoginException()));

    client.post()
        .uri("/api/v1/auth/login")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("""
            {"identifier":"alice@example.com","password":"wrong-password"}
            """)
        .exchange()
        .expectStatus().isUnauthorized()
        .expectHeader().doesNotExist("WWW-Authenticate")
        .expectBody()
        .jsonPath("$.code").isEqualTo("invalid_credentials")
        .jsonPath("$.details").isEmpty();

    verify(sessions, never()).establish(any(), any());
  }

  @Test
  void resourceLimitPreventsPasswordWork() {
    when(defense.admit(any())).thenReturn(CompletableFuture.completedFuture(
        LoginResourceAdmission.NETWORK_RATE_LIMITED));

    client.post()
        .uri("/api/v1/auth/login")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("""
            {"identifier":"alice@example.com","password":"wrong-password"}
            """)
        .exchange()
        .expectStatus().isEqualTo(429)
        .expectBody()
        .jsonPath("$.code").isEqualTo("too_many_requests");

    verify(logins, never()).authenticate(any());
  }

  @Test
  void staleSessionIsInvalidatedWhenCurrentAccountFactsNoLongerResolve() {
    UUID accountId = UUID.randomUUID();
    when(accountResolver.resolveSession(any(), any(), any())).thenReturn(Optional.empty());
    when(sessions.invalidateCurrent(any())).thenReturn(Mono.empty());
    var authentication = UsernamePasswordAuthenticationToken.authenticated(
        new BrowserSessionPrincipal(accountId, 3),
        null,
        List.of(new SimpleGrantedAuthority("ROLE_USER")));
    WebFilter principal = (exchange, chain) ->
        chain.filter(exchange.mutate().principal(Mono.just(authentication)).build());
    WebTestClient authenticatedClient = WebTestClient.bindToController(controller)
        .controllerAdvice(new ApiExceptionHandler())
        .webFilter(csrf())
        .webFilter(principal)
        .build();

    authenticatedClient.get()
        .uri("/api/v1/auth/session")
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.authenticated").isEqualTo(false)
        .jsonPath("$.account").doesNotExist();

    verify(sessions).invalidateCurrent(any());
  }

  @Test
  void authenticatedSessionProjectsCurrentTeamRolePermissions() {
    UserAccountId accountId = UserAccountId.generate();
    UserAccount account = mock(UserAccount.class);
    when(account.id()).thenReturn(accountId);
    when(account.username()).thenReturn(new Username("alice"));
    when(account.displayName()).thenReturn("Alice");
    when(account.platformRole()).thenReturn(PlatformRole.USER);
    when(account.securityVersion()).thenReturn(new SecurityVersion(3));
    when(account.version()).thenReturn(4L);
    var principal = mock(io.crewscope.domain.identity.Principal.class);
    PrincipalId principalId = PrincipalId.generate();
    when(principal.id()).thenReturn(principalId);
    AccountOrganizationResolution resolution = mock(AccountOrganizationResolution.class);
    when(resolution.account()).thenReturn(account);
    when(resolution.principal()).thenReturn(principal);
    when(accountResolver.resolveSession(any(), any(), any()))
        .thenReturn(Optional.of(resolution));

    Team team = mock(Team.class);
    var teamId = io.crewscope.domain.shared.id.TeamId.generate();
    when(team.id()).thenReturn(teamId);
    when(team.organizationId()).thenReturn(organizationId);
    when(team.name()).thenReturn("Platform");
    when(teams.findActiveByMember(organizationId, principalId)).thenReturn(List.of(team));
    TeamMember member = mock(TeamMember.class);
    TeamMemberId memberId = TeamMemberId.generate();
    when(member.id()).thenReturn(memberId);
    when(member.canParticipate()).thenReturn(true);
    when(members.findByTeamAndUserPrincipalId(organizationId, teamId, principalId))
        .thenReturn(Optional.of(member));
    TeamRoleId roleId = TeamRoleId.generate();
    TeamRole role = mock(TeamRole.class);
    when(role.id()).thenReturn(roleId);
    when(role.isGrantable()).thenReturn(true);
    when(role.permissions()).thenReturn(java.util.Set.of(TeamPermission.WORK_CREATE));
    when(roles.findByTeam(organizationId, teamId)).thenReturn(List.of(role));
    MemberRole grant = mock(MemberRole.class);
    when(grant.teamRoleId()).thenReturn(roleId);
    when(grant.isEffectiveAt(any())).thenReturn(true);
    when(memberRoles.findByMember(organizationId, memberId)).thenReturn(List.of(grant));
    when(timeProvider.now()).thenReturn(UtcTimestamp.parse("2026-08-29T00:00:00Z"));

    WebTestClient authenticatedClient = authenticatedClient(accountId.value(), 3);
    authenticatedClient.get()
        .uri("/api/v1/auth/session")
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.authenticated").isEqualTo(true)
        .jsonPath("$.account.username").isEqualTo("alice")
        .jsonPath("$.principal.organizationId").isEqualTo(organizationId.toString())
        .jsonPath("$.teams[0].name").isEqualTo("Platform")
        .jsonPath("$.teams[0].permissions[0]").isEqualTo("WORK_CREATE")
        .jsonPath("$.permissions[0]").isEqualTo("WORK_CREATE")
        .jsonPath("$.password").doesNotExist()
        .jsonPath("$.sessionId").doesNotExist();
  }

  private WebTestClient authenticatedClient(UUID accountId, long securityVersion) {
    var authentication = UsernamePasswordAuthenticationToken.authenticated(
        new BrowserSessionPrincipal(accountId, securityVersion),
        null,
        List.of(new SimpleGrantedAuthority("ROLE_USER")));
    WebFilter principal = (exchange, chain) ->
        chain.filter(exchange.mutate().principal(Mono.just(authentication)).build());
    return WebTestClient.bindToController(controller)
        .controllerAdvice(new ApiExceptionHandler())
        .webFilter(csrf())
        .webFilter(principal)
        .build();
  }

  private static WebFilter csrf() {
    CsrfToken token = mock(CsrfToken.class);
    when(token.getHeaderName()).thenReturn("X-XSRF-TOKEN");
    when(token.getParameterName()).thenReturn("_csrf");
    when(token.getToken()).thenReturn("csrf-token");
    return (exchange, chain) -> {
      exchange.getAttributes().put(CsrfToken.class.getName(), Mono.just(token));
      return chain.filter(exchange);
    };
  }

  @SuppressWarnings("unchecked")
  private static <T> ObjectProvider<T> provider(T value) {
    ObjectProvider<T> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(value);
    return provider;
  }
}

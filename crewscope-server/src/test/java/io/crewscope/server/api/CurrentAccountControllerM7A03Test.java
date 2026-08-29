package io.crewscope.server.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.identity.AccountOrganizationResolution;
import io.crewscope.application.identity.AccountProfileUpdateCommand;
import io.crewscope.application.identity.AuthenticatedAccountOrganizationResolver;
import io.crewscope.application.identity.CurrentAccountApplicationService;
import io.crewscope.application.identity.CurrentAccountMutationException;
import io.crewscope.application.identity.CurrentAccountMutationFailure;
import io.crewscope.application.identity.CurrentAccountMutationResult;
import io.crewscope.domain.identity.AccountIdentifierConflictException;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.RegistrationMode;
import io.crewscope.domain.identity.UserAccount;
import io.crewscope.domain.identity.UserAccountId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.server.config.RegistrationProperties;
import io.crewscope.server.security.session.BrowserSessionLifecycle;
import io.crewscope.server.security.session.BrowserSessionPrincipal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

/** Strong ETag, safe error and Session revocation HTTP contract for M7-A03. */
class CurrentAccountControllerM7A03Test {

  private CurrentAccountApplicationService accounts;
  private AuthenticatedAccountOrganizationResolver resolver;
  private BrowserSessionLifecycle sessions;
  private UserAccount account;
  private WebTestClient client;

  @BeforeEach
  void setUp() {
    accounts = mock(CurrentAccountApplicationService.class);
    resolver = mock(AuthenticatedAccountOrganizationResolver.class);
    sessions = mock(BrowserSessionLifecycle.class);
    account = UserAccount.register(
        UserAccountId.generate(),
        "alice",
        "alice@example.com",
        "Alice",
        UtcTimestamp.parse("2026-08-29T00:00:00Z"));
    OrganizationId organizationId = OrganizationId.generate();
    RegistrationProperties registration = new RegistrationProperties();
    registration.setMode(RegistrationMode.OPEN);
    registration.setOrganizationId(organizationId.toString());
    Principal principal = mock(Principal.class);
    when(principal.id()).thenReturn(PrincipalId.generate());
    AccountOrganizationResolution resolution = mock(AccountOrganizationResolution.class);
    when(resolution.account()).thenReturn(account);
    when(resolution.principal()).thenReturn(principal);
    when(resolver.resolveSession(any(), any(), any())).thenReturn(Optional.of(resolution));
    when(accounts.current(any())).thenReturn(account);
    CurrentAccountController controller = new CurrentAccountController(
        accounts, resolver, provider(sessions), registration);
    client = authenticatedClient(controller, account.id().value(), 1);
  }

  @Test
  void getReturnsEditableProfileStrongEtagAndNoCredentialFields() {
    client.get()
        .uri("/api/v1/account")
        .exchange()
        .expectStatus().isOk()
        .expectHeader().valueEquals("ETag", "\"0\"")
        .expectHeader().cacheControl(org.springframework.http.CacheControl.noStore())
        .expectBody()
        .jsonPath("$.username").isEqualTo("alice")
        .jsonPath("$.email").isEqualTo("alice@example.com")
        .jsonPath("$.securityVersion").isEqualTo(1)
        .jsonPath("$.password").doesNotExist()
        .jsonPath("$.credential").doesNotExist()
        .jsonPath("$.sessionId").doesNotExist();
  }

  @Test
  void patchRequiresOneStrongIfMatchAndPreservesOptionalStepUpCoordinates() {
    UserAccount changed = account.changeDisplayName(
        "Alice Zhang", UtcTimestamp.parse("2026-08-29T01:00:00Z"));
    when(accounts.updateProfile(any(), any())).thenReturn(
        CompletableFuture.completedFuture(
            new CurrentAccountMutationResult(changed, UUID.randomUUID())));

    client.patch()
        .uri("/api/v1/account")
        .header("If-Match", "\"0\"")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("""
            {"displayName":"Alice Zhang"}
            """)
        .exchange()
        .expectStatus().isOk()
        .expectHeader().valueEquals("ETag", "\"1\"")
        .expectBody()
        .jsonPath("$.displayName").isEqualTo("Alice Zhang");

    ArgumentCaptor<AccountProfileUpdateCommand> command =
        ArgumentCaptor.forClass(AccountProfileUpdateCommand.class);
    verify(accounts).updateProfile(any(), command.capture());
    assertFalse(command.getValue().revealCurrentPassword().isPresent());
    assertFalse(command.getValue().expectedSecurityVersion().isPresent());

    client.patch()
        .uri("/api/v1/account")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"displayName\":\"Another\"}")
        .exchange()
        .expectStatus().isEqualTo(428)
        .expectBody()
        .jsonPath("$.code").isEqualTo("precondition_required");
  }

  @Test
  void wrongCurrentPasswordUsesTheSameNonEnumeratingCredentialError() {
    when(accounts.updateProfile(any(), any())).thenReturn(CompletableFuture.failedFuture(
        new CurrentAccountMutationException(
            CurrentAccountMutationFailure.INVALID_CURRENT_PASSWORD)));

    client.patch()
        .uri("/api/v1/account")
        .header("If-Match", "\"0\"")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("""
            {
              "username":"alice.dev",
              "currentPassword":"wrong-current",
              "securityVersion":1
            }
            """)
        .exchange()
        .expectStatus().isUnauthorized()
        .expectBody()
        .jsonPath("$.code").isEqualTo("invalid_credentials")
        .jsonPath("$.details").isEmpty();
  }

  @Test
  void usernameAndEmailUniquenessUseOneFieldlessConflict() {
    when(accounts.updateProfile(any(), any())).thenReturn(
        CompletableFuture.failedFuture(new AccountIdentifierConflictException()));

    client.patch()
        .uri("/api/v1/account")
        .header("If-Match", "\"0\"")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("""
            {
              "email":"occupied@example.com",
              "currentPassword":"correct-current",
              "securityVersion":1
            }
            """)
        .exchange()
        .expectStatus().isEqualTo(409)
        .expectBody()
        .jsonPath("$.code").isEqualTo("account_identifier_conflict")
        .jsonPath("$.details").isEmpty()
        .jsonPath("$.email").doesNotExist()
        .jsonPath("$.username").doesNotExist();
  }

  @Test
  void passwordChangeReturnsNewEtagOnlyAfterAllSessionsAreDeleted() {
    UserAccount changed = account.advanceSecurityVersion(
        UtcTimestamp.parse("2026-08-29T01:00:00Z"));
    when(accounts.changePassword(any(), any())).thenReturn(
        CompletableFuture.completedFuture(
            new CurrentAccountMutationResult(changed, UUID.randomUUID())));
    when(sessions.invalidateAll(account.id().value())).thenReturn(Mono.empty());

    client.post()
        .uri("/api/v1/account/password")
        .header("If-Match", "\"0\"")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("""
            {
              "currentPassword":"correct-current",
              "newPassword":"new-password-value",
              "securityVersion":1
            }
            """)
        .exchange()
        .expectStatus().isNoContent()
        .expectHeader().valueEquals("ETag", "\"1\"")
        .expectHeader().cacheControl(org.springframework.http.CacheControl.noStore());

    verify(sessions).invalidateAll(account.id().value());
  }

  @Test
  void allSessionRevocationCarriesTheCurrentPasswordAndSecurityVersion() {
    UserAccount changed = account.advanceSecurityVersion(
        UtcTimestamp.parse("2026-08-29T01:00:00Z"));
    when(accounts.revokeAllSessions(any(), any())).thenReturn(
        CompletableFuture.completedFuture(
            new CurrentAccountMutationResult(changed, UUID.randomUUID())));
    when(sessions.invalidateAll(account.id().value())).thenReturn(Mono.empty());

    client.post()
        .uri("/api/v1/account/sessions/revoke")
        .header("If-Match", "\"0\"")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("""
            {"currentPassword":"correct-current","securityVersion":1}
            """)
        .exchange()
        .expectStatus().isNoContent();

    verify(accounts).revokeAllSessions(any(), any());
    verify(sessions).invalidateAll(account.id().value());
    assertTrue(changed.securityVersion().value() > account.securityVersion().value());
  }

  private static WebTestClient authenticatedClient(
      CurrentAccountController controller, UUID accountId, long securityVersion) {
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

  @SuppressWarnings("unchecked")
  private static <T> ObjectProvider<T> provider(T value) {
    ObjectProvider<T> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(value);
    return provider;
  }
}

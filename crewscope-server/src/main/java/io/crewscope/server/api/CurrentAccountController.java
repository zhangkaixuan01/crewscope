package io.crewscope.server.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.crewscope.application.identity.AccountOrganizationResolution;
import io.crewscope.application.identity.AccountPasswordChangeCommand;
import io.crewscope.application.identity.AccountProfileUpdateCommand;
import io.crewscope.application.identity.AccountSessionRevocationCommand;
import io.crewscope.application.identity.AuthenticatedAccountOrganizationResolver;
import io.crewscope.application.identity.CurrentAccountApplicationService;
import io.crewscope.application.identity.CurrentAccountCommandContext;
import io.crewscope.application.identity.CurrentAccountMutationResult;
import io.crewscope.domain.identity.SecurityVersion;
import io.crewscope.domain.identity.UserAccount;
import io.crewscope.domain.identity.UserAccountId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.server.config.RegistrationProperties;
import io.crewscope.server.security.session.BrowserSessionLifecycle;
import io.crewscope.server.security.session.BrowserSessionPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Self-service profile, password and device-session API for the authenticated Account only. */
@RestController
@RequestMapping("/api/v1/account")
public class CurrentAccountController {

  private final CurrentAccountApplicationService accounts;
  private final AuthenticatedAccountOrganizationResolver accountResolver;
  private final ObjectProvider<BrowserSessionLifecycle> sessions;
  private final RegistrationProperties registration;

  public CurrentAccountController(
      CurrentAccountApplicationService accounts,
      AuthenticatedAccountOrganizationResolver accountResolver,
      ObjectProvider<BrowserSessionLifecycle> sessions,
      RegistrationProperties registration) {
    this.accounts = accounts;
    this.accountResolver = accountResolver;
    this.sessions = sessions;
    this.registration = registration;
  }

  /** Returns the complete editable profile with a strong aggregate-version ETag. */
  @GetMapping
  public Mono<ResponseEntity<AccountResponse>> current(
      Authentication authentication, ServerWebExchange exchange) {
    return Mono.fromCallable(() -> {
          ResolvedCurrentAccount resolved = resolve(authentication, exchange);
          return accountResponse(accounts.current(resolved.context()));
        })
        .subscribeOn(Schedulers.boundedElastic());
  }

  /** Changes one or more profile fields; identifier changes require password step-up. */
  @PatchMapping
  public Mono<ResponseEntity<AccountResponse>> update(
      @Valid @RequestBody ProfileUpdateRequest request,
      @RequestHeader(value = ApiHeaders.IF_MATCH, required = false) List<String> ifMatch,
      Authentication authentication,
      ServerWebExchange exchange) {
    long expectedVersion = ApiHeaders.requireSingleIfMatch(ifMatch);
    return resolved(authentication, exchange)
        .flatMap(value -> Mono.fromCompletionStage(accounts.updateProfile(
            value.context(), request.toCommand(expectedVersion))))
        .map(result -> accountResponse(result.account()));
  }

  /** Rotates the password, advances SecurityVersion and deletes all browser Sessions. */
  @PostMapping("/password")
  public Mono<ResponseEntity<Void>> changePassword(
      @Valid @RequestBody PasswordChangeRequest request,
      @RequestHeader(value = ApiHeaders.IF_MATCH, required = false) List<String> ifMatch,
      Authentication authentication,
      ServerWebExchange exchange) {
    long expectedVersion = ApiHeaders.requireSingleIfMatch(ifMatch);
    return resolved(authentication, exchange)
        .flatMap(value -> Mono.fromCompletionStage(accounts.changePassword(
            value.context(), request.toCommand(expectedVersion))))
        .flatMap(this::invalidateAllAndRespond);
  }

  /** Advances SecurityVersion and deletes every indexed Session owned by this Account. */
  @PostMapping("/sessions/revoke")
  public Mono<ResponseEntity<Void>> revokeSessions(
      @Valid @RequestBody SessionRevocationRequest request,
      @RequestHeader(value = ApiHeaders.IF_MATCH, required = false) List<String> ifMatch,
      Authentication authentication,
      ServerWebExchange exchange) {
    long expectedVersion = ApiHeaders.requireSingleIfMatch(ifMatch);
    return resolved(authentication, exchange)
        .flatMap(value -> Mono.fromCompletionStage(accounts.revokeAllSessions(
            value.context(), request.toCommand(expectedVersion))))
        .flatMap(this::invalidateAllAndRespond);
  }

  private Mono<ResolvedCurrentAccount> resolved(
      Authentication authentication, ServerWebExchange exchange) {
    return Mono.fromCallable(() -> resolve(authentication, exchange))
        .subscribeOn(Schedulers.boundedElastic());
  }

  private ResolvedCurrentAccount resolve(
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
        .orElseThrow(CurrentAccountController::unauthenticated);
    return new ResolvedCurrentAccount(
        resolution.account(),
        new CurrentAccountCommandContext(
            resolution.account().id(),
            organizationId,
            resolution.principal().id(),
            ApiCorrelationIds.resolve(exchange),
            Optional.empty()));
  }

  private Mono<ResponseEntity<Void>> invalidateAllAndRespond(
      CurrentAccountMutationResult result) {
    BrowserSessionLifecycle lifecycle = sessions.getIfAvailable();
    if (lifecycle == null) {
      return Mono.error(unavailable());
    }
    return lifecycle.invalidateAll(result.account().id().value())
        .onErrorMap(ignored -> unavailable())
        .thenReturn(ResponseEntity.noContent()
            .cacheControl(CacheControl.noStore())
            .header(ApiHeaders.ETAG, ApiHeaders.versionEtag(result.account().version()))
            .build());
  }

  private ResponseEntity<AccountResponse> accountResponse(UserAccount account) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .eTag(ApiHeaders.versionEtag(account.version()))
        .body(AccountResponse.from(account));
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
        "account_service_unavailable",
        "The account service is unavailable",
        Map.of());
  }

  public record ProfileUpdateRequest(
      Optional<@Size(min = 3, max = 64) String> username,
      Optional<@Size(max = 254) String> email,
      Optional<@Size(max = 200) String> displayName,
      Optional<@Size(max = 512) String> currentPassword,
      @Positive Long securityVersion) {

    public ProfileUpdateRequest {
      username = username == null ? Optional.empty() : username;
      email = email == null ? Optional.empty() : email;
      displayName = displayName == null ? Optional.empty() : displayName;
      currentPassword = currentPassword == null ? Optional.empty() : currentPassword;
    }

    AccountProfileUpdateCommand toCommand(long expectedVersion) {
      return new AccountProfileUpdateCommand(
          username,
          email,
          displayName,
          currentPassword,
          securityVersion == null ? OptionalLong.empty() : OptionalLong.of(securityVersion),
          expectedVersion);
    }

    /** Rejects account status, role, ownership and persistence coordinates from the client. */
    @JsonAnySetter
    void rejectUnknownProperty(String ignoredName, Object ignoredValue) {
      throw new IllegalArgumentException("Unsupported profile property");
    }
  }

  public record PasswordChangeRequest(
      @NotBlank @Size(max = 512) String currentPassword,
      @NotBlank @Size(max = 512) String newPassword,
      @Positive long securityVersion) {

    AccountPasswordChangeCommand toCommand(long expectedVersion) {
      return new AccountPasswordChangeCommand(
          currentPassword, newPassword, securityVersion, expectedVersion);
    }

    /** Rejects credential metadata and server-owned password-policy controls. */
    @JsonAnySetter
    void rejectUnknownProperty(String ignoredName, Object ignoredValue) {
      throw new IllegalArgumentException("Unsupported password-change property");
    }
  }

  public record SessionRevocationRequest(
      @NotBlank @Size(max = 512) String currentPassword,
      @Positive long securityVersion) {

    AccountSessionRevocationCommand toCommand(long expectedVersion) {
      return new AccountSessionRevocationCommand(
          currentPassword, securityVersion, expectedVersion);
    }

    /** Rejects Session identifiers so this command can only revoke the current account scope. */
    @JsonAnySetter
    void rejectUnknownProperty(String ignoredName, Object ignoredValue) {
      throw new IllegalArgumentException("Unsupported session-revocation property");
    }
  }

  public record AccountResponse(
      UUID accountId,
      String username,
      String email,
      String displayName,
      String status,
      String platformRole,
      long securityVersion,
      long version,
      Instant createdAt,
      Instant updatedAt) {

    static AccountResponse from(UserAccount account) {
      return new AccountResponse(
          account.id().value(),
          account.username().displayValue(),
          account.email(),
          account.displayName(),
          account.status().name(),
          account.platformRole().name(),
          account.securityVersion().value(),
          account.version(),
          account.lifecycle().createdAt().value(),
          account.lifecycle().updatedAt().value());
    }
  }

  private record ResolvedCurrentAccount(
      UserAccount account, CurrentAccountCommandContext context) {}
}

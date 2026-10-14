package io.crewscope.server.api;

import io.crewscope.server.security.session.BrowserSessionLifecycle;
import io.crewscope.server.security.session.BrowserSessionPrincipal;
import io.crewscope.server.security.session.BrowserSessionSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Self-service enumeration and per-device revocation of the caller's own browser Sessions
 * (M12-I01). Enumeration is always account-scoped inside the lifecycle, so another account's
 * Session identifier is indistinguishable from a missing one.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthenticationSessionController {

  private final ObjectProvider<BrowserSessionLifecycle> sessions;

  public AuthenticationSessionController(ObjectProvider<BrowserSessionLifecycle> sessions) {
    this.sessions = Objects.requireNonNull(sessions, "sessions");
  }

  /** Lists the caller's Sessions, most recently accessed first. */
  @GetMapping("/sessions")
  public Mono<ResponseEntity<SessionsResponse>> list(
      Authentication authentication, ServerWebExchange exchange) {
    BrowserSessionPrincipal principal = requirePrincipal(authentication);
    BrowserSessionLifecycle lifecycle = required();
    return exchange
        .getSession()
        .flatMapMany(
            current ->
                lifecycle
                    .listSessions(principal.accountId())
                    .map(snapshot -> view(snapshot, current.getId())))
        .collectList()
        .map(
            views ->
                ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(new SessionsResponse(views)));
  }

  /** Deletes one of the caller's Sessions; deleting the current one is a logout equivalent. */
  @DeleteMapping("/sessions/{sessionId}")
  public Mono<ResponseEntity<Void>> delete(
      @PathVariable String sessionId,
      Authentication authentication,
      ServerWebExchange exchange) {
    BrowserSessionPrincipal principal = requirePrincipal(authentication);
    BrowserSessionLifecycle lifecycle = required();
    return lifecycle
        .invalidateSession(exchange, principal.accountId(), sessionId)
        .flatMap(
            removed ->
                removed
                    ? Mono.just(
                        ResponseEntity.noContent()
                            .cacheControl(CacheControl.noStore())
                            .<Void>build())
                    : Mono.error(notFound()));
  }

  private static SessionView view(BrowserSessionSnapshot snapshot, String currentSessionId) {
    return new SessionView(
        snapshot.sessionId(),
        snapshot.createdAt(),
        snapshot.lastAccessedAt(),
        snapshot.sessionId().equals(currentSessionId));
  }

  private BrowserSessionLifecycle required() {
    BrowserSessionLifecycle lifecycle = sessions.getIfAvailable();
    if (lifecycle == null) {
      throw unavailable();
    }
    return lifecycle;
  }

  private static BrowserSessionPrincipal requirePrincipal(Authentication authentication) {
    if (authentication == null
        || !authentication.isAuthenticated()
        || !(authentication.getPrincipal() instanceof BrowserSessionPrincipal principal)) {
      throw unauthenticated();
    }
    return principal;
  }

  private static ApiRequestException unauthenticated() {
    return new ApiRequestException(
        HttpStatus.UNAUTHORIZED,
        "authentication_required",
        "Authentication is required",
        Map.of());
  }

  private static ApiRequestException notFound() {
    return new ApiRequestException(
        HttpStatus.NOT_FOUND, "session_not_found", "No such session", Map.of());
  }

  private static ApiRequestException unavailable() {
    return new ApiRequestException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "authentication_unavailable",
        "Authentication is unavailable",
        Map.of());
  }

  /** Owner-visible projection of one browser Session; no principal or account data leaks. */
  public record SessionView(
      String sessionId, Instant createdAt, Instant lastAccessedAt, boolean current) {}

  public record SessionsResponse(List<SessionView> sessions) {}
}

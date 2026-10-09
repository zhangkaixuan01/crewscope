package io.crewscope.server.collaboration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.crewscope.domain.collaboration.CollaborationResourceScope;
import io.crewscope.domain.collaboration.CollaborationResourceType;
import io.crewscope.domain.collaboration.ResourceScope;
import io.crewscope.domain.collaboration.TeamScope;
import io.crewscope.domain.collaboration.WorkProjectScope;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.server.collaboration.CollaborationConnectionMetrics.DenialReason;
import io.crewscope.server.collaboration.CollaborationConnectionMetrics.RejectionReason;
import io.crewscope.server.collaboration.CollaborationConnectionRegistry.Admission;
import io.crewscope.server.collaboration.CollaborationConnectionRegistry.Reason;
import io.crewscope.server.collaboration.CollaborationSubscriptionAuthorizer.Decision;
import io.crewscope.server.collaboration.CollaborationSubscriptionRegistry.Added;
import io.crewscope.server.collaboration.CollaborationSubscriptionRegistry.AlreadySubscribed;
import io.crewscope.server.collaboration.CollaborationSubscriptionRegistry.AtLimit;
import io.crewscope.server.security.AccountSessionSubject;
import io.crewscope.server.security.AuthenticationSubjectExtractor;
import io.crewscope.server.security.AuthenticatedSubject;
import io.crewscope.server.security.ExternalAuthenticatedSubject;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.server.context.WebSessionServerSecurityContextRepository;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Connection-level half of the collaboration transport (M11-I01a/I01b). The filter chain has
 * already authenticated the upgrade request and the handshake service copied exactly the
 * security-context attribute into {@link WebSocketSession#getAttributes()} — this handler reuses
 * that session identity (no second authentication) and enforces the ADR-032 connection
 * constants: 15s server ping, 30s inbound-idle disconnect, per-principal and node admission
 * limits, and a per-connection outbound budget that closes a slow client (1013) instead of
 * growing node memory.
 *
 * <p>I01b adds the subscription protocol on top: {@code subscribe}/{@code unsubscribe} frames
 * carrying a scope, per-subscription authorization through {@link
 * CollaborationSubscriptionAuthorizer} (every subscribe re-intersects the identity with durable
 * authorization), the per-connection subscription cap, and the Redis presence writes — conn hash
 * with TTL plus scope ZSET entries — refreshed by the heartbeat and torn down on disconnect.
 * Presence stays fire-and-forget: losing it degrades presentation, never business (the I01b
 * acceptance line). Authorization frames never disclose whether a resource exists;
 * forbidden, missing, and infrastructure failures all answer {@code forbidden_scope}.</p>
 *
 * <p>Protocol until A01 formalizes the contract: text frames carrying {@code {"type":...}} JSON.
 * The server sends {@code welcome} then periodic {@code ping}; the client answers {@code pong}.
 * Unknown or unparseable frames get an {@code error} reply without disconnecting — the frame-size
 * cap and the idle timeout bound abuse on their own.</p>
 */
public final class CollaborationConnectionHandler implements WebSocketHandler {

  static final CloseStatus CLOSE_PRINCIPAL_LIMIT = new CloseStatus(4000, "principal limit");
  static final CloseStatus CLOSE_NODE_LIMIT = new CloseStatus(1013, "node limit");
  static final CloseStatus CLOSE_SLOW = new CloseStatus(1013, "outbound budget exceeded");
  static final CloseStatus CLOSE_IDLE = new CloseStatus(1000, "inbound idle");
  static final CloseStatus CLOSE_PROTOCOL = new CloseStatus(1008, "no authenticated principal");

  private static final Logger log = LoggerFactory.getLogger(CollaborationConnectionHandler.class);
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final TypeReference<Map<String, Object>> FRAME_TYPE = new TypeReference<>() {};

  private static final String PING_FRAME = "{\"type\":\"ping\"}";
  private static final Duration IDLE_CHECK_INTERVAL = Duration.ofSeconds(1);

  private final CollaborationConnectionRegistry registry;
  private final CollaborationConnectionMetrics metrics;
  private final AuthenticationSubjectExtractor subjectExtractor;
  private final CollaborationSubscriptionRegistry subscriptions;
  private final CollaborationSubscriptionAuthorizer subscriptionAuthorizer;
  private final CollaborationPresenceKeyspace presenceKeyspace;
  private final CollaborationPresenceStore presence;
  private final Duration heartbeatInterval;
  private final Duration inboundIdleTimeout;
  private final Duration presenceTtl;
  private final int outboundFrameBufferLimit;

  public CollaborationConnectionHandler(
      CollaborationConnectionRegistry registry,
      CollaborationConnectionMetrics metrics,
      AuthenticationSubjectExtractor subjectExtractor,
      CollaborationSubscriptionRegistry subscriptions,
      CollaborationSubscriptionAuthorizer subscriptionAuthorizer,
      CollaborationPresenceKeyspace presenceKeyspace,
      CollaborationPresenceStore presence,
      Duration heartbeatInterval,
      Duration inboundIdleTimeout,
      Duration presenceTtl,
      int outboundFrameBufferLimit) {
    this.registry = registry;
    this.metrics = metrics;
    this.subjectExtractor = subjectExtractor;
    this.subscriptions = subscriptions;
    this.subscriptionAuthorizer = subscriptionAuthorizer;
    this.presenceKeyspace = presenceKeyspace;
    this.presence = presence;
    this.heartbeatInterval = heartbeatInterval;
    this.inboundIdleTimeout = inboundIdleTimeout;
    this.presenceTtl = presenceTtl;
    this.outboundFrameBufferLimit = outboundFrameBufferLimit;
  }

  @Override
  public Mono<Void> handle(WebSocketSession session) {
    Authentication authentication = resolveAuthentication(session);
    String principalKey = authentication == null ? null : principalKeyOf(authentication);
    if (principalKey == null) {
      metrics.recordRejection(RejectionReason.PROTOCOL);
      return session.close(CLOSE_PROTOCOL);
    }
    Admission admission = registry.admit(session.getId(), principalKey);
    if (admission instanceof Admission.Rejected rejected) {
      return reject(session, rejected.reason());
    }
    if (admission instanceof Admission.Accepted accepted && accepted.overSoftBudget()) {
      log.warn(
          "collaboration connections over soft budget: {} live", registry.totalConnections());
    }

    CollaborationOutboundChannel outbound =
        new CollaborationOutboundChannel(outboundFrameBufferLimit);
    AtomicReference<Instant> lastInbound = new AtomicReference<>(Instant.now());
    offer(session, outbound, welcomeFrame(session.getId()));

    Mono<Void> inbound =
        session
            .receive()
            .map(message -> message.getPayloadAsText())
            .concatMap(
                text ->
                    Mono.defer(
                        () -> {
                          // Any inbound frame refreshes activity, before protocol handling:
                          // the idle disconnect must never fire on a chatty client, and an
                          // authorization wait must not count as idleness.
                          lastInbound.set(Instant.now());
                          return acceptInboundFrame(session, outbound, authentication, principalKey, text);
                        }))
            .doOnComplete(outbound::close)
            .then();
    Mono<Void> outboundSend = session.send(outbound.stream().map(session::textMessage));
    Mono<Void> heartbeat = heartbeatLoop(session, outbound, lastInbound);

    return Mono.zip(inbound, outboundSend, heartbeat)
        .then()
        .doFinally(signal -> {
          outbound.close();
          registry.release(session.getId());
          releasePresence(session.getId());
        });
  }

  /** Independent ticks: ping at the heartbeat interval, idle checks at 1s resolution. */
  private Mono<Void> heartbeatLoop(
      WebSocketSession session, CollaborationOutboundChannel outbound,
      AtomicReference<Instant> lastInbound) {
    return Flux.merge(
            Flux.interval(heartbeatInterval).map(tick -> true),
            Flux.interval(IDLE_CHECK_INTERVAL).map(tick -> false))
        .onBackpressureDrop()
        .concatMap(isPing ->
            Mono.fromRunnable(() -> {
              if (isPing) {
                offer(session, outbound, PING_FRAME);
                refreshPresence(session.getId());
                return;
              }
              if (Duration.between(lastInbound.get(), Instant.now()).compareTo(inboundIdleTimeout)
                  > 0) {
                session.close(CLOSE_IDLE).subscribe();
              }
            }))
        .then();
  }

  /** Pong keeps the connection alive; subscribe/unsubscribe are the I01b protocol verbs. */
  private Mono<Void> acceptInboundFrame(
      WebSocketSession session,
      CollaborationOutboundChannel outbound,
      Authentication authentication,
      String principalKey,
      String text) {
    Map<String, Object> frame;
    try {
      frame = JSON.readValue(text, FRAME_TYPE);
    } catch (Exception exception) {
      offer(session, outbound, "{\"type\":\"error\",\"code\":\"invalid_json\"}");
      return Mono.empty();
    }
    String type = String.valueOf(frame.get("type"));
    if ("pong".equals(type)) {
      return Mono.empty();
    }
    if ("subscribe".equals(type)) {
      return handleSubscribe(session, outbound, authentication, principalKey, frame);
    }
    if ("unsubscribe".equals(type)) {
      handleUnsubscribe(session, outbound, frame);
      return Mono.empty();
    }
    offer(session, outbound, errorFrame("unsupported-frame", type));
    return Mono.empty();
  }

  private Mono<Void> handleSubscribe(
      WebSocketSession session,
      CollaborationOutboundChannel outbound,
      Authentication authentication,
      String principalKey,
      Map<String, Object> frame) {
    Object scopeEcho = frame.get("scope");
    CollaborationResourceScope scope = parseScope(scopeEcho);
    if (scope == null) {
      metrics.recordSubscriptionDenied(DenialReason.INVALID);
      offer(session, outbound, scopeErrorFrame("invalid_scope", scopeEcho));
      return Mono.empty();
    }
    return subscriptionAuthorizer
        .authorize(authentication, scope)
        .flatMap(decision -> {
          if (decision == Decision.DENIED) {
            // The I01b acceptance line: denials are recorded (never with a resource title —
            // the scope echo below is the client's own request bounced back).
            metrics.recordSubscriptionDenied(DenialReason.FORBIDDEN);
            log.warn(
                "collaboration subscription denied for principal {} to scope {}",
                principalKey,
                presenceKeyspace.scopeKey(scope));
            offer(session, outbound, scopeErrorFrame("forbidden_scope", scopeEcho));
            return Mono.empty();
          }
          return acknowledgeSubscription(session, outbound, principalKey, scope, scopeEcho);
        });
  }

  private Mono<Void> acknowledgeSubscription(
      WebSocketSession session,
      CollaborationOutboundChannel outbound,
      String principalKey,
      CollaborationResourceScope scope,
      Object scopeEcho) {
    var addition = subscriptions.add(session.getId(), presenceKeyspace.scopeKey(scope));
    if (addition instanceof AtLimit limit) {
      metrics.recordSubscriptionDenied(DenialReason.LIMIT);
      offer(session, outbound, scopeErrorFrame("subscription_limit", scopeEcho));
      return Mono.empty();
    }
    String subscriptionId =
        addition instanceof Added fresh
            ? fresh.subscriptionId()
            : ((AlreadySubscribed) addition).subscriptionId();
    if (addition instanceof Added) {
      // Presence is lossy by contract: a failed Redis write still acks the subscription —
      // losing presence degrades presentation, never business.
      return presence
          .register(session.getId(), principalKey, scope, Instant.now())
          .onErrorResume(
              failure -> {
                log.warn(
                    "collaboration presence register failed for connection {}; "
                        + "subscription stands without it",
                    session.getId(),
                    failure);
                return Mono.empty();
              })
          .doOnSuccess(
              ignored -> offer(session, outbound, subscribedFrame(subscriptionId, scopeEcho)));
    }
    // Idempotent re-subscribe: the original handle stays, presence keeps riding the heartbeat.
    offer(session, outbound, subscribedFrame(subscriptionId, scopeEcho));
    return Mono.empty();
  }

  private void handleUnsubscribe(
      WebSocketSession session, CollaborationOutboundChannel outbound, Map<String, Object> frame) {
    String subscriptionId = String.valueOf(frame.get("subscriptionId"));
    Optional<String> scopeKey = subscriptions.remove(session.getId(), subscriptionId);
    scopeKey.ifPresent(key -> presence.removeScope(session.getId(), key).subscribe());
    // Idempotent: an unknown handle still acks, so a lost ack never wedges the client.
    offer(session, outbound, unsubscribedFrame(subscriptionId));
  }

  /**
   * Parses the frame scope into the domain value. Team granularity omits resourceType and
   * resourceId; every granularity carries explicit organization and team coordinates (the
   * product ports are all org-scoped — a deliberate deviation from the single-org probe).
   * Anything malformed is invalid_scope, not an error: clients echo garbage, servers answer.
   */
  private static CollaborationResourceScope parseScope(Object raw) {
    if (!(raw instanceof Map<?, ?> scope)) {
      return null;
    }
    try {
      OrganizationId organizationId =
          new OrganizationId(UUID.fromString(String.valueOf(scope.get("organization"))));
      TeamId teamId = new TeamId(UUID.fromString(String.valueOf(scope.get("team"))));
      Object resourceType = scope.get("resourceType");
      if (resourceType == null || "team".equals(resourceType)) {
        // Team granularity carries no resource coordinates; sending any is a client bug.
        return scope.get("resourceId") == null ? new TeamScope(organizationId, teamId) : null;
      }
      Object resourceId = scope.get("resourceId");
      if (resourceId == null) {
        return null;
      }
      UUID resource = UUID.fromString(String.valueOf(resourceId));
      return switch (String.valueOf(resourceType)) {
        case "work_project" ->
            new WorkProjectScope(organizationId, teamId, new WorkProjectId(resource));
        case "work_item" ->
            new ResourceScope(organizationId, teamId, CollaborationResourceType.WORK_ITEM, resource);
        case "conversation" ->
            new ResourceScope(
                organizationId, teamId, CollaborationResourceType.CONVERSATION, resource);
        default -> null;
      };
    } catch (IllegalArgumentException invalid) {
      return null;
    }
  }

  /** Heartbeat tick: slide the conn TTL and every active scope score forward, fire-and-forget. */
  private void refreshPresence(String connectionId) {
    Set<String> scopeKeys = subscriptions.scopeKeysOf(connectionId);
    if (scopeKeys.isEmpty()) {
      return;
    }
    presence
        .refresh(connectionId, scopeKeys)
        .onErrorResume(
            failure -> {
              log.debug("collaboration presence refresh failed for {}", connectionId, failure);
              return Mono.empty();
            })
        .subscribe();
  }

  /** Disconnect cleanup: drop the topology and the presence entries, fire-and-forget. */
  private void releasePresence(String connectionId) {
    Set<String> scopeKeys = subscriptions.removeAll(connectionId);
    if (scopeKeys.isEmpty()) {
      return;
    }
    presence
        .removeConnection(connectionId, scopeKeys)
        .onErrorResume(
            failure -> {
              log.debug("collaboration presence cleanup failed for {}", connectionId, failure);
              return Mono.empty();
            })
        .subscribe();
  }

  private void offer(WebSocketSession session, CollaborationOutboundChannel outbound, String frame) {
    CollaborationOutboundChannel.Delivery delivery = outbound.offer(frame);
    if (delivery == CollaborationOutboundChannel.Delivery.OVER_BUDGET) {
      metrics.recordClosedSlow();
      session.close(CLOSE_SLOW).subscribe();
    }
  }

  private Mono<Void> reject(WebSocketSession session, Reason reason) {
    return switch (reason) {
      case PRINCIPAL_LIMIT -> {
        metrics.recordRejection(RejectionReason.PRINCIPAL_LIMIT);
        yield session.close(CLOSE_PRINCIPAL_LIMIT);
      }
      case NODE_LIMIT -> {
        metrics.recordRejection(RejectionReason.HARD_LIMIT);
        yield session.close(CLOSE_NODE_LIMIT);
      }
      case DUPLICATE_CONNECTION_ID -> {
        metrics.recordRejection(RejectionReason.PROTOCOL);
        yield session.close(CLOSE_PROTOCOL);
      }
    };
  }

  /**
   * The principal is whatever the session's security context holds — extracted with the same
   * sealed-subject rules as every HTTP endpoint. Returns null when the session is not one this
   * channel admits; the caller closes with the protocol status.
   */
  private Authentication resolveAuthentication(WebSocketSession session) {
    Object stored =
        session
            .getAttributes()
            .get(WebSessionServerSecurityContextRepository.DEFAULT_SPRING_SECURITY_CONTEXT_ATTR_NAME);
    if (!(stored instanceof SecurityContext securityContext)) {
      return null;
    }
    Authentication authentication = securityContext.getAuthentication();
    return authentication != null && authentication.isAuthenticated() ? authentication : null;
  }

  /** Null when the subject is not one this channel admits (or extraction itself fails). */
  private String principalKeyOf(Authentication authentication) {
    try {
      AuthenticatedSubject subject = subjectExtractor.extract(authentication);
      if (subject instanceof AccountSessionSubject account) {
        return "account:" + account.accountId().value();
      }
      if (subject instanceof ExternalAuthenticatedSubject external) {
        return "external:" + external.externalIdentity().provider()
            + ":" + external.externalIdentity().subject();
      }
      return null;
    } catch (RuntimeException exception) {
      return null;
    }
  }

  private String welcomeFrame(String connectionId) {
    Map<String, Object> frame = new LinkedHashMap<>();
    frame.put("type", "welcome");
    frame.put("connectionId", connectionId);
    frame.put("heartbeatIntervalSeconds", heartbeatInterval.toSeconds());
    frame.put("inboundIdleTimeoutSeconds", inboundIdleTimeout.toSeconds());
    frame.put("presenceTtlSeconds", presenceTtl.toSeconds());
    return writeFrame(frame);
  }

  private static String subscribedFrame(String subscriptionId, Object scopeEcho) {
    Map<String, Object> frame = new LinkedHashMap<>();
    frame.put("type", "subscribed");
    frame.put("subscriptionId", subscriptionId);
    frame.put("scope", scopeEcho);
    return writeFrame(frame);
  }

  private static String unsubscribedFrame(String subscriptionId) {
    Map<String, Object> frame = new LinkedHashMap<>();
    frame.put("type", "unsubscribed");
    frame.put("subscriptionId", subscriptionId);
    return writeFrame(frame);
  }

  private static String scopeErrorFrame(String code, Object scopeEcho) {
    Map<String, Object> frame = new LinkedHashMap<>();
    frame.put("type", "error");
    frame.put("code", code);
    frame.put("scope", scopeEcho);
    return writeFrame(frame);
  }

  private static String errorFrame(String code, String type) {
    Map<String, Object> frame = new LinkedHashMap<>();
    frame.put("type", "error");
    frame.put("code", code);
    frame.put("frameType", type);
    return writeFrame(frame);
  }

  private static String writeFrame(Map<String, Object> frame) {
    try {
      return JSON.writeValueAsString(frame);
    } catch (Exception exception) {
      // Jackson only fails here on map types it always supports; a plain map never triggers it.
      throw new IllegalStateException("failed to serialize collaboration frame", exception);
    }
  }
}

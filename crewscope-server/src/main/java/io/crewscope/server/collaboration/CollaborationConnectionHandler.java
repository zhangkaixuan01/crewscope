package io.crewscope.server.collaboration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.crewscope.server.collaboration.CollaborationConnectionMetrics.RejectionReason;
import io.crewscope.server.collaboration.CollaborationConnectionRegistry.Admission;
import io.crewscope.server.collaboration.CollaborationConnectionRegistry.Reason;
import io.crewscope.server.security.AccountSessionSubject;
import io.crewscope.server.security.AuthenticationSubjectExtractor;
import io.crewscope.server.security.AuthenticatedSubject;
import io.crewscope.server.security.ExternalAuthenticatedSubject;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
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
 * Connection-level half of the collaboration transport (M11-I01a). The filter chain has already
 * authenticated the upgrade request and the handshake service copied exactly the security-context
 * attribute into {@link WebSocketSession#getAttributes()} — this handler reuses that session
 * identity (no second authentication) and enforces the ADR-032 connection constants: 15s server
 * ping, 30s inbound-idle disconnect, per-principal and node admission limits, and a per-connection
 * outbound budget that closes a slow client (1013) instead of growing node memory.
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
  private final Duration heartbeatInterval;
  private final Duration inboundIdleTimeout;
  private final int outboundFrameBufferLimit;

  public CollaborationConnectionHandler(
      CollaborationConnectionRegistry registry,
      CollaborationConnectionMetrics metrics,
      AuthenticationSubjectExtractor subjectExtractor,
      Duration heartbeatInterval,
      Duration inboundIdleTimeout,
      int outboundFrameBufferLimit) {
    this.registry = registry;
    this.metrics = metrics;
    this.subjectExtractor = subjectExtractor;
    this.heartbeatInterval = heartbeatInterval;
    this.inboundIdleTimeout = inboundIdleTimeout;
    this.outboundFrameBufferLimit = outboundFrameBufferLimit;
  }

  @Override
  public Mono<Void> handle(WebSocketSession session) {
    String principalKey = resolvePrincipalKey(session);
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
                    Mono.fromRunnable(
                        () -> {
                          // Any inbound frame refreshes activity, before protocol handling:
                          // the idle disconnect must never fire on a chatty client.
                          lastInbound.set(Instant.now());
                          acceptInboundFrame(session, outbound, text);
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
                return;
              }
              if (Duration.between(lastInbound.get(), Instant.now()).compareTo(inboundIdleTimeout)
                  > 0) {
                session.close(CLOSE_IDLE).subscribe();
              }
            }))
        .then();
  }

  /** Any inbound frame refreshes activity; only pong is otherwise a no-op. */
  private void acceptInboundFrame(
      WebSocketSession session, CollaborationOutboundChannel outbound, String text) {
    Map<String, Object> frame;
    try {
      frame = JSON.readValue(text, FRAME_TYPE);
    } catch (Exception exception) {
      offer(session, outbound, "{\"type\":\"error\",\"code\":\"invalid_json\"}");
      return;
    }
    if ("pong".equals(frame.get("type"))) {
      return;
    }
    offer(session, outbound, errorFrame("unsupported-frame", String.valueOf(frame.get("type"))));
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
  private String resolvePrincipalKey(WebSocketSession session) {
    Object stored =
        session
            .getAttributes()
            .get(WebSessionServerSecurityContextRepository.DEFAULT_SPRING_SECURITY_CONTEXT_ATTR_NAME);
    if (!(stored instanceof SecurityContext securityContext)) {
      return null;
    }
    Authentication authentication = securityContext.getAuthentication();
    if (authentication == null || !authentication.isAuthenticated()) {
      return null;
    }
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

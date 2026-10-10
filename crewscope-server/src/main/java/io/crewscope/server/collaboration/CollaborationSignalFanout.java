package io.crewscope.server.collaboration;

import io.crewscope.application.collaboration.CollaborationSignalSink;
import io.crewscope.domain.collaboration.CollaborationResourceChanged;
import io.crewscope.domain.collaboration.CollaborationResourceScope;
import io.crewscope.domain.collaboration.TeamScope;
import io.crewscope.server.collaboration.CollaborationConnectionMetrics.SignalFrameType;
import io.crewscope.server.collaboration.CollaborationOutboundChannel.Delivery;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.web.reactive.socket.CloseStatus;
import reactor.core.publisher.Mono;

/**
 * The A01 signal fanout (M11-A01, ADR-032): the in-memory half of {@link
 * CollaborationSignalSink}. The outbox consumer pushes coordinate-plus-version facts
 * here; this class resolves the audience scopes through the subscription registry and
 * emits one frame per receiving subscription, labeled with the receiver's own handle.
 *
 * <p>Every emission path runs <em>deliver first, revalidate after</em>: frames carry
 * coordinates and versions only, so an out-of-order delivery is harmless, while the
 * revocation revalidator's probe runs asynchronously and cuts a denied connection with
 * 4403 (plus immediate topology, presence and fanout cleanup — the handler's teardown
 * re-runs the same idempotent steps). A receiver whose outbound channel is over budget
 * is closed with 1013, exactly like a slow heartbeat client; one lost receiver never
 * slows the others.</p>
 *
 * <p>Typing is stateless forwarding with an <em>anchored</em> (not sliding) window per
 * subscription scope: a {@code started} forwards only when no anchor exists or the
 * window has fully elapsed since the last forwarded one, duplicates inside the window
 * are dropped without moving the anchor — a continuously typing user keeps producing
 * one forwarded frame per window instead of starving the indicator or flooding it.</p>
 */
public final class CollaborationSignalFanout implements CollaborationSignalSink {

  static final CloseStatus CLOSE_REVOKED = new CloseStatus(4403, "authorization revoked");
  static final CloseStatus CLOSE_SIGNAL_SLOW = new CloseStatus(1013, "signal budget exceeded");

  private static final Logger log = LoggerFactory.getLogger(CollaborationSignalFanout.class);

  /** One live connection as the fanout sees it: identity, labels and the emission paths. */
  private record RegisteredConnection(
      String connectionId,
      Authentication authentication,
      String principalKey,
      String displayName,
      CollaborationOutboundChannel channel,
      CollaborationConnectionControl control,
      AtomicBoolean revoked,
      ConcurrentHashMap<String, Instant> typingAnchors) {}

  private final CollaborationSubscriptionRegistry subscriptions;
  private final CollaborationPresenceKeyspace keyspace;
  private final CollaborationPresenceStore presence;
  private final CollaborationConnectionMetrics metrics;
  private final CollaborationRevocationRevalidator revalidator;
  private final Duration typingWindow;
  private final Clock clock;
  private final Map<String, RegisteredConnection> connections = new ConcurrentHashMap<>();

  public CollaborationSignalFanout(
      CollaborationSubscriptionRegistry subscriptions,
      CollaborationPresenceKeyspace keyspace,
      CollaborationPresenceStore presence,
      CollaborationConnectionMetrics metrics,
      CollaborationRevocationRevalidator revalidator,
      Duration typingWindow,
      Clock clock) {
    this.subscriptions = Objects.requireNonNull(subscriptions, "subscriptions");
    this.keyspace = Objects.requireNonNull(keyspace, "keyspace");
    this.presence = Objects.requireNonNull(presence, "presence");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.revalidator = Objects.requireNonNull(revalidator, "revalidator");
    this.typingWindow = Objects.requireNonNull(typingWindow, "typingWindow");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Registers a live connection; every subsequent emission may reach it. */
  public void register(
      String connectionId,
      Authentication authentication,
      String principalKey,
      String displayName,
      CollaborationOutboundChannel channel,
      CollaborationConnectionControl control) {
    connections.put(
        connectionId,
        new RegisteredConnection(
            connectionId,
            authentication,
            principalKey,
            displayName,
            channel,
            control,
            new AtomicBoolean(),
            new ConcurrentHashMap<>()));
  }

  /**
   * Disconnect teardown: drops the fanout entry first, then announces the leave on every
   * scope the connection held (the registry removal happened before this call, so the
   * remaining-connection count is already correct).
   */
  public void disconnected(String connectionId, Set<String> scopeKeys) {
    RegisteredConnection subject = connections.remove(connectionId);
    if (subject == null) {
      return;
    }
    for (String scopeKey : scopeKeys) {
      announceLeave(subject, scopeKey);
    }
  }

  /** Unsubscribe teardown: the connection stays live, only this scope's leave is announced. */
  public void unsubscribed(String connectionId, String scopeKey) {
    RegisteredConnection subject = connections.get(connectionId);
    if (subject != null) {
      announceLeave(subject, scopeKey);
    }
  }

  /**
   * Subscription completion: announces the enter to everyone else in the scope, and — for
   * the resource and project granularities only — hands the new subscriber a presence
   * snapshot. A Team-granularity snapshot could hold 300 principals and exceed the frame
   * budget, so those subscribers see deltas only. The snapshot read is lossy: a Redis
   * failure skips it, never the subscription.
   */
  public Mono<Void> subscribed(String connectionId, String scopeKey) {
    RegisteredConnection subject = connections.get(connectionId);
    if (subject == null) {
      return Mono.empty();
    }
    CollaborationResourceScope scope = keyspace.parseScopeKey(scopeKey).orElse(null);
    if (scope == null) {
      return Mono.empty();
    }
    announceEnter(scope, scopeKey, subject);
    if (scope instanceof TeamScope) {
      return Mono.empty();
    }
    String handle = scopeKeyHandle(connectionId, scopeKey);
    if (handle == null) {
      return Mono.empty();
    }
    return presence
        .listPresent(scope)
        .doOnNext(present -> deliver(subject, scope,
            CollaborationSignalFrames.presenceSnapshot(handle, scope, present),
            SignalFrameType.PRESENCE_SNAPSHOT))
        .onErrorResume(
            failure -> {
              log.debug("collaboration presence snapshot skipped for {}: {}",
                  connectionId, failure.toString());
              return Mono.empty();
            })
        .then();
  }

  /**
   * Typing forwarding with the anchored window: {@code stopped} always forwards and
   * clears the anchor; {@code started} forwards only outside the anchored window. The
   * sender never receives its own frame back.
   */
  public void typing(String connectionId, String scopeKey, boolean started) {
    try {
      RegisteredConnection sender = connections.get(connectionId);
      if (sender == null) {
        return;
      }
      CollaborationResourceScope scope = keyspace.parseScopeKey(scopeKey).orElse(null);
      if (scope == null) {
        return;
      }
      if (!started) {
        sender.typingAnchors().remove(scopeKey);
        broadcastTyping(scope, scopeKey, sender, "stopped");
        return;
      }
      Instant now = clock.instant();
      Instant anchor = sender.typingAnchors().get(scopeKey);
      if (anchor != null && Duration.between(anchor, now).compareTo(typingWindow) < 0) {
        return;
      }
      sender.typingAnchors().put(scopeKey, now);
      broadcastTyping(scope, scopeKey, sender, "started");
    } catch (RuntimeException dropped) {
      log.warn("collaboration fanout dropped a typing signal: {}", dropped.toString());
    }
  }

  /** Heartbeat tick: refreshes the revalidation cache for every scope the connection holds. */
  public void heartbeatTouch(String connectionId) {
    RegisteredConnection connection = connections.get(connectionId);
    if (connection == null) {
      return;
    }
    for (String scopeKey : subscriptions.scopeKeysOf(connectionId)) {
      keyspace.parseScopeKey(scopeKey)
          .ifPresent(scope -> probe(connection, scope));
    }
  }

  /**
   * {@link CollaborationSignalSink}: fans one change fact out to every audience scope. The
   * frame names the changed resource — never the receiver's subscription granularity: a
   * Team- or project-granularity subscriber must still learn which concrete resource moved
   * and at what version, or the contract's client dedup rule (max version per resourceType,
   * resourceId) could not apply on those granularities.
   */
  @Override
  public void resourceChanged(CollaborationResourceChanged change) {
    try {
      Objects.requireNonNull(change, "change");
      CollaborationResourceScope resource = change.resource();
      for (CollaborationResourceScope audienceScope : change.audience()) {
        String scopeKey = keyspace.scopeKey(audienceScope);
        for (String connectionId : subscriptions.connectionsFor(scopeKey)) {
          RegisteredConnection receiver = connections.get(connectionId);
          String handle = scopeKeyHandle(connectionId, scopeKey);
          if (receiver == null || handle == null) {
            continue;
          }
          deliver(receiver, resource,
              CollaborationSignalFrames.resourceChanged(handle, resource, change.version()),
              SignalFrameType.RESOURCE_CHANGED);
        }
      }
    } catch (RuntimeException dropped) {
      // The sink contract: never throw back into the receipt transaction.
      log.warn("collaboration fanout dropped a resource change: {}", dropped.toString());
    }
  }

  // ------------------------------------------------------------------ internals

  private void announceEnter(
      CollaborationResourceScope scope, String scopeKey, RegisteredConnection subject) {
    broadcast(scope, scopeKey, subject.connectionId(), SignalFrameType.PRESENCE_DELTA,
        handle -> CollaborationSignalFrames.presenceDelta(
            handle, scope, "enter", subject.principalKey(), subject.displayName(),
            countPrincipalConnections(scopeKey, subject.principalKey())));
  }

  private void announceLeave(RegisteredConnection subject, String scopeKey) {
    CollaborationResourceScope scope = keyspace.parseScopeKey(scopeKey).orElse(null);
    if (scope == null) {
      return;
    }
    broadcast(scope, scopeKey, null, SignalFrameType.PRESENCE_DELTA,
        handle -> CollaborationSignalFrames.presenceDelta(
            handle, scope, "leave", subject.principalKey(), subject.displayName(),
            countPrincipalConnections(scopeKey, subject.principalKey())));
  }

  private void broadcastTyping(
      CollaborationResourceScope scope, String scopeKey, RegisteredConnection sender, String state) {
    broadcast(scope, scopeKey, sender.connectionId(), SignalFrameType.TYPING,
        handle -> CollaborationSignalFrames.typing(
            handle, scope, sender.principalKey(), sender.displayName(), state));
  }

  /**
   * Emits to every other subscriber of the scope; the receiver's own handle labels the
   * frame, so a client always knows which of its subscriptions produced the signal.
   */
  private void broadcast(
      CollaborationResourceScope scope,
      String scopeKey,
      String excludeConnectionId,
      SignalFrameType frameType,
      UnaryOperator<String> frameForHandle) {
    for (String connectionId : subscriptions.connectionsFor(scopeKey)) {
      if (connectionId.equals(excludeConnectionId)) {
        continue;
      }
      RegisteredConnection receiver = connections.get(connectionId);
      String handle = scopeKeyHandle(connectionId, scopeKey);
      if (receiver == null || handle == null) {
        continue;
      }
      deliver(receiver, scope, frameForHandle.apply(handle), frameType);
    }
  }

  /**
   * Deliver-then-revalidate: the frame goes out first (coordinates and versions only),
   * then a fresh-negative cache cuts the connection, a stale cache schedules one
   * asynchronous probe, and a fresh-positive verdict needs nothing.
   */
  private void deliver(
      RegisteredConnection receiver,
      CollaborationResourceScope scope,
      String frame,
      SignalFrameType frameType) {
    Optional<Boolean> verdict =
        revalidator.cached(receiver.principalKey(), scope.organizationId(), scope.teamId());
    if (verdict.isPresent() && !verdict.get()) {
      // The probe already denied this principal; a revoke raced with this emission.
      revoke(receiver);
      return;
    }
    Delivery delivery = receiver.channel().offer(frame);
    if (delivery == Delivery.OVER_BUDGET) {
      metrics.recordClosedSlow();
      receiver.control().close(CLOSE_SIGNAL_SLOW);
      return;
    }
    if (delivery == Delivery.CLOSED) {
      // The channel is already tearing down; probing it now is wasted work.
      return;
    }
    if (delivery == Delivery.EMITTED) {
      metrics.recordSignalEmitted(frameType);
    }
    if (verdict.isEmpty()) {
      probe(receiver, scope);
    }
  }

  private void probe(RegisteredConnection connection, CollaborationResourceScope scope) {
    revalidator
        .revalidate(
            connection.principalKey(),
            connection.authentication(),
            scope.organizationId(),
            scope.teamId())
        .subscribe(member -> {
          if (!member) {
            revoke(connection);
          }
        });
  }

  private void revoke(RegisteredConnection connection) {
    if (!connection.revoked().compareAndSet(false, true)) {
      return;
    }
    metrics.recordClosedRevoked();
    connection.control().close(CLOSE_REVOKED);
    // Immediate cleanup instead of waiting for the handler teardown, which re-runs the
    // same idempotent steps (removeAll and the Redis removals are all idempotent).
    Set<String> scopeKeys = subscriptions.removeAll(connection.connectionId());
    disconnected(connection.connectionId(), scopeKeys);
    presence
        .removeConnection(connection.connectionId(), scopeKeys)
        .onErrorResume(
            failure -> {
              log.debug("collaboration revocation presence cleanup failed for {}: {}",
                  connection.connectionId(), failure.toString());
              return Mono.empty();
            })
        .subscribe();
  }

  /** The receiver's subscription handle on this scope, or null when it does not hold one. */
  private String scopeKeyHandle(String connectionId, String scopeKey) {
    return subscriptions.subscriptionId(connectionId, scopeKey).orElse(null);
  }

  /** Live connections of one principal on one scope — the presence_delta connection count. */
  private int countPrincipalConnections(String scopeKey, String principalKey) {
    int count = 0;
    for (String connectionId : subscriptions.connectionsFor(scopeKey)) {
      RegisteredConnection connection = connections.get(connectionId);
      if (connection != null && connection.principalKey().equals(principalKey)) {
        count++;
      }
    }
    return count;
  }
}

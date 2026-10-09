package io.crewscope.server.collaboration;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory subscription topology (M11-I01b): the per-connection subscription set with the
 * per-connection cap frozen by ADR-032, plus the global scope-to-connections index that the
 * A01 fanout and the D02 revocation sweep will consume. Pure bookkeeping — the authorization
 * decision happens before {@link #add}, and the Redis presence writes happen after it, keyed
 * by the returned subscription state.
 *
 * <p>Keys are the rendered scope strings from {@link CollaborationPresenceKeyspace}; the
 * registry never needs to interpret them, only to compare and index them.</p>
 */
public final class CollaborationSubscriptionRegistry {

  /** Outcome of trying to register one subscription on one connection. */
  public sealed interface Addition permits AlreadySubscribed, Added, AtLimit {}

  /** The scope was already subscribed; the original handle is returned for idempotent acks. */
  record AlreadySubscribed(String subscriptionId) implements Addition {}

  /** A new subscription was registered under a fresh handle. */
  record Added(String subscriptionId) implements Addition {}

  /** The connection already holds the configured maximum number of subscriptions. */
  record AtLimit(int limit) implements Addition {}

  private final int maxSubscriptionsPerConnection;
  private final ConcurrentMap<String, Map<String, String>> subscriptionsByConnection =
      new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Set<String>> connectionsByScope = new ConcurrentHashMap<>();

  public CollaborationSubscriptionRegistry(int maxSubscriptionsPerConnection) {
    if (maxSubscriptionsPerConnection < 1 || maxSubscriptionsPerConnection > 256) {
      throw new IllegalArgumentException(
          "maxSubscriptionsPerConnection must be between 1 and 256");
    }
    this.maxSubscriptionsPerConnection = maxSubscriptionsPerConnection;
  }

  /**
   * Registers the scope atomically: either the existing handle (idempotent re-subscribe), a
   * fresh handle, or the limit verdict. The limit counts distinct scopes, so re-subscribing
   * an existing scope never trips it.
   */
  public synchronized Addition add(String connectionId, String scopeKey) {
    Map<String, String> subscriptions =
        subscriptionsByConnection.computeIfAbsent(connectionId, id -> new HashMap<>());
    String existing = subscriptions.get(scopeKey);
    if (existing != null) {
      return new AlreadySubscribed(existing);
    }
    if (subscriptions.size() >= maxSubscriptionsPerConnection) {
      return new AtLimit(maxSubscriptionsPerConnection);
    }
    String subscriptionId = UUID.randomUUID().toString();
    subscriptions.put(scopeKey, subscriptionId);
    connectionsByScope
        .computeIfAbsent(scopeKey, key -> ConcurrentHashMap.newKeySet())
        .add(connectionId);
    return new Added(subscriptionId);
  }

  /** Returns the handle for an active subscription, if the connection holds one. */
  public Optional<String> subscriptionId(String connectionId, String scopeKey) {
    Map<String, String> subscriptions = subscriptionsByConnection.get(connectionId);
    return subscriptions == null ? Optional.empty() : Optional.ofNullable(subscriptions.get(scopeKey));
  }

  /**
   * Removes one subscription by handle. Returns the scope key it occupied so the caller can
   * drop the matching Redis presence entry; unknown handles leave everything untouched.
   */
  public synchronized Optional<String> remove(String connectionId, String subscriptionId) {
    Map<String, String> subscriptions = subscriptionsByConnection.get(connectionId);
    if (subscriptions == null) {
      return Optional.empty();
    }
    String scopeKey = null;
    for (Map.Entry<String, String> entry : subscriptions.entrySet()) {
      if (entry.getValue().equals(subscriptionId)) {
        scopeKey = entry.getKey();
        break;
      }
    }
    if (scopeKey == null) {
      return Optional.empty();
    }
    subscriptions.remove(scopeKey);
    if (subscriptions.isEmpty()) {
      subscriptionsByConnection.remove(connectionId, subscriptions);
    }
    removeConnectionFromScope(connectionId, scopeKey);
    return Optional.of(scopeKey);
  }

  /**
   * Drops every subscription of a disconnected connection and returns the scope keys, so the
   * caller can clear the Redis presence entries in one pass.
   */
  public synchronized Set<String> removeAll(String connectionId) {
    Map<String, String> subscriptions = subscriptionsByConnection.remove(connectionId);
    if (subscriptions == null || subscriptions.isEmpty()) {
      return Set.of();
    }
    Set<String> scopeKeys = new HashSet<>(subscriptions.keySet());
    for (String scopeKey : scopeKeys) {
      removeConnectionFromScope(connectionId, scopeKey);
    }
    return Collections.unmodifiableSet(scopeKeys);
  }

  /** Connections currently holding a subscription to the scope — the fanout set. */
  public Set<String> connectionsFor(String scopeKey) {
    Set<String> connections = connectionsByScope.get(scopeKey);
    return connections == null ? Set.of() : Set.copyOf(connections);
  }

  /** Total distinct subscriptions across all connections, for metrics and tests. */
  public int totalSubscriptions() {
    return subscriptionsByConnection.values().stream().mapToInt(Map::size).sum();
  }

  private void removeConnectionFromScope(String connectionId, String scopeKey) {
    Set<String> connections = connectionsByScope.get(scopeKey);
    if (connections == null) {
      return;
    }
    connections.remove(connectionId);
    if (connections.isEmpty()) {
      connectionsByScope.remove(scopeKey, connections);
    }
  }
}

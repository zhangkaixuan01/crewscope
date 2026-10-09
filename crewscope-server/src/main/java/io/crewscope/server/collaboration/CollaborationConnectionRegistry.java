package io.crewscope.server.collaboration;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-process registry of live collaboration connections with the ADR-032 admission limits:
 * per-principal connection cap, node-level soft budget (observability only) and hard limit
 * (admission). Single-instance by design; the multi-instance control channel is an explicit
 * ADR-032 reevaluation condition, not an assumption of this class.
 */
public final class CollaborationConnectionRegistry {

  /** Outcome of an admission attempt; the caller is responsible for closing on rejection. */
  public sealed interface Admission {

    record Accepted(boolean overSoftBudget) implements Admission {}

    record Rejected(Reason reason) implements Admission {}
  }

  public enum Reason {
    PRINCIPAL_LIMIT,
    NODE_LIMIT,
    DUPLICATE_CONNECTION_ID
  }

  /** Immutable live-connection record; connection lifetime state stays in the handler. */
  public record LiveConnection(String connectionId, UUID accountId, Instant connectedAt) {}

  private final int maxConnectionsPerPrincipal;
  private final int softConnectionBudget;
  private final int hardConnectionLimit;

  private final Map<String, LiveConnection> connections = new ConcurrentHashMap<>();
  private final Map<UUID, Set<String>> connectionsByAccount = new ConcurrentHashMap<>();
  private final Object admissionLock = new Object();

  public CollaborationConnectionRegistry(
      int maxConnectionsPerPrincipal, int softConnectionBudget, int hardConnectionLimit) {
    if (maxConnectionsPerPrincipal < 1
        || softConnectionBudget < 1
        || hardConnectionLimit <= softConnectionBudget) {
      throw new IllegalArgumentException(
          "limits must satisfy 1<=perPrincipal, 1<=softBudget<hardLimit");
    }
    this.maxConnectionsPerPrincipal = maxConnectionsPerPrincipal;
    this.softConnectionBudget = softConnectionBudget;
    this.hardConnectionLimit = hardConnectionLimit;
  }

  /**
   * Atomically admits one connection. Check-and-insert runs under the admission lock because
   * connection setup is a low-frequency human-scale event; steady-state reads stay lock-free.
   */
  public Admission admit(String connectionId, UUID accountId) {
    synchronized (admissionLock) {
      if (connections.containsKey(connectionId)) {
        return new Admission.Rejected(Reason.DUPLICATE_CONNECTION_ID);
      }
      Set<String> existing = connectionsByAccount.get(accountId);
      if (existing != null && existing.size() >= maxConnectionsPerPrincipal) {
        return new Admission.Rejected(Reason.PRINCIPAL_LIMIT);
      }
      if (connections.size() >= hardConnectionLimit) {
        return new Admission.Rejected(Reason.NODE_LIMIT);
      }
      LiveConnection connection = new LiveConnection(connectionId, accountId, Instant.now());
      connections.put(connectionId, connection);
      connectionsByAccount
          .computeIfAbsent(accountId, key -> ConcurrentHashMap.newKeySet())
          .add(connectionId);
      return new Admission.Accepted(connections.size() > softConnectionBudget);
    }
  }

  /** Idempotently removes one connection; returns whether it was still registered. */
  public boolean release(String connectionId) {
    LiveConnection connection = connections.remove(connectionId);
    if (connection == null) {
      return false;
    }
    connectionsByAccount.computeIfPresent(
        connection.accountId(),
        (accountId, ids) -> {
          ids.remove(connectionId);
          return ids.isEmpty() ? null : ids;
        });
    return true;
  }

  public int connectionsFor(UUID accountId) {
    Set<String> ids = connectionsByAccount.get(accountId);
    return ids == null ? 0 : ids.size();
  }

  public int totalConnections() {
    return connections.size();
  }

  /** Snapshot of live connections for operational reads; order is unspecified. */
  public Set<LiveConnection> snapshot() {
    return Collections.unmodifiableSet(
        connections.values().stream().collect(Collectors.toSet()));
  }
}

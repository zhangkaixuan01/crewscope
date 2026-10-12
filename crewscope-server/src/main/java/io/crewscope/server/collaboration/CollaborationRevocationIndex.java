package io.crewscope.server.collaboration;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory reverse index for the D02 event-driven revocation sweep: which live
 * connections hold a Team subscription as which durable member. The transport identifies
 * connections by {@code principalKey} (account or external subject), but member lifecycle
 * events name the member's {@code userPrincipalId} — the only durable bridge is the
 * {@code Principal} the subscription rulebook already resolves, so the index is filled at
 * subscribe admission and drained at connection teardown. Connections without any
 * subscription in a Team receive no frame of that Team and are readmitted (or denied) per
 * subscribe frame, so they need no index entry.
 *
 * <p>Single-instance by design, exactly like {@link CollaborationConnectionRegistry}: the
 * multi-instance control channel is an ADR-032 reevaluation condition. Entries are
 * forgettable state — a lost entry degrades to the revalidation/TTL layers.</p>
 */
public final class CollaborationRevocationIndex {

  private final Map<String, Set<String>> connectionsByMember = new ConcurrentHashMap<>();
  private final Map<String, Set<String>> memberKeysByConnection = new ConcurrentHashMap<>();

  /** Records that a connection now holds a subscription in the member's Team. */
  public void record(
      UUID userPrincipalId, OrganizationId organizationId, TeamId team, String connectionId) {
    String memberKey = memberKey(userPrincipalId, organizationId, team);
    connectionsByMember
        .computeIfAbsent(memberKey, ignored -> ConcurrentHashMap.newKeySet())
        .add(connectionId);
    memberKeysByConnection
        .computeIfAbsent(connectionId, ignored -> ConcurrentHashMap.newKeySet())
        .add(memberKey);
  }

  /** Live connections of the member in that Team; empty when none ever subscribed. */
  public Set<String> connectionsOf(
      UUID userPrincipalId, OrganizationId organizationId, TeamId team) {
    Set<String> connections = connectionsByMember.get(memberKey(userPrincipalId, organizationId, team));
    return connections == null ? Set.of() : Set.copyOf(connections);
  }

  /**
   * Drops every entry of one connection (teardown and revocation share this path);
   * idempotent, and empty member buckets leave no residue.
   */
  public void forget(String connectionId) {
    Set<String> memberKeys = memberKeysByConnection.remove(connectionId);
    if (memberKeys == null) {
      return;
    }
    for (String memberKey : memberKeys) {
      connectionsByMember.computeIfPresent(
          memberKey,
          (key, connections) -> {
            connections.remove(connectionId);
            return connections.isEmpty() ? null : connections;
          });
    }
  }

  private static String memberKey(
      UUID userPrincipalId, OrganizationId organizationId, TeamId team) {
    return userPrincipalId + ":" + organizationId.value() + ":" + team.value();
  }
}

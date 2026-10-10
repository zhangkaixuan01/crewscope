package io.crewscope.server.collaboration;

import io.crewscope.domain.collaboration.CollaborationResourceScope;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Redis-only presence model frozen by ADR-032: one connection-key hash per WebSocket
 * connection carrying the TTL, plus one ZSET index per scope whose members are connection
 * ids scored by their expiry instant. Nothing here ever touches PostgreSQL, the outbox, or
 * the audit pipeline; presence is lossy by contract.
 *
 * <p>Write paths run fire-and-forget from the connection lifecycle: {@link #register} on the
 * first successful subscription of a connection (the organization/team coordinates only
 * exist once an authorization has succeeded), {@link #refresh} on every heartbeat tick for
 * the whole active subscription set, and {@link #removeConnection} on disconnect. The hash's
 * resourceType/resourceId fields mirror the most recently active subscription — the ADR
 * freezes them for a single-scope design — while the ZSET indexes carry the complete
 * multi-subscription truth. Since A01 the hash also carries the signal display name
 * (empty when the resolver fell back); the field keeps the ADR hash shape.</p>
 */
public final class CollaborationPresenceStore {

  /** One deduplicated entry per principal, carrying the raw connection count. */
  public record PresentPrincipal(
      String principalId,
      String displayName,
      String teamId,
      String resourceType,
      String resourceId,
      int connections) {}

  private final ReactiveStringRedisTemplate redis;
  private final CollaborationPresenceKeyspace keyspace;
  private final Duration presenceTtl;
  private final Clock clock;

  public CollaborationPresenceStore(
      ReactiveStringRedisTemplate redis,
      CollaborationPresenceKeyspace keyspace,
      Duration presenceTtl,
      Clock clock) {
    this.redis = redis;
    this.keyspace = keyspace;
    this.presenceTtl = presenceTtl;
    this.clock = clock;
  }

  /** Registers (or re-scopes) a connection without a display name (empty hash field). */
  public Mono<Void> register(
      String connectionId,
      String principalId,
      CollaborationResourceScope scope,
      Instant connectedAt) {
    return register(connectionId, principalId, scope, "", connectedAt);
  }

  /**
   * Registers (or re-scopes) a connection: conn hash with TTL (including the signal
   * display name) plus the scope ZSET entry.
   */
  public Mono<Void> register(
      String connectionId,
      String principalId,
      CollaborationResourceScope scope,
      String displayName,
      Instant connectedAt) {
    Map<String, String> fields = new LinkedHashMap<>();
    fields.put("organizationId", scope.organizationId().value().toString());
    fields.put("teamId", scope.teamId().value().toString());
    fields.put("principalId", principalId);
    fields.put("displayName", displayName == null ? "" : displayName);
    fields.put("resourceType", keyspace.resourceTypeSegment(scope));
    fields.put("resourceId", keyspace.resourceIdSegment(scope));
    fields.put("connectedAt", Long.toString(connectedAt.toEpochMilli()));
    fields.put("lastSeen", Long.toString(clock.millis()));
    String connectionKey = keyspace.connectionKey(connectionId);
    return Mono.when(
            redis.<String, String>opsForHash().putAll(connectionKey, fields),
            redis.expire(connectionKey, presenceTtl),
            redis
                .opsForZSet()
                .add(keyspace.scopeKey(scope), connectionId, expiryScore()))
        .then();
  }

  /**
   * Heartbeat refresh: the conn TTL and every active scope's ZSET score slide forward. Takes
   * pre-rendered scope keys — the caller's subscription registry only tracks those strings.
   */
  public Mono<Void> refresh(String connectionId, Collection<String> scopeKeys) {
    if (scopeKeys.isEmpty()) {
      return Mono.empty();
    }
    return redis
        .expire(keyspace.connectionKey(connectionId), presenceTtl)
        .thenMany(
            Flux.fromIterable(scopeKeys)
                .concatMap(scopeKey -> redis.opsForZSet().add(scopeKey, connectionId, expiryScore())))
        .then();
  }

  /** Removes a connection and all its scope index entries on disconnect or revocation. */
  public Mono<Void> removeConnection(String connectionId, Collection<String> scopeKeys) {
    return redis
        .delete(keyspace.connectionKey(connectionId))
        .thenMany(
            Flux.fromIterable(scopeKeys)
                .concatMap(scopeKey -> redis.opsForZSet().remove(scopeKey, connectionId)))
        .then();
  }

  /** Drops one scope entry on unsubscribe; the conn hash stays until disconnect. */
  public Mono<Void> removeScope(String connectionId, String scopeKey) {
    return redis.opsForZSet().remove(scopeKey, connectionId).then();
  }

  /**
   * Lists who is present in a scope, deduplicated per principal. The ZSET is lazily evicted
   * of expired members first; conn hashes that already expired surface as empty maps and
   * are skipped.
   */
  public Mono<List<PresentPrincipal>> listPresent(CollaborationResourceScope scope) {
    String scopeKey = keyspace.scopeKey(scope);
    long now = clock.millis();
    return redis
        .opsForZSet()
        .removeRangeByScore(scopeKey, Range.closed(0.0, (double) now))
        .thenMany(redis.opsForZSet().range(scopeKey, Range.<Long>unbounded()))
        .collectList()
        .flatMap(
            connectionIds -> {
              if (connectionIds.isEmpty()) {
                return Mono.just(List.<PresentPrincipal>of());
              }
              Map<String, PresentPrincipal> byPrincipal = new LinkedHashMap<>();
              return Mono.when(
                      connectionIds.stream()
                          .map(
                              id ->
                                  redis
                                      .<String, String>opsForHash()
                                      .entries(keyspace.connectionKey(id))
                                      .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                                      .doOnNext(
                                          hash -> {
                                            String principalId = hash.get("principalId");
                                            if (principalId == null) {
                                              return; // conn hash already expired
                                            }
                                            byPrincipal.merge(
                                                principalId,
                                                new PresentPrincipal(
                                                    principalId,
                                                    hash.getOrDefault("displayName", ""),
                                                    hash.get("teamId"),
                                                    hash.get("resourceType"),
                                                    hash.get("resourceId"),
                                                    1),
                                                (a, b) ->
                                                    new PresentPrincipal(
                                                        a.principalId(),
                                                        a.displayName(),
                                                        a.teamId(),
                                                        a.resourceType(),
                                                        a.resourceId(),
                                                        a.connections() + b.connections()));
                                          }))
                          .toList())
                  // thenReturn would evaluate its argument eagerly (before the HGETALLs
                  // fill byPrincipal); the supplier defers the copy until completion.
                  .then(Mono.fromSupplier(() -> new ArrayList<>(byPrincipal.values())));
            });
  }

  private double expiryScore() {
    return clock.millis() + presenceTtl.toMillis();
  }
}

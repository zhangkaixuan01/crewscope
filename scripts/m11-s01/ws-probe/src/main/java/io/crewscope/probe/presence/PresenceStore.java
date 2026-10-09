package io.crewscope.probe.presence;

import io.crewscope.probe.auth.PrincipalContext;
import io.crewscope.probe.config.ProbeProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Redis-only presence model frozen by ADR-032: one connection-key hash per
 * WebSocket connection carrying the TTL, plus one ZSET index per scope whose
 * members are connection ids scored by their expiry instant. Reads lazily
 * evict expired members before listing, and nothing here ever touches
 * PostgreSQL, the outbox, or the audit pipeline.
 *
 * <p>Write paths are invoked fire-and-forget from the connection lifecycle:
 * presence is lossy by contract and the probe measures the Redis-side
 * behaviour (TTL expiry, lazy eviction, per-principal dedup) directly, not
 * write acknowledgements.</p>
 */
@Component
public class PresenceStore {

    /** Isolated from every product key space (sessions, agent state, login defense). */
    public static final String KEY_PREFIX = "crewscope:probe:m11s01:collaboration:v1:";

    private final ReactiveStringRedisTemplate redis;
    private final Duration ttl;

    public PresenceStore(ReactiveStringRedisTemplate redis, ProbeProperties properties) {
        this.redis = redis;
        this.ttl = properties.presenceTtl();
    }

    public record Scope(String organizationId, String teamId,
            String resourceType, String resourceId) {

        public String key() {
            return KEY_PREFIX + "presence:scope:" + organizationId + ":" + teamId
                    + ":" + resourceType + ":" + resourceId;
        }
    }

    /** One deduplicated entry per principal, carrying the raw connection count. */
    public record PresenceView(String principalId, String displayName,
            String resourceType, String resourceId, int connections) {}

    private String connKey(String connectionId) {
        return KEY_PREFIX + "presence:conn:" + connectionId;
    }

    private double expiryScore() {
        return System.currentTimeMillis() + ttl.toMillis();
    }

    /** Registers (or re-scopes) a connection: conn hash + TTL + scope ZSET entry. */
    public Mono<Boolean> register(String connectionId, PrincipalContext principal,
            Scope scope, long connectedAt) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("organizationId", principal.organizationId());
        fields.put("teamId", principal.teamId());
        fields.put("principalId", principal.principalId());
        fields.put("displayName", principal.displayName());
        fields.put("resourceType", scope.resourceType());
        fields.put("resourceId", scope.resourceId());
        fields.put("connectedAt", Long.toString(connectedAt));
        fields.put("lastSeen", Long.toString(System.currentTimeMillis()));
        return Mono.zip(
                        redis.opsForHash().putAll(connKey(connectionId), fields),
                        redis.expire(connKey(connectionId), ttl),
                        redis.opsForZSet().add(scope.key(), connectionId, expiryScore()))
                .thenReturn(true);
    }

    /** Heartbeat refresh: conn TTL and the ZSET score both slide forward. */
    public Mono<Boolean> refresh(String connectionId, Scope scope) {
        return Mono.zip(
                        redis.expire(connKey(connectionId), ttl),
                        redis.opsForZSet().add(scope.key(), connectionId, expiryScore()))
                .thenReturn(true);
    }

    /** Removal on close/revocation, covering the scope the connection was in. */
    public Mono<Boolean> remove(String connectionId, Scope scope) {
        return Mono.zip(
                        redis.delete(connKey(connectionId)),
                        scope == null ? Mono.just(true)
                                : redis.opsForZSet().remove(scope.key(), connectionId))
                .thenReturn(true);
    }

    /**
     * Re-scoping only: drops the old scope's ZSET entry without touching the
     * conn hash — register rewrites that hash for the same connection id, and
     * deleting it here would race the register write (both are fire-and-forget)
     * and leave the ZSET pointing at a vanished hash.
     */
    public Mono<Boolean> removeFromScope(String connectionId, Scope scope) {
        if (scope == null) {
            return Mono.just(true);
        }
        return redis.opsForZSet().remove(scope.key(), connectionId).thenReturn(true);
    }

    /**
     * Lists who is present in a scope, deduplicated per principal. The ZSET is
     * lazily evicted of expired members first; conn hashes that already
     * expired surface as empty maps and are skipped.
     */
    public Mono<List<PresenceView>> listPresent(Scope scope) {
        long now = System.currentTimeMillis();
        return redis.opsForZSet()
                .removeRangeByScore(scope.key(),
                        org.springframework.data.domain.Range.closed(0.0, (double) now))
                .thenMany(redis.opsForZSet().range(scope.key(),
                        org.springframework.data.domain.Range.<Long>unbounded()))
                .collectList()
                .flatMap(ids -> {
                    Map<String, PresenceView> byPrincipal = new LinkedHashMap<>();
                    return ids.isEmpty() ? Mono.just(List.<PresenceView>of())
                            : Mono.when(ids.stream().map(id -> redis.<String, String>opsForHash()
                                            .entries(connKey(id))
                                            .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                                            .doOnNext(hash -> {
                                                String principalId = hash.get("principalId");
                                                if (principalId == null) {
                                                    return; // conn hash already expired
                                                }
                                                byPrincipal.merge(principalId,
                                                        new PresenceView(principalId,
                                                                hash.get("displayName"),
                                                                hash.get("resourceType"),
                                                                hash.get("resourceId"), 1),
                                                        (a, b) -> new PresenceView(a.principalId(),
                                                                a.displayName(), a.resourceType(),
                                                                a.resourceId(),
                                                                a.connections() + b.connections()));
                                            }))
                                    .toList())
                            // thenReturn would evaluate its argument eagerly
                            // (before the HGETALLs fill byPrincipal); the
                            // supplier defers the copy until completion.
                            .then(Mono.fromSupplier(() -> new ArrayList<>(byPrincipal.values())));
                });
    }
}

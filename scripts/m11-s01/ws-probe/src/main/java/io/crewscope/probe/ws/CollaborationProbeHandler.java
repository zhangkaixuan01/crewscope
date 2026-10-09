package io.crewscope.probe.ws;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.crewscope.probe.auth.MembershipSimulator;
import io.crewscope.probe.auth.PrincipalContext;
import io.crewscope.probe.config.ProbeProperties;
import io.crewscope.probe.presence.PresenceStore;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * The probe's collaboration signal endpoint. Protocol (text frames, JSON):
 *
 * <pre>
 * client -> server: {"type":"subscribe"|"unsubscribe","scope":{"team","resourceType","resourceId"}}
 *                   {"type":"presence","scope":{...}}
 *                   {"type":"typing","scope":{...},"state":"started"|"stopped"}
 *                   {"type":"ping"}
 * server -> client: {"type":"welcome"|"subscribed"|"unsubscribed"|"presence"|"typing"
 *                    |"changed"|"pong"|"error", ...}
 * </pre>
 *
 * Every outbound frame passes the revalidation layer (membership with the 5s
 * cache — a revoked member is closed with 4403 on the next emit), the
 * heartbeat ticks every 15s and closes after 30s of inbound silence, and the
 * outbound sink is bounded so a slow client is dropped rather than allowed
 * to grow server memory. Cross-team subscribe attempts are denied and
 * counted. The organization of a scope is always taken from the principal,
 * never from the frame.
 */
@Component
public class CollaborationProbeHandler implements WebSocketHandler {

    public static final CloseStatus CLOSE_REVOKED = new CloseStatus(4403, "authorization revoked");
    public static final CloseStatus CLOSE_IDLE = new CloseStatus(1000, "inbound idle");
    public static final CloseStatus CLOSE_SLOW = new CloseStatus(1013, "outbound overflow");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> FRAME_TYPE =
            new TypeReference<>() {};

    private final ConnectionRegistry registry;
    private final PresenceStore presence;
    private final MembershipSimulator membership;
    private final ProbeProperties properties;

    public CollaborationProbeHandler(ConnectionRegistry registry, PresenceStore presence,
            MembershipSimulator membership, ProbeProperties properties) {
        this.registry = registry;
        this.presence = presence;
        this.membership = membership;
        this.properties = properties;
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        // Spring Framework 7 passes the WebSession's attribute map (not the
        // exchange attributes, as 6.x did) into HandshakeInfo — so the
        // principal the login controller stored in the session is right here.
        // The filter chain has already rejected unauthenticated upgrades.
        Object resolved = session.getAttributes().get(PrincipalContext.SESSION_ATTRIBUTE);
        if (!(resolved instanceof PrincipalContext principal)) {
            return session.close(CLOSE_REVOKED);
        }
        ProbeConnection conn = registry.register(session, principal);
        PresenceStore.Scope scope = new PresenceStore.Scope(principal.organizationId(),
                principal.teamId(), "SESSION", "connected");
        conn.setPresenceScope(scope);
        presence.register(conn.id(), principal, scope, conn.connectedAt()).subscribe();
        emit(conn, frame("welcome", Map.of(
                "connectionId", conn.id(),
                "principal", principal.principalId(),
                "heartbeatIntervalSeconds", properties.heartbeatInterval().toSeconds(),
                "presenceTtlSeconds", properties.presenceTtl().toSeconds())));

        Mono<Void> inbound = session.receive()
                .map(message -> message.getPayloadAsText())
                .concatMap(text -> parseAndHandle(conn, text))
                .doOnComplete(() -> conn.outbound().tryEmitComplete())
                .then();
        Mono<Void> outbound = session.send(
                conn.outbound().asFlux().map(session::textMessage));
        Mono<Void> heartbeat = Flux.interval(properties.heartbeatInterval())
                .onBackpressureDrop()
                .concatMap(tick -> heartbeatTick(conn))
                .then();
        return Mono.zip(inbound, outbound, heartbeat)
                .then()
                .doFinally(signal -> cleanup(conn));
    }

    private Mono<Void> parseAndHandle(ProbeConnection conn, String text) {
        Map<String, Object> frame;
        try {
            frame = JSON.readValue(text, FRAME_TYPE);
        } catch (Exception e) {
            conn.markInbound();
            registry.framesIn().incrementAndGet();
            emit(conn, frame("error", Map.of("code", "invalid_json")));
            return Mono.empty();
        }
        return handleFrame(conn, frame);
    }

    private Mono<Void> heartbeatTick(ProbeConnection conn) {
        long idleMs = System.currentTimeMillis() - conn.lastInboundAt();
        if (idleMs > properties.inboundIdleLimit().toMillis()) {
            close(conn, CLOSE_IDLE);
            return Mono.empty();
        }
        PresenceStore.Scope scope = conn.presenceScope();
        if (scope != null) {
            presence.refresh(conn.id(), scope).subscribe();
        }
        emit(conn, frame("ping", Map.of("t", System.currentTimeMillis())));
        return Mono.empty();
    }

    private Mono<Void> handleFrame(ProbeConnection conn, Map<String, Object> frame) {
        conn.markInbound();
        registry.framesIn().incrementAndGet();
        String type = String.valueOf(frame.get("type"));
        switch (type) {
            case "subscribe" -> {
                return onSubscribe(conn, frame);
            }
            case "unsubscribe" -> {
                Object raw = frame.get("scope");
                if (raw instanceof Map<?, ?> scopeMap) {
                    String key = scopeKeyFor(conn, scopeMap);
                    conn.subscriptions().remove(key);
                }
                emit(conn, frame("unsubscribed", Map.of()));
                return Mono.empty();
            }
            case "presence" -> {
                Object raw = frame.get("scope");
                if (!(raw instanceof Map<?, ?> scopeMap)) {
                    emit(conn, frame("error", Map.of("code", "invalid_frame")));
                    return Mono.empty();
                }
                PresenceStore.Scope scope = toScope(conn, scopeMap);
                return presence.listPresent(scope)
                        .doOnNext(views -> emit(conn, frame("presence", Map.of(
                                "scope", scopeDescription(scope), "present", views))))
                        .then();
            }
            case "typing" -> {
                Object raw = frame.get("scope");
                if (raw instanceof Map<?, ?> scopeMap) {
                    PresenceStore.Scope scope = toScope(conn, scopeMap);
                    fanout(scope.key(), frame("typing", Map.of(
                            "principal", conn.principal().principalId(),
                            "displayName", conn.principal().displayName(),
                            "state", String.valueOf(frame.getOrDefault("state", "started")),
                            "scope", scopeDescription(scope))), conn.id());
                }
                return Mono.empty();
            }
            case "ping" -> {
                emit(conn, frame("pong", Map.of("t", System.currentTimeMillis())));
                return Mono.empty();
            }
            default -> {
                emit(conn, frame("error", Map.of("code", "unknown_type", "type", type)));
                return Mono.empty();
            }
        }
    }

    private Mono<Void> onSubscribe(ProbeConnection conn, Map<String, Object> frame) {
        Object raw = frame.get("scope");
        if (!(raw instanceof Map<?, ?> scopeMap)) {
            emit(conn, frame("error", Map.of("code", "invalid_frame")));
            return Mono.empty();
        }
        // Cross-team subscriptions are denied and counted: the probe's
        // equivalent of the ExecutionScope intersection.
        String requestedTeam = String.valueOf(scopeMap.get("team"));
        if (!conn.principal().teamId().equals(requestedTeam)) {
            registry.deniedSubscriptions().incrementAndGet();
            emit(conn, frame("error", Map.of("code", "cross_team_denied",
                    "team", requestedTeam)));
            return Mono.empty();
        }
        PresenceStore.Scope scope = toScope(conn, scopeMap);
        PresenceStore.Scope previous = conn.presenceScope();
        conn.subscriptions().add(scope.key());
        conn.setPresenceScope(scope);
        presence.register(conn.id(), conn.principal(), scope, conn.connectedAt()).subscribe();
        if (previous != null && !previous.key().equals(scope.key())) {
            presence.removeFromScope(conn.id(), previous).subscribe();
        }
        emit(conn, frame("subscribed", Map.of("scope", scopeDescription(scope))));
        return Mono.empty();
    }

    /**
     * Admin fan-out entry: emits {@code count} change notifications carrying
     * only the stable coordinates and the new version — zero business
     * payload, the contract ADR-032 freezes. Returns the delivery attempts
     * of the last emission (subscribers that failed revalidation are closed,
     * not counted).
     */
    public int notifyChanged(String organizationId, String teamId,
            String resourceType, String resourceId, long version, int count) {
        PresenceStore.Scope scope =
                new PresenceStore.Scope(organizationId, teamId, resourceType, resourceId);
        int delivered = 0;
        for (int i = 0; i < count; i++) {
            delivered = fanout(scope.key(), frame("changed", Map.of(
                    "scope", scopeDescription(scope), "version", version + i)), null);
        }
        return delivered;
    }

    private int fanout(String scopeKey, Map<String, Object> payload, String excludeConnectionId) {
        int delivered = 0;
        for (ProbeConnection c : registry.connections()) {
            if (c.id().equals(excludeConnectionId)) {
                continue;
            }
            if (c.subscriptions().contains(scopeKey)) {
                emit(c, payload);
                delivered++;
            }
        }
        return delivered;
    }

    /**
     * The revalidation layer + bounded emit. A member that fails
     * revalidation is closed with 4403; a client whose buffer is full is
     * closed as slow instead of growing memory.
     */
    private void emit(ProbeConnection conn, Map<String, Object> payload) {
        if (!membership.isMember(conn.principal().teamId(), conn.principal().principalId())) {
            close(conn, CLOSE_REVOKED);
            return;
        }
        try {
            Sinks.EmitResult result = conn.outbound().tryEmitNext(JSON.writeValueAsString(payload));
            if (result.isSuccess()) {
                registry.framesOut().incrementAndGet();
            } else {
                close(conn, CLOSE_SLOW);
            }
        } catch (Exception e) {
            close(conn, CLOSE_SLOW);
        }
    }

    private void close(ProbeConnection conn, CloseStatus status) {
        if (conn.tryClose()) {
            conn.session().close(status).subscribe();
        }
    }

    private void cleanup(ProbeConnection conn) {
        if (conn.tryClose()) {
            conn.session().close(CloseStatus.NORMAL).subscribe(null, e -> { });
        }
        registry.unregister(conn);
        presence.remove(conn.id(), conn.presenceScope()).subscribe();
    }

    private PresenceStore.Scope toScope(ProbeConnection conn, Map<?, ?> scopeMap) {
        Object resourceType = scopeMap.get("resourceType");
        Object resourceId = scopeMap.get("resourceId");
        return new PresenceStore.Scope(
                conn.principal().organizationId(),
                String.valueOf(scopeMap.get("team")),
                resourceType == null ? "WORK_ITEM" : String.valueOf(resourceType),
                resourceId == null ? "default" : String.valueOf(resourceId));
    }

    private String scopeKeyFor(ProbeConnection conn, Map<?, ?> scopeMap) {
        return toScope(conn, scopeMap).key();
    }

    private Map<String, Object> scopeDescription(PresenceStore.Scope scope) {
        Map<String, Object> description = new LinkedHashMap<>();
        description.put("team", scope.teamId());
        description.put("resourceType", scope.resourceType());
        description.put("resourceId", scope.resourceId());
        return description;
    }

    private Map<String, Object> frame(String type, Map<String, Object> fields) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", type);
        frame.putAll(fields);
        return frame;
    }
}
